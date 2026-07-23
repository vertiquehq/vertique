// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationHeaderValidator;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.correlation.CorrelationMdcKeys;
import dev.vertique.correlation.TraceReferenceResolver;
import dev.vertique.logging.MDCContexts;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig.InvalidValuePolicy;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ROOT-scoped middleware that builds the live {@link CorrelationContext} for the request,
 * binds it via the substrate, mirrors the safe-by-default MDC keys, optionally mirrors the
 * active distributed-trace identity, and emits the configured response headers
 * (FR-COR-080..090, FR-COR-094, FR-COR-125 for REST ingress only).
 *
 * <p>Execution order: {@link RequestContextLifecycle#ORDER} + 10 so it runs after the lifecycle
 * middleware (which provides the {@link RequestContextLifecycle.Handle handle} this middleware
 * uses to register scope closes) and before {@code IdentityResolutionMiddleware} so the latter can
 * read the bound correlation context without ordering surprises.
 *
 * <p>Resolution flow per request:
 * <ol>
 *   <li>Resolve the request id from the configured inbound header (or generate when absent /
 *       invalid per policy). Source is recorded on the resulting {@link CorrelationIdentifier}.</li>
 *   <li>Resolve the correlation id from its inbound header; falls back to the request id with
 *       {@code source="generated-from-request-id"} when absent (FR-COR-084).</li>
 *   <li>Optionally resolve the causation id from its inbound header (FR-COR-085 MAY).</li>
 *   <li>Build a {@link CorrelationContext} via {@link CorrelationContextFactory#create} and bind
 *       it on the holder; register the scope close with the request lifecycle handle.</li>
 *   <li>Snapshot the framework-mirrored MDC keys via
 *       {@link MDCContexts#snapshotKeys(Set)} so any keys added later by enrichers (e.g. trace
 *       ids) are restored at request end. Write the initial mirrored entries
 *       (requestId, correlationId, causationId) inline via {@code MDCContexts.put}.</li>
 *   <li>If a {@link TraceReferenceResolver} is present, consult it for the active trace identity
 *       and call {@link CorrelationContextMutator#setTrace} when a trace is returned. The call is
 *       guarded with its own {@code try/catch} so a failing resolver emits a {@code WARN} log
 *       entry without interrupting the request pipeline.</li>
 *   <li>Apply the REJECT policy if any inbound header failed validation (short-circuits before
 *       protocol-correlation processing).</li>
 *   <li>Walk the registered {@link ProtocolCorrelationSpec}s (default path) followed by the
 *       {@link ProtocolCorrelationContributor}s (escape hatch); append every resolved
 *       {@link ProtocolCorrelationRef} via {@link CorrelationContextMutator#addProtocolCorrelation}.</li>
 *   <li>Emit configured response headers: the request id is echoed when
 *       {@link CorrelationIngressConfig#echoRequestId()} is true, the correlation id when
 *       {@code echoCorrelationId} is true, and every protocol ref with a non-{@code NONE}
 *       response mode is written verbatim.</li>
 * </ol>
 *
 * <p>The middleware uses {@code ctx.addHeadersEndHandler} so response headers are written before
 * the response is committed; the lifecycle handle ensures the holder binding and the MDC scope
 * are torn down at request end regardless of completion path.
 */
@Singleton
public final class CorrelationIngressMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIngressMiddleware.class);

    /**
     * Runs after {@link RequestContextLifecycle} ({@link Integer#MIN_VALUE}) and before any
     * middleware whose order is &gt; this value, including {@code ContextualLoggingMiddleware}
     * ({@code 0}) and security middlewares.
     */
    public static final int ORDER = RequestContextLifecycle.ORDER + 10;

    private final ContextHolder holder;
    private final CorrelationContextFactory factory;
    private final CorrelationContextMutator mutator;
    private final CorrelationIngressConfig config;
    private final Set<ProtocolCorrelationSpec> protocolSpecs;
    private final Set<ProtocolCorrelationContributor> protocolContributors;
    private final Optional<TraceReferenceResolver> traceReferenceResolver;

    /**
     * Constructs the middleware with all required collaborators.
     *
     * @param holder                 the context holder used to bind and look up the live context
     * @param factory                builds and seeds fresh {@link CorrelationContext} instances
     * @param mutator                writes enrichments (causation id, trace ids) into the live context
     * @param config                 resolved ingress configuration (headers, policy, echo flags)
     * @param protocolSpecs          declarative protocol-correlation specs contributed via multibinding
     * @param protocolContributors   escape-hatch contributors for protocol-correlation refs
     * @param traceReferenceResolver optional resolver for the active distributed-trace identity;
     *                               absent when no tracer module is wired
     */
    @Inject
    public CorrelationIngressMiddleware(
            ContextHolder holder,
            CorrelationContextFactory factory,
            CorrelationContextMutator mutator,
            CorrelationIngressConfig config,
            Set<ProtocolCorrelationSpec> protocolSpecs,
            Set<ProtocolCorrelationContributor> protocolContributors,
            Optional<TraceReferenceResolver> traceReferenceResolver) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.mutator = Objects.requireNonNull(mutator, "mutator");
        this.config = Objects.requireNonNull(config, "config");
        this.protocolSpecs = Set.copyOf(Objects.requireNonNull(protocolSpecs, "protocolSpecs"));
        this.protocolContributors = Set.copyOf(Objects.requireNonNull(protocolContributors, "protocolContributors"));
        this.traceReferenceResolver = Objects.requireNonNull(traceReferenceResolver, "traceReferenceResolver");
    }

    /**
     * Returns the execution priority for this middleware.
     *
     * @return {@link #ORDER} ({@link RequestContextLifecycle#ORDER} + 10)
     */
    @Override
    public int priority() {
        return ORDER;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    @Override
    public void handle(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);

        // Collect header names whose inbound value failed validation; used to decide REJECT vs
        // REPLACE_WITH_GENERATED below.
        List<String> invalidHeaders = new ArrayList<>(0);
        CorrelationIdentifier requestId = resolveRequestId(request, invalidHeaders);
        CorrelationIdentifier correlationId = resolveCorrelationId(request, requestId, invalidHeaders);
        CorrelationIdentifier causationId = resolveCausationId(request, invalidHeaders);

        CorrelationContext live = factory.create(requestId, correlationId);
        ContextHolder.Scope holderScope = holder.bind(CorrelationContext.class, live);
        lifecycle.onClose(holderScope);

        // Snapshot the safe-by-default mirrored keys before any enricher (this middleware
        // included) touches MDC. Late additions (traceId/spanId by tracing middleware) will
        // also be restored because the scope captured their absence at this point.
        ContextHolder.Scope mdcSnapshotScope = MDCContexts.snapshotKeys(CorrelationMdcKeys.MIRRORED);
        lifecycle.onClose(mdcSnapshotScope);

        // Write the initial mirrored entries.
        MDCContexts.put(CorrelationMdcKeys.REQUEST_ID, requestId.value());
        MDCContexts.put(CorrelationMdcKeys.CORRELATION_ID, correlationId.value());
        if (causationId != null) {
            mutator.setCausationId(causationId);
        }

        // Mirror the active distributed-trace identity into the live context (and MDC via mutator).
        // Guarded by its own try/catch: optional telemetry must never fail REST ingress. A throwing
        // resolver emits a WARN but does not interrupt the request pipeline.
        try {
            traceReferenceResolver.flatMap(TraceReferenceResolver::currentTrace).ifPresent(mutator::setTrace);
        } catch (Exception e) {
            log.warn("TraceReferenceResolver failed: {}", e.getClass().getSimpleName());
        }

        // Response-header emission runs before headers are committed. Registered before any
        // possible REJECT short-circuit below so 4xx rejection responses still carry the
        // X-Request-Id (the bound context is the generated one when inbound was invalid).
        ctx.addHeadersEndHandler(v -> writeResponseHeaders(ctx));

        // Apply REJECT policy if any inbound header failed validation.
        if (!invalidHeaders.isEmpty() && config.invalidValuePolicy() == InvalidValuePolicy.REJECT) {
            log.warn("rejecting request: invalid correlation header value(s) on {} (policy=REJECT)", invalidHeaders);
            ctx.fail(
                    400,
                    new BadRequestException(
                            "Invalid correlation header value(s): " + String.join(", ", invalidHeaders)));
            return;
        }

        // Process protocol-correlation specs (default declarative path) then contributors. Done
        // AFTER the REJECT check so a rejected request never appends protocol refs that won't
        // be visible to downstream handlers anyway.
        for (ProtocolCorrelationSpec spec : protocolSpecs) {
            ProtocolCorrelationRef ref = resolveFromSpec(spec, request);
            if (ref != null) {
                mutator.addProtocolCorrelation(ref);
            }
        }
        for (ProtocolCorrelationContributor contributor : protocolContributors) {
            Optional<ProtocolCorrelationRef> resolved = contributor.resolve(request);
            resolved.ifPresent(mutator::addProtocolCorrelation);
        }

        ctx.next();
    }

    // --- Resolution helpers ---

    private CorrelationIdentifier resolveRequestId(HttpServerRequest request, List<String> invalidHeaders) {
        String inbound = request.getHeader(config.requestIdHeader());
        // Absent header (null) is OK — generate without recording an error.
        if (inbound == null) {
            return new CorrelationIdentifier(factory.generator().generate(), "generated");
        }
        // Header was sent: validate as one unit. Blank values fail the validator and are
        // therefore "invalid supplied values" subject to the InvalidValuePolicy.
        if (CorrelationHeaderValidator.isValidHeaderValue(inbound)) {
            return new CorrelationIdentifier(inbound, "http-header");
        }
        log.warn("invalid {} header value supplied at ingress, replacing with generated id", config.requestIdHeader());
        invalidHeaders.add(config.requestIdHeader());
        return new CorrelationIdentifier(factory.generator().generate(), "generated");
    }

    private CorrelationIdentifier resolveCorrelationId(
            HttpServerRequest request, CorrelationIdentifier requestId, List<String> invalidHeaders) {
        String inbound = request.getHeader(config.correlationIdHeader());
        if (inbound == null) {
            // FR-COR-084: header absent — fall back to request id with provenance recorded.
            return new CorrelationIdentifier(requestId.value(), "generated-from-request-id");
        }
        if (CorrelationHeaderValidator.isValidHeaderValue(inbound)) {
            return new CorrelationIdentifier(inbound, "http-header");
        }
        log.warn(
                "invalid {} header value supplied at ingress, deriving from request id instead",
                config.correlationIdHeader());
        invalidHeaders.add(config.correlationIdHeader());
        return new CorrelationIdentifier(requestId.value(), "generated-from-request-id");
    }

    private CorrelationIdentifier resolveCausationId(HttpServerRequest request, List<String> invalidHeaders) {
        if (!config.parseCausationId()) {
            return null;
        }
        String inbound = request.getHeader(config.causationIdHeader());
        if (inbound == null) {
            // Causation is optional — absent header is not an error.
            return null;
        }
        if (!CorrelationHeaderValidator.isValidHeaderValue(inbound)) {
            log.warn("invalid {} header value supplied at ingress, dropping causation id", config.causationIdHeader());
            invalidHeaders.add(config.causationIdHeader());
            return null;
        }
        return new CorrelationIdentifier(inbound, "http-header");
    }

    private ProtocolCorrelationRef resolveFromSpec(ProtocolCorrelationSpec spec, HttpServerRequest request) {
        String inbound = request.getHeader(spec.headerName());
        Optional<String> accepted = spec.acceptInbound(inbound);

        String value;
        String source;
        if (accepted.isPresent()) {
            value = accepted.get();
            source = "http-header";
        } else if (spec.responseMode() == CorrelationResponseMode.ECHO_OR_GENERATE_RFC4122) {
            value = spec.generate();
            source = "generated";
        } else {
            // Spec has no obligation to emit — no ref produced.
            return null;
        }

        return new ProtocolCorrelationRef(
                spec.headerName(),
                value,
                source,
                spec.responseMode(),
                spec.propagationMode(),
                spec.durableSafe(),
                spec.attributes());
    }

    // --- Response emission ---

    private void writeResponseHeaders(RoutingContext ctx) {
        CorrelationContext live = holder.current(CorrelationContext.class).orElse(null);
        if (live == null) {
            return;
        }
        if (config.echoRequestId()) {
            CorrelationHeaderValidator.sanitizeForResponse(live.requestId().value())
                    .ifPresent(safe -> ctx.response().putHeader(config.requestIdHeader(), safe));
        }
        if (config.echoCorrelationId()) {
            CorrelationHeaderValidator.sanitizeForResponse(live.correlationId().value())
                    .ifPresent(safe -> ctx.response().putHeader(config.correlationIdHeader(), safe));
        }
        for (ProtocolCorrelationRef ref : live.protocolCorrelations()) {
            if (ref.responseMode() == CorrelationResponseMode.NONE) {
                continue;
            }
            CorrelationHeaderValidator.sanitizeForResponse(ref.value())
                    .ifPresent(safe -> ctx.response().putHeader(ref.headerName(), safe));
        }
    }
}
