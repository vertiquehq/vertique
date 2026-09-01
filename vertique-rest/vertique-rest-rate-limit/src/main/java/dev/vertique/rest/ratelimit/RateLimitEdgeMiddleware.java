// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.jaxrs.DefaultExceptionMapper;
import dev.vertique.rest.jaxrs.ExceptionMapperRegistry;
import dev.vertique.rest.security.OriginCaptureMiddleware;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional, config-driven {@link Middleware} contributed at {@link MiddlewareScope#ROOT} that
 * applies ordered, composable-dimension admission rules pre-authorization
 * (contracts/rest-adapter.md, "Edge limiter"). Mounted before any {@code RouterMount}/{@code
 * BodyHandler}, and strictly after {@link OriginCaptureMiddleware} at {@link #ORDER}.
 *
 * <p>Rules are evaluated in declared order via {@link RateLimiter#acquire}; the first
 * non-permitting decision wins and stops further evaluation. Tokens already consumed by
 * earlier-permitting rules stay consumed.
 *
 * <p><b>Denial rendering.</b> A {@code QUOTA_EXCEEDED}/{@code BACKEND_FAILURE_CLOSED} decision
 * mints the same {@link RateLimitExceededException}/{@link RateLimitUnavailableException} the
 * {@code execute()}/{@code @RateLimited} path throws, and resolves it through the application's
 * own {@link ExceptionMapperRegistry} — the same mapper-resolution semantics (most-specific
 * registered {@code ExceptionMapper} by superclass walk) that path uses. This class still never
 * uses Java {@code throw}/{@code catch} control flow for a rate-limit exception; the exception
 * instance exists only as the typed argument an {@code ExceptionMapper<T>} expects. An
 * application that contributes its own {@code ExceptionMapper<RateLimitExceededException>}/{@code
 * ExceptionMapper<RateLimitUnavailableException>} (or a common supertype) therefore renders the
 * edge denial the same way it already renders one thrown by {@code execute()} — no separate
 * customization surface to learn. With no application override, the response is byte-identical to
 * the framework's built-in {@code RateLimitExceptionMapper} shape.
 *
 * <p>Two paths carry no {@link RateLimitDecision} at all — an absent {@code RequestOrigin} under
 * {@code failureMode=CLOSED} (no {@code acquire} was ever called) and a defensive
 * {@code acquire()}-future failure — and always render the fixed, generic {@code 503} body
 * directly; there is no decision to mint an exception from, so the application mapper chain is
 * never consulted on those two paths.
 *
 * <p>Entity serialization supports the shapes the framework's own mappers produce: a {@code
 * String}/{@link Buffer} entity is written through as-is, a {@code null} entity yields an empty
 * body, and any other entity (e.g. {@link ProblemDetail}) is serialized via the shared Vert.x JSON
 * codec ({@link Json#encode}) — the same codec the JAX-RS response pipeline itself falls back to
 * outside a per-method resolved body-mapper profile. Full JAX-RS pipeline fidelity (a per-method
 * profile mapper, response interceptors, {@code ProblemDetail} enrichment) is not reachable from
 * this {@code ROOT}-scope construction point and is an accepted limitation — the {@code
 * ExceptionMapper} itself is the customization contract, not the surrounding pipeline. If a
 * resolved mapper throws, or response rendering itself fails, this middleware falls back to the
 * fixed built-in response rather than ever propagating an unhandled failure. Redaction guarantees
 * (no key material, identity, backend detail, or exception message) apply to the framework's own
 * default mapper only; an application-contributed mapper choosing to expose more is its own
 * decision.
 */
public final class RateLimitEdgeMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(RateLimitEdgeMiddleware.class);

    /** {@link OriginCaptureMiddleware#ORDER} + 20 (contracts/rest-adapter.md, "Edge limiter"). */
    public static final int ORDER = OriginCaptureMiddleware.ORDER + 20;

    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    // Both bodies are constant across every request: neither status carries a per-request field
    // (no policyName/rule identity/quota detail — contracts/rest-adapter.md, "HTTP mapping"), so
    // the JSON encoding is computed once at class-load time instead of on every denial.
    private static final String EXCEEDED_BODY = Json.encode(ProblemDetail.of(429, null));
    private static final String UNAVAILABLE_BODY = Json.encode(ProblemDetail.of(503, null));

    private final List<CompiledRule> rules;
    private final String path;
    private final ExceptionMapperRegistry exceptionMapperRegistry;

    /**
     * Compiles {@code config}'s rules against {@code rateLimiters}, resolving one {@link
     * RateLimiter} handle per rule via {@link RateLimiters#limiter(String)} — an unknown policy
     * name fails synchronously here, exactly as any other {@code limiter(...)} caller observes
     * (contracts/rest-adapter.md, "Configuration").
     *
     * @param config the enabled edge config (callers must not construct this for a disabled config)
     * @param rateLimiters the application-scoped rate-limit runtime
     * @param originCaptureBound whether {@link OriginCaptureMiddleware} is bound in the Dagger
     *     graph; an {@code IP}-dimension rule fails construction when this is {@code false}
     *     (contracts/rest-adapter.md, "Rule composition semantics" — IP mechanism, startup
     *     validation)
     * @param exceptionMappers the application's full {@code Set<ExceptionMapper<?>>} multibinding
     *     (rest-core's {@code RestCoreModule}, available at {@code ROOT} scope independent of
     *     whether the JAX-RS routing runtime is installed) — resolved into an {@link
     *     ExceptionMapperRegistry} this middleware alone owns, over an empty {@link
     *     DefaultExceptionMapper} (no JAX-RS-pipeline-specific built-in mappings; see this class's
     *     javadoc, "Denial rendering")
     * @throws ConfigurationException if an {@code IP}-dimension rule is declared without {@code
     *     originCaptureBound}, or a rule's declared {@code cost} exceeds its referenced policy's
     *     capacity
     * @throws IllegalArgumentException if a rule references an unknown policy
     */
    public RateLimitEdgeMiddleware(
            RateLimitEdgeConfig config,
            RateLimiters rateLimiters,
            boolean originCaptureBound,
            Set<ExceptionMapper<?>> exceptionMappers) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(rateLimiters, "rateLimiters");
        Objects.requireNonNull(exceptionMappers, "exceptionMappers");
        this.exceptionMapperRegistry = new ExceptionMapperRegistry(new DefaultExceptionMapper(), exceptionMappers);
        this.path = config.path();
        List<CompiledRule> compiled = new ArrayList<>(config.rules().size());
        for (RateLimitEdgeRule rule : config.rules()) {
            boolean usesIp = rule.key().contains(RateLimitEdgeKeyDimension.IP);
            if (usesIp && !originCaptureBound) {
                throw new ConfigurationException("rateLimit.rest.edge rule for policy '" + rule.policy()
                        + "' uses the IP dimension, but OriginCaptureMiddleware is not bound — "
                        + "co-install vertique-rest-security's AuthModule");
            }
            RateLimiter limiter = rateLimiters.limiter(rule.policy());
            if (rule.cost().isPresent() && rule.cost().getAsLong() > limiter.capacity()) {
                throw new ConfigurationException("rateLimit.rest.edge rule for policy '" + rule.policy()
                        + "' declares cost " + rule.cost().getAsLong() + " exceeding policy capacity "
                        + limiter.capacity());
            }
            // Read directly off the resolved handle (RateLimiter#failureMode()) rather than
            // re-deriving it from a separate configuration source: this classifies correctly for a
            // policy declared only via a Dagger @IntoSet RateLimitPolicy contribution, which carries
            // no rateLimit.policies.<name> JSON section at all (contracts/rate-limit-runtime.md,
            // "Policy model"; RateLimitCoreModule merges config- and programmatic-tier policies
            // before either RateLimiters or this middleware ever sees them).
            RateLimitFailureMode ipFailureMode = usesIp ? limiter.failureMode() : null;
            compiled.add(new CompiledRule(rule, limiter, ipFailureMode));
        }
        this.rules = List.copyOf(compiled);
    }

    @Override
    public int priority() {
        return ORDER;
    }

    @Override
    public MiddlewareScope scope() {
        return MiddlewareScope.ROOT;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public void handle(RoutingContext ctx) {
        // Rule evaluation calls RateLimiter.acquire(...), which may resolve asynchronously (a
        // CLUSTERED backend's network round trip in particular). Vert.x's HttpServerRequest
        // auto-drains and marks itself ended if the handler that first sees it returns to the
        // event loop without a body consumer attached or the stream paused — which would make any
        // later BodyHandler fail ("BodyHandler invoked after the request has ended"). Pausing here
        // and resuming exactly once on every terminal path below (permit-through, 429, 503)
        // preserves the body for whatever runs next, matching Vert.x's own body-preservation
        // contract for a handler that cannot decide synchronously.
        ctx.request().pause();
        evaluate(ctx, 0);
    }

    private void evaluate(RoutingContext ctx, int index) {
        if (index >= rules.size()) {
            ctx.request().resume();
            ctx.next();
            return;
        }
        CompiledRule compiled = rules.get(index);
        KeyOutcome outcome = compiled.deriveKey(ctx);
        if (outcome instanceof Bypassed) {
            evaluate(ctx, index + 1);
            return;
        }
        if (outcome instanceof OriginAbsent) {
            if (compiled.ipFailureMode() == RateLimitFailureMode.CLOSED) {
                ctx.request().resume();
                respondUnavailable(ctx);
            } else {
                // OPEN admits without ever deriving a key or calling acquire() — log internally so
                // an operator can see this rule is silently bypassed (never surfaced to the
                // response body/headers: contracts/rest-adapter.md, "HTTP mapping" carries no
                // policy/rule identity). Policy name only; never the request's key material.
                log.warn(
                        "rate-limit edge rule for policy '{}': RequestOrigin absent, failureMode=OPEN — admitting"
                                + " request without evaluating this rule",
                        compiled.rule().policy());
                evaluate(ctx, index + 1);
            }
            return;
        }
        RateLimitKey key = ((Resolved) outcome).key();
        RateLimitEdgeRule rule = compiled.rule();
        Future<RateLimitDecision> acquired = rule.cost().isPresent()
                ? compiled.limiter().acquire(key, rule.cost().getAsLong())
                : compiled.limiter().acquire(key);
        acquired.onComplete(result -> {
            if (result.failed()) {
                // Defensive-only: acquire() only fails its future for a cost/capacity mismatch
                // (contracts/rate-limit-runtime.md, "Handle semantics"), which eager policy
                // validation should already have ruled out. Fail closed rather than let an
                // unmapped rate-limit exception cross the pre-authorization boundary.
                ctx.request().resume();
                respondUnavailable(ctx);
                return;
            }
            RateLimitDecision decision = result.result();
            switch (decision.outcome()) {
                case PERMITTED, BACKEND_FAILURE_OPEN, DISABLED -> evaluate(ctx, index + 1);
                case QUOTA_EXCEEDED -> {
                    ctx.request().resume();
                    respondExceeded(ctx, decision);
                }
                case BACKEND_FAILURE_CLOSED -> {
                    ctx.request().resume();
                    respondUnavailable(ctx, decision);
                }
            }
        });
    }

    /**
     * Renders a {@code QUOTA_EXCEEDED} decision by minting a {@link RateLimitExceededException}
     * and resolving it through {@link #exceptionMapperRegistry} (this class's javadoc, "Denial
     * rendering"). Falls back to the fixed built-in {@code 429} body if resolution or rendering
     * fails for any reason — never an unhandled failure.
     */
    private void respondExceeded(RoutingContext ctx, RateLimitDecision decision) {
        try {
            writeResponse(ctx, exceptionMapperRegistry.toResponse(new RateLimitExceededException(decision)));
        } catch (Throwable mapperFailure) {
            log.warn(
                    "Exception mapper resolution/rendering failed for a QUOTA_EXCEEDED edge denial;"
                            + " falling back to the built-in 429 response (cause: {})",
                    mapperFailure.getClass().getName());
            writeExceededFallback(ctx, decision);
        }
    }

    /**
     * Renders a {@code BACKEND_FAILURE_CLOSED} decision by minting a {@link
     * RateLimitUnavailableException} and resolving it through {@link #exceptionMapperRegistry}
     * (this class's javadoc, "Denial rendering"). Falls back to the fixed built-in {@code 503}
     * body if resolution or rendering fails for any reason — never an unhandled failure.
     */
    private void respondUnavailable(RoutingContext ctx, RateLimitDecision decision) {
        try {
            writeResponse(ctx, exceptionMapperRegistry.toResponse(new RateLimitUnavailableException(decision)));
        } catch (Throwable mapperFailure) {
            log.warn(
                    "Exception mapper resolution/rendering failed for a BACKEND_FAILURE_CLOSED edge denial;"
                            + " falling back to the built-in 503 response (cause: {})",
                    mapperFailure.getClass().getName());
            respondUnavailable(ctx);
        }
    }

    private static void writeExceededFallback(RoutingContext ctx, RateLimitDecision decision) {
        long retryAfterSeconds =
                RateLimitHttpMapping.retryAfterSeconds(decision.retryAfter().orElse(Duration.ZERO));
        ctx.response()
                .putHeader(RateLimitHttpMapping.RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds))
                .putHeader(RateLimitHttpMapping.CACHE_CONTROL_HEADER, RateLimitHttpMapping.CACHE_CONTROL_NO_STORE)
                .putHeader(CONTENT_TYPE_HEADER, RateLimitHttpMapping.PROBLEM_JSON)
                .setStatusCode(429)
                .end(EXCEEDED_BODY);
    }

    /**
     * Writes the fixed, generic {@code 503} body directly — used both for the two paths that
     * carry no {@link RateLimitDecision} at all (absent-origin fail-closed, defensive
     * acquire()-future failure; this class's javadoc, "Denial rendering") and as the fallback when
     * mapper resolution/rendering fails for a genuine {@code BACKEND_FAILURE_CLOSED} decision.
     */
    private static void respondUnavailable(RoutingContext ctx) {
        ctx.response()
                .putHeader(RateLimitHttpMapping.CACHE_CONTROL_HEADER, RateLimitHttpMapping.CACHE_CONTROL_NO_STORE)
                .putHeader(CONTENT_TYPE_HEADER, RateLimitHttpMapping.PROBLEM_JSON)
                .setStatusCode(503)
                .end(UNAVAILABLE_BODY);
    }

    /**
     * Writes a resolved JAX-RS {@link Response} onto {@code ctx}'s HTTP response: status, every
     * header (verbatim — {@code Retry-After}/{@code Cache-Control} now come from the resolved
     * mapper, not this middleware), and the entity. Entity handling covers the shapes the
     * framework's own mappers produce (this class's javadoc, "Denial rendering"): {@code null}
     * yields an empty body; a {@link Buffer}/{@link String} entity is written through as-is (a
     * {@link String} without an explicit {@code Content-Type} defaults to {@code text/plain},
     * matching the JAX-RS pipeline's own {@code StringBodyEncoder} default); any other entity is
     * serialized via {@link Json#encode} (defaulting to {@code application/json} when no explicit
     * {@code Content-Type} is set), the same fallback the JAX-RS pipeline's own {@code
     * JsonBodyEncoder} uses outside a per-method resolved body-mapper profile — a profile never
     * applies here since no JAX-RS operation has been dispatched yet.
     */
    private static void writeResponse(RoutingContext ctx, Response response) {
        HttpServerResponse serverResponse = ctx.response();
        serverResponse.setStatusCode(response.getStatus());
        response.getStringHeaders().forEach(serverResponse::putHeader);
        Object entity = response.getEntity();
        if (entity == null) {
            serverResponse.end();
            return;
        }
        if (entity instanceof Buffer buffer) {
            serverResponse.end(buffer);
            return;
        }
        boolean hasContentType = response.getHeaderString(CONTENT_TYPE_HEADER) != null;
        if (entity instanceof String str) {
            if (!hasContentType) {
                serverResponse.putHeader(CONTENT_TYPE_HEADER, "text/plain");
            }
            serverResponse.end(str);
            return;
        }
        if (!hasContentType) {
            serverResponse.putHeader(CONTENT_TYPE_HEADER, "application/json");
        }
        serverResponse.end(Json.encode(entity));
    }

    /**
     * Aggregates an IPv6 {@code clientIp} onto {@code ipv6PrefixBits}; an IPv4 address always keys
     * on the full address regardless of {@code ipv6PrefixBits} (contracts/rest-adapter.md,
     * "Cardinality caution"). {@code clientIp} is guaranteed to be a validated IP literal by {@link
     * RequestOrigin}'s own construction, so {@link InetAddress#getByName} below never triggers a
     * DNS lookup.
     *
     * <p>Package-private (rather than {@code private}) so {@code
     * RateLimitEdgeMiddlewareIpKeyComponentTest} can pin this derivation's golden vectors directly,
     * independent of a full HTTP round trip.
     */
    static String ipKeyComponent(String clientIp, int ipv6PrefixBits) {
        if (!clientIp.contains(":")) {
            return clientIp;
        }
        try {
            byte[] bytes = InetAddress.getByName(clientIp).getAddress();
            if (bytes.length != 16) {
                return clientIp;
            }
            byte[] masked = new byte[16];
            int fullBytes = ipv6PrefixBits / 8;
            int remainderBits = ipv6PrefixBits % 8;
            System.arraycopy(bytes, 0, masked, 0, fullBytes);
            if (remainderBits > 0 && fullBytes < 16) {
                int mask = (0xFF << (8 - remainderBits)) & 0xFF;
                masked[fullBytes] = (byte) (bytes[fullBytes] & mask);
            }
            return InetAddress.getByAddress(masked).getHostAddress() + "/" + ipv6PrefixBits;
        } catch (UnknownHostException e) {
            return clientIp;
        }
    }

    /** Distinct missing-header bucket marker, framed via {@link RateLimitKey}'s enum encoding. */
    private enum MissingHeaderMarker {
        SHARED
    }

    /** One rule compiled against its resolved {@link RateLimiter} handle. */
    private record CompiledRule(RateLimitEdgeRule rule, RateLimiter limiter, RateLimitFailureMode ipFailureMode) {

        /**
         * Derives this rule's key for {@code ctx}, or signals a bypass/origin-absent outcome
         * instead (contracts/rest-adapter.md, "Rule composition semantics").
         */
        KeyOutcome deriveKey(RoutingContext ctx) {
            List<RateLimitEdgeKeyDimension> dimensions = rule.key();
            if (dimensions.size() == 1 && dimensions.get(0) == RateLimitEdgeKeyDimension.GLOBAL) {
                return new Resolved(RateLimitKey.global());
            }
            List<Object> components = new ArrayList<>(dimensions.size() * 2);
            for (RateLimitEdgeKeyDimension dimension : dimensions) {
                switch (dimension) {
                    case IP -> {
                        Object stashed = ctx.get(RequestOrigin.class.getName());
                        if (!(stashed instanceof RequestOrigin origin)) {
                            return OriginAbsent.INSTANCE;
                        }
                        components.add(RateLimitEdgeKeyDimension.IP);
                        components.add(ipKeyComponent(origin.clientIp(), rule.effectiveIpv6PrefixBits()));
                    }
                    case HEADER -> {
                        String headerName = rule.headerName().orElseThrow();
                        MultiMap headers = ctx.request().headers();
                        List<String> values = headers.getAll(headerName);
                        if (values.size() != 1) {
                            if (rule.missingDimension() == MissingDimensionPolicy.BYPASS) {
                                return Bypassed.INSTANCE;
                            }
                            components.add(RateLimitEdgeKeyDimension.HEADER);
                            components.add(MissingHeaderMarker.SHARED);
                        } else {
                            components.add(RateLimitEdgeKeyDimension.HEADER);
                            components.add(values.get(0));
                        }
                    }
                    case GLOBAL ->
                        throw new IllegalStateException(
                                "unreachable: GLOBAL is only valid alone, enforced by RateLimitEdgeRule's constructor");
                }
            }
            return new Resolved(RateLimitKey.of(
                    components.get(0), components.subList(1, components.size()).toArray()));
        }
    }

    /** Outcome of {@link CompiledRule#deriveKey}. */
    private sealed interface KeyOutcome permits Resolved, Bypassed, OriginAbsent {}

    private record Resolved(RateLimitKey key) implements KeyOutcome {}

    private record Bypassed() implements KeyOutcome {
        static final Bypassed INSTANCE = new Bypassed();
    }

    private record OriginAbsent() implements KeyOutcome {
        static final OriginAbsent INSTANCE = new OriginAbsent();
    }
}
