// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.async.Combinators;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared error mapping pipeline used by both {@link ResourceMethodInvoker} (operation errors)
 * and the router-level failure handler (pre-dispatch errors from middlewares and validation).
 *
 * <p>Pipeline: fire {@link RequestInterceptor#onError} sync observers with the original cause →
 * {@link ErrorInterceptor#beforeMapping} async chain → {@link RestExceptionMapper} →
 * {@link ExceptionMapperRegistry} → {@link ProblemDetail} instance enrichment →
 * {@link ErrorInterceptor#afterMapping} async chain → returns {@link Future}{@code <Response>}
 * to the caller for unified dispatch via {@link ResponsePipeline#sendResponse}.
 *
 * <p>The original {@link Throwable} (before any mapping) is stored in
 * {@link RoutingContext#data()} under {@link RequestInterceptor#ORIGINAL_ERROR_KEY} for the
 * full duration of error processing, enabling audit and diagnostic interceptors to access the
 * root cause even after mapping.
 */
@Slf4j
public class ErrorPipeline {

    /**
     * Lower-cased names of the headers dropped when this pipeline replaces a response entity — the
     * ones whose value describes the octets of the superseded body rather than the response itself.
     * Matched case-insensitively; see {@link #rebuildWithHeaders} for why each must not survive.
     *
     * <p>Headers that describe what the body <em>means</em> ({@code Content-Type},
     * {@code Content-Language}) are not in this set: when the replacement stays a
     * {@link ProblemDetail} under {@code application/problem+json} they still hold. When the
     * override fail-closes a non-{@code ProblemDetail} entity into a fresh problem document, those
     * meaning headers are dropped separately so the superseded media type cannot ride along.
     */
    private static final Set<String> ENTITY_DESCRIBING_HEADERS = Set.of(
            "content-length",
            "content-encoding",
            "content-range",
            "content-md5",
            "etag",
            "digest",
            "content-digest",
            "repr-digest");

    /**
     * Lower-cased names of headers that name the body's media type / language. Dropped only when the
     * override replaces a non-{@link ProblemDetail} entity with a fresh problem document, so the
     * winning response cannot inherit a superseded {@code Content-Type} such as {@code text/plain}.
     */
    private static final Set<String> MEDIA_TYPE_HEADERS = Set.of("content-type", "content-language");

    private final List<ErrorInterceptor> errorInterceptors;
    private final List<RequestInterceptor> requestInterceptors;
    private final RestExceptionMapper restExceptionMapper;
    private final ExceptionMapperRegistry exceptionMapperRegistry;

    /**
     * Creates a new error pipeline with the given components.
     *
     * @param errorInterceptors       sorted list of error interceptors (async before/after mapping)
     * @param requestInterceptors     sorted list of request interceptors (for {@code onError} sync observer)
     * @param restExceptionMapper     REST-layer exception translator (Throwable to Throwable pre-translation)
     * @param exceptionMapperRegistry JAX-RS exception-to-Response mapper
     */
    public ErrorPipeline(
            List<ErrorInterceptor> errorInterceptors,
            List<RequestInterceptor> requestInterceptors,
            RestExceptionMapper restExceptionMapper,
            ExceptionMapperRegistry exceptionMapperRegistry) {
        this.errorInterceptors = errorInterceptors != null ? errorInterceptors : List.of();
        this.requestInterceptors = requestInterceptors != null ? requestInterceptors : List.of();
        this.restExceptionMapper = restExceptionMapper;
        this.exceptionMapperRegistry = exceptionMapperRegistry;
    }

    /**
     * Maps a throwable through the full error pipeline and returns the mapped {@link Response}.
     *
     * <p>The caller is responsible for sending the response (via
     * {@link ResponsePipeline#sendResponse}) so that the unified
     * {@link RequestInterceptor#transformResponse} and
     * {@link RequestInterceptor#afterResponse} hooks see all responses.
     *
     * <p>Pipeline steps:
     * <ol>
     *   <li>Fire {@link RequestInterceptor#onError} sync observers with the original cause</li>
     *   <li>Store original cause in {@link RoutingContext#data()} under
     *       {@link RequestInterceptor#ORIGINAL_ERROR_KEY}</li>
     *   <li>{@link ErrorInterceptor#beforeMapping} async chain (transforms the throwable)</li>
     *   <li>{@link RestExceptionMapper} (Throwable to Throwable pre-translation)</li>
     *   <li>{@link ExceptionMapperRegistry} (Throwable to {@link Response})</li>
     *   <li>Instance enrichment — populates {@link ProblemDetail#instance()} from the
     *       request path if the response entity is a {@link ProblemDetail} and
     *       {@code instance} is not already set</li>
     *   <li>{@link ErrorInterceptor#afterMapping} async chain (transforms the response)</li>
     *   <li>Framework catch-all logging, once, using the status of the response leaving the chain</li>
     * </ol>
     *
     * <p>The Vert.x failure-status hint ({@code VertxFailureStatus.KEY}) and the failure-cycle
     * observation keys ({@code VertxFailureStatus.OBSERVED_*}) are <em>consumed</em> when the mapping
     * step begins: read and removed from {@link RoutingContext#data()} before
     * {@link RestExceptionMapper#translate} runs, so a throwing translator cannot leave them behind.
     * Removal does not depend on which mapper produces the response or whether the fallback applies
     * the stored status. The hint describes the failure being mapped; leaving it — or the observation
     * pair that produced it — would let a later mapping on the same context (a reroute, for instance)
     * be steered by a status that no longer describes anything. Consumption happens after the
     * {@link ErrorInterceptor#beforeMapping} chain, so an error interceptor still observes the hint
     * that produced the failure it is inspecting.
     *
     * @param ctx   the current routing context
     * @param cause the throwable to map into an error response
     * @return a {@link Future} that completes with the mapped {@link Response}; callers should
     *         handle failure with {@link ResponsePipeline#sendFallback500}
     */
    public Future<Response> mapToResponse(RoutingContext ctx, Throwable cause) {
        // 1. Fire onError sync observers with the original cause (before any mapping).
        Combinators.forEachSwallowSync(
                requestInterceptors, interceptor -> interceptor.onError(ctx, cause), (interceptor, e) -> {
                    // intentional: ErrorPipeline.onError emitted no log before this refactor; preserve the silent
                    // swallow — do not add one
                });

        // 2. Store original error for downstream access
        ctx.data().put(RequestInterceptor.ORIGINAL_ERROR_KEY, cause);

        // FR-JSON-058A: mark this as an error response so the wire-writing path applies the vertx
        // fail-open to error-body serialization only. mapToResponse is invoked exclusively on error
        // paths (never for a success entity), so this unambiguously tags error/ProblemDetail bodies.
        ctx.data().put(ResponsePipeline.KEY_ERROR_RESPONSE, Boolean.TRUE);

        // 3. Async error mapping pipeline — returns Response to caller.
        // foldSequential over errorInterceptors directly; the ordinal is computed via indexOf
        // inside the recover lambda (failure path only) to preserve the interceptor[{}] WARN
        // message verbatim.
        return Combinators.foldSequential(
                        errorInterceptors, cause, (interceptor, t) -> Future.<Throwable>succeededFuture()
                                .compose(v -> interceptor.beforeMapping(ctx, t))
                                .recover(mf -> {
                                    log.warn(
                                            "ErrorInterceptor.beforeMapping failed in interceptor[{}] — passing throwable unchanged",
                                            errorInterceptors.indexOf(interceptor),
                                            mf);
                                    return Future.succeededFuture(t);
                                }))
                .map(mappedCause -> {
                    // Consume the Vert.x failure-status hint and the failure-cycle observation at the
                    // start of the mapping step, before translate: both describe exactly the failure
                    // being mapped, so they must be gone whichever mapper produces the response —
                    // including when a specific application mapper outranks the hint and the fallback
                    // never runs, and including when translate throws. Leaving either behind would let
                    // it steer a mapping raised later on the same context (reroute() clears failure and
                    // statusCode, but not data()). Consuming here rather than at method entry keeps the
                    // hint observable to the beforeMapping chain, which runs first.
                    Object vertxFailureStatus = ctx.data().remove(VertxFailureStatus.KEY);
                    VertxFailureStatus.consumeObservation(ctx);
                    Throwable translated = restExceptionMapper.translate(mappedCause);
                    boolean frameworkCatchAll =
                            exceptionMapperRegistry.handledByFrameworkCatchAll(translated.getClass());
                    Response response = exceptionMapperRegistry.toResponse(translated);
                    if (!exceptionMapperRegistry.hasSpecificMapper(translated.getClass())) {
                        response = applyVertxStatusCodeFallback(response, vertxFailureStatus, translated);
                    }
                    return new MappingOutcome(enrichProblemDetail(ctx, response), translated, frameworkCatchAll);
                })
                .compose(outcome -> Combinators.foldSequential(
                                errorInterceptors,
                                outcome.response(),
                                (interceptor, r) -> Future.<Response>succeededFuture()
                                        .compose(v -> interceptor.afterMapping(ctx, r))
                                        .recover(mf -> {
                                            log.warn(
                                                    "ErrorInterceptor.afterMapping failed in interceptor[{}] — passing response unchanged",
                                                    errorInterceptors.indexOf(interceptor),
                                                    mf);
                                            return Future.succeededFuture(r);
                                        }))
                        .map(finalResponse -> {
                            // The afterMapping chain may replace the response and its status, so the
                            // level is decided once, here, from the response that will actually be sent.
                            if (outcome.frameworkCatchAll()) {
                                int status = finalResponse != null ? finalResponse.getStatus() : 500;
                                logUnhandledAfterMapping(outcome.translated(), status);
                            }
                            return finalResponse;
                        }));
    }

    /**
     * Per-invocation result of the mapping step carried into the {@code afterMapping} chain.
     *
     * @param response         the mapped (fallback-applied, enriched) response entering the chain
     * @param translated       the cause after {@link RestExceptionMapper#translate}
     * @param frameworkCatchAll whether the framework {@code Throwable} catch-all produced the response
     */
    private record MappingOutcome(Response response, Throwable translated, boolean frameworkCatchAll) {}

    // --- Unhandled-exception logging ---

    /**
     * Logs after the framework {@code Throwable} catch-all produced a response and the Vert.x
     * status-code fallback and the {@link ErrorInterceptor#afterMapping} chain have settled. A final 4xx is a deliberate client rejection (for
     * example a multipart decoder limit) and must not look like an unhandled server fault; genuine
     * 5xx outcomes keep the ERROR stack.
     *
     * @param cause  the throwable the catch-all mapped
     * @param status the HTTP status of the response leaving the {@code afterMapping} chain
     */
    private static void logUnhandledAfterMapping(Throwable cause, int status) {
        if (status >= 400 && status < 500) {
            log.debug("Unhandled exception mapped to client error {}", status, cause);
            return;
        }
        log.error("Unhandled exception", cause);
    }

    // --- ProblemDetail enrichment ---

    /**
     * Enriches the response entity if it is a {@link ProblemDetail} without an instance set.
     * The instance is populated from the request path. All response headers and media type
     * are preserved.
     *
     * @param ctx      the current routing context (used to read the request path)
     * @param response the response produced by the exception mapper
     * @return the enriched response, or the original response unchanged
     */
    private static Response enrichProblemDetail(RoutingContext ctx, Response response) {
        Object entity = response.getEntity();
        if (entity instanceof ProblemDetail pd && pd.instance() == null) {
            ProblemDetail enriched =
                    pd.toBuilder().instance(ctx.request().path()).build();
            Response.ResponseBuilder rb = Response.status(response.getStatus()).entity(enriched);
            return rebuildWithHeaders(response, rb, true);
        }
        return response;
    }

    /**
     * Applies the Vert.x status code fallback: when the routing context carries an authoritative
     * Vert.x failure status that differs from the status the exception mapper produced, the Vert.x
     * status wins. It overrides any framework-default mapping — not only the {@link Throwable}
     * catch-all's 500 — because the status Vert.x set is a deliberate decision about this request,
     * whereas a framework default is a decision about the exception's type alone. This preserves HTTP
     * semantics (e.g. 401, 403) for unwrapped {@code HttpException} causes and for a 4xx the Vert.x
     * layer set alongside an arbitrary cause.
     *
     * <p>The fallback does not activate when:
     * <ul>
     *   <li>No status was recorded under {@link VertxFailureStatus#KEY} (the Vert.x layer decided none)</li>
     *   <li>The mapper produced 403 and the stored status is 401 — a hint never downgrades an
     *       authorization outcome into an authentication challenge</li>
     * </ul>
     *
     * <p>When the recorded status <em>agrees</em> with the mapped one, the status is left alone but a
     * {@link ProblemDetail} {@code detail} synthesized from an arbitrary exception message is still
     * dropped — see {@link #sanitizeEqualStatusDetail}. That closes the equal-status disclosure where
     * {@code ctx.fail(401, new UnauthorizedException(...))} (or any other semantic type whose own
     * mapping already equals the Vert.x status) would otherwise publish the cause message verbatim.
     * A {@link WebApplicationException} whose {@link Response} already carries an entity is treated as
     * deliberately authored client output and keeps its body (the framework's own 415 producers use this
     * shape).
     *
     * <p>The 403←401 guard keys on the <em>mapped status</em>, never on the exception type that produced
     * it: any 403 survives a 401 hint, whether it came from the framework's own
     * {@code ForbiddenException} mapping or from an application {@code ExceptionMapper<Throwable>}
     * catch-all that chose 403 for its own denial type. Keying on a list of authorization exception
     * types instead was considered and rejected — the list would drift the moment a new denial type
     * appears, and it would invert the guard for the case that most deserves it (an application mapping
     * its own {@code TenantMismatchException} to 403 would be overridden back to 401).
     *
     * <p>The stored status is consumed by the caller before this method runs — see
     * {@link #mapToResponse} — so it is passed in rather than read from the context here.
     *
     * <p>When it does override, the body is rebuilt for the overriding status rather than re-labelled.
     * A {@link ProblemDetail} is reconstructed: the title is recomputed and the detail is dropped. A
     * detail was written for the status being superseded — and on the {@code ctx.fail(4xx, cause)} path
     * it is an arbitrary application exception's message — so carrying it into the new status would both
     * contradict the title and publish a message the framework never intended for the client. The same
     * reasoning applies to everything else the superseded body carried: typed subclass fields (such as
     * {@link dev.vertique.rest.core.ValidationProblemDetail#errors()}) and RFC 9457 extension members
     * are dropped with it, so only {@code instance} — request-scoped and status-independent — survives.
     * A non-{@code ProblemDetail} entity is fail-closed the same way: replaced with a fresh
     * {@link ProblemDetail} for the winning status and {@code application/problem+json}, rather than
     * shipping the superseded body under a new status line.
     *
     * <p>This method is only called when no specific (user-contributed) {@code ExceptionMapper}
     * matched the unwrapped cause — the caller checks
     * {@link ExceptionMapperRegistry#hasSpecificMapper} before invoking.
     *
     * @param response     the response produced by the exception mapper
     * @param storedStatus the status the Vert.x layer recorded for this failure, already consumed from
     *                     the routing context by {@link #mapToResponse}; {@code null} (or any
     *                     non-{@link Integer}) when no status was recorded
     * @param cause        the throwable that produced {@code response}, used on the equal-status arm to
     *                     tell an authored {@link WebApplicationException} entity from a synthesized
     *                     {@code detail}
     * @return the response with the status overridden and its problem body rebuilt, or the original
     *         response with equal-status detail sanitization applied
     */
    private static Response applyVertxStatusCodeFallback(Response response, Object storedStatus, Throwable cause) {
        if (!(storedStatus instanceof Integer vertxStatus)) {
            return response;
        }
        if (response.getStatus() == vertxStatus) {
            return sanitizeEqualStatusDetail(response, cause);
        }
        if (response.getStatus() == 403 && vertxStatus == 401) {
            // Directional guard, deliberately narrow. 403 is an authorization decision the cause itself
            // carried; answering 401 instead tells the client "authenticate and retry", which is false for
            // a denial no fresh credential can lift — it invites a token-refresh loop that cannot succeed,
            // and it hides the denial from access logs and SIEM rules that count 403s to spot probing.
            // Keyed on the mapped status, not on the exception type: an application ExceptionMapper<Throwable>
            // catch-all answering 403 is protected too, because "never turn a 403 into a 401" is a property
            // of the status, not of which mapper decided it. See this method's javadoc for the rejected
            // type-list alternative. This is the only guarded status pair — every other mapped status is
            // superseded normally.
            return response;
        }
        // Override: the mapper's status is superseded by the status Vert.x decided.
        Object entity = response.getEntity();
        if (entity instanceof ProblemDetail pd) {
            // Build a fresh body rather than deriving one from the superseded problem: pd may be a
            // ProblemDetail subclass (e.g. ValidationProblemDetail) or carry RFC 9457 extension
            // members, and toBuilder() would copy those fields — which describe the status being
            // superseded — straight into the overriding status, defeating the cleared detail. Only
            // instance carries over; it is request-scoped and status-independent.
            Response.ResponseBuilder rb =
                    Response.status(vertxStatus).entity(ProblemDetail.of(vertxStatus, null, pd.instance()));
            return rebuildWithHeaders(response, rb, true, false);
        }
        // Fail closed: a non-ProblemDetail entity (or a missing one) describes the superseded status —
        // including via its Content-Type. Replace it with a fresh problem document for the winning
        // status rather than re-labelling the old body. instance is filled by enrichProblemDetail.
        Response.ResponseBuilder rb = Response.status(vertxStatus)
                .entity(ProblemDetail.of(vertxStatus, null))
                .type("application/problem+json");
        return rebuildWithHeaders(response, rb, true, true);
    }

    /**
     * Drops a synthesized {@link ProblemDetail} {@code detail} when the Vert.x failure status already
     * equals the mapped status.
     *
     * <p>On {@code ctx.fail(4xx, cause)} the cause is often an arbitrary application exception — a JWT
     * claims validator's {@code UnauthorizedException}, for example. {@code DefaultExceptionMapper}
     * still publishes {@code ex.getMessage()} as {@code detail} for those semantic types, and the
     * equal-status arm used to return that body untouched, so whether the message reached the client
     * depended on whether the exception's own mapping happened to equal the Vert.x status. Clearing
     * the detail here makes the equal-status path match the override path's disclosure rule.
     *
     * <p>A {@link WebApplicationException} whose {@link Response} already carries an entity is left
     * alone: that entity is deliberate client output (the framework's {@code @Consumes} / content-type
     * 415 producers author a {@link ProblemDetail} on the JAX-RS response before failing the context).
     * Hint presence alone is not a foreignness discriminator; an authored entity is.
     *
     * @param response the equal-status response from the exception mapper
     * @param cause    the throwable that produced {@code response}
     * @return {@code response} with {@code detail} cleared, or unchanged when there is nothing to
     *         sanitize
     */
    private static Response sanitizeEqualStatusDetail(Response response, Throwable cause) {
        if (cause instanceof WebApplicationException wae && wae.getResponse().getEntity() != null) {
            return response;
        }
        Object entity = response.getEntity();
        if (!(entity instanceof ProblemDetail pd) || pd.detail() == null) {
            return response;
        }
        // Fresh ProblemDetail — same rebuild rule as the override arm — so a ValidationProblemDetail
        // (or any extension members) that rode along with a synthesized detail cannot survive either.
        Response.ResponseBuilder rb = Response.status(response.getStatus())
                .entity(ProblemDetail.of(response.getStatus(), null, pd.instance()));
        return rebuildWithHeaders(response, rb, true);
    }

    /**
     * Copies the source response's headers into the builder and returns the built response.
     *
     * <p>The headers dropped when the entity was replaced are exactly those that describe the
     * <em>octets</em> of the body the superseded response carried, listed in
     * {@link #ENTITY_DESCRIBING_HEADERS}. Nothing downstream recomputes them —
     * {@code ResponsePipeline.applyToWire} copies every JAX-RS header to the wire and
     * {@code DefaultResponseSerializer} only overwrites the length when the encoder supplies one,
     * which the JSON encoder does not — so each would describe a body that no longer exists: a
     * {@code Content-Length} declaring a length for other bytes is the framing violation
     * {@link ResponsePipeline} already detects, a {@code Content-Encoding} makes the generated JSON
     * undecodable, and an {@code ETag} or digest identifies a representation the client never
     * receives.
     *
     * <p>When the replacement stays a {@link ProblemDetail} under the same media type,
     * {@code Content-Type} and {@code Content-Language} still hold and are preserved. When the
     * override fail-closes a non-{@code ProblemDetail} entity, {@code mediaTypeReplaced} drops those
     * meaning headers so the builder's {@code application/problem+json} is not overwritten by the
     * superseded type. {@code WWW-Authenticate}, {@code Retry-After} and {@code Allow} describe the
     * response, not its body, and remain correct — indeed a {@code WWW-Authenticate} the mapper
     * authored is exactly what a status overridden <em>to</em> 401 needs.
     *
     * @param source            the original response whose headers should be preserved
     * @param builder           the response builder (with status and entity already set)
     * @param entityReplaced    whether the builder carries a different entity than {@code source} did
     * @param mediaTypeReplaced whether the builder's media type replaces the source's (fail-closed
     *                          non-{@code ProblemDetail} override)
     * @return the built response with the source's headers
     */
    private static Response rebuildWithHeaders(
            Response source, Response.ResponseBuilder builder, boolean entityReplaced, boolean mediaTypeReplaced) {
        source.getStringHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (entityReplaced && ENTITY_DESCRIBING_HEADERS.contains(lower)) {
                return;
            }
            if (mediaTypeReplaced && MEDIA_TYPE_HEADERS.contains(lower)) {
                return;
            }
            for (String value : values) {
                builder.header(name, value);
            }
        });
        return builder.build();
    }

    /**
     * Copies the source response's headers into the builder when the entity was replaced but the
     * media type still describes the rebuilt body (ProblemDetail enrichment / ProblemDetail override).
     *
     * @param source         the original response whose headers should be preserved
     * @param builder        the response builder (with status and entity already set)
     * @param entityReplaced whether the builder carries a different entity than {@code source} did
     * @return the built response with the source's headers
     */
    private static Response rebuildWithHeaders(
            Response source, Response.ResponseBuilder builder, boolean entityReplaced) {
        return rebuildWithHeaders(source, builder, entityReplaced, false);
    }
}
