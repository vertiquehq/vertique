// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.async.Combinators;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.List;
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
     * </ol>
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
                .map(mappedCause -> restExceptionMapper.translate(mappedCause))
                .map(translated -> {
                    Response response = exceptionMapperRegistry.toResponse(translated);
                    if (!exceptionMapperRegistry.hasSpecificMapper(translated.getClass())) {
                        response = applyVertxStatusCodeFallback(ctx, response);
                    }
                    return enrichProblemDetail(ctx, response);
                })
                .compose(response -> Combinators.foldSequential(
                        errorInterceptors, response, (interceptor, r) -> Future.<Response>succeededFuture()
                                .compose(v -> interceptor.afterMapping(ctx, r))
                                .recover(mf -> {
                                    log.warn(
                                            "ErrorInterceptor.afterMapping failed in interceptor[{}] — passing response unchanged",
                                            errorInterceptors.indexOf(interceptor),
                                            mf);
                                    return Future.succeededFuture(r);
                                })));
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
            return rebuildWithHeaders(response, rb);
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
     *   <li>No {@link VertxFailureStatus#KEY} is present (the Vert.x layer decided no status)</li>
     *   <li>The response already has the correct status (matches the stored code)</li>
     *   <li>The mapper produced 403 and the stored status is 401 — a hint never downgrades an
     *       authorization outcome into an authentication challenge</li>
     * </ul>
     *
     * <p>The stored status is <em>consumed</em> on read: it is removed from {@link RoutingContext#data()}
     * so it cannot steer a later mapping (a reroute raised from within the async error chain, for
     * instance) after the failure that produced it has been answered.
     *
     * <p>When it does override, the {@link ProblemDetail} body is rebuilt from the overriding status
     * rather than patched: the title is recomputed and the detail is dropped. A detail was written
     * for the status being superseded — and on the {@code ctx.fail(4xx, cause)} path it is an arbitrary
     * application exception's message — so carrying it into the new status would both contradict the
     * title and publish a message the framework never intended for the client. The same reasoning
     * applies to everything else the superseded body carried: typed subclass fields (such as
     * {@link dev.vertique.rest.core.ValidationProblemDetail#errors()}) and RFC 9457 extension members
     * are dropped with it, so only {@code instance} — request-scoped and status-independent — survives.
     * A body the mapper authored <em>for the status that survives</em> is untouched, which is what the
     * equal-status early return above protects.
     *
     * <p>This method is only called when no specific (user-contributed) {@code ExceptionMapper}
     * matched the unwrapped cause — the caller checks
     * {@link ExceptionMapperRegistry#hasSpecificMapper} before invoking.
     *
     * @param ctx      the routing context containing the Vert.x status code (if any)
     * @param response the response produced by the exception mapper
     * @return the response with the status overridden and its problem body rebuilt, or the original
     *         response unchanged
     */
    private static Response applyVertxStatusCodeFallback(RoutingContext ctx, Response response) {
        // Consume rather than peek: the hint describes exactly this failure, and leaving it behind would
        // let it steer a mapping raised later on the same context (reroute() clears failure and
        // statusCode, but not data()).
        Object storedCode = ctx.data().remove(VertxFailureStatus.KEY);
        if (!(storedCode instanceof Integer vertxStatus)) {
            return response;
        }
        if (response.getStatus() == vertxStatus) {
            return response;
        }
        if (response.getStatus() == 403 && vertxStatus == 401) {
            // Directional guard, deliberately narrow. 403 is an authorization decision the cause itself
            // carried; answering 401 instead tells the client "authenticate and retry", which is false for
            // a denial no fresh credential can lift — it invites a token-refresh loop that cannot succeed,
            // and it hides the denial from access logs and SIEM rules that count 403s to spot probing.
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
            entity = ProblemDetail.of(vertxStatus, null, pd.instance());
        }
        Response.ResponseBuilder rb = Response.status(vertxStatus).entity(entity);
        return rebuildWithHeaders(response, rb);
    }

    /**
     * Copies all headers from the source response into the builder and returns the built response.
     *
     * @param source  the original response whose headers should be preserved
     * @param builder the response builder (with status and entity already set)
     * @return the built response with all original headers
     */
    private static Response rebuildWithHeaders(Response source, Response.ResponseBuilder builder) {
        source.getStringHeaders().forEach((name, values) -> {
            for (String value : values) {
                builder.header(name, value);
            }
        });
        return builder.build();
    }
}
