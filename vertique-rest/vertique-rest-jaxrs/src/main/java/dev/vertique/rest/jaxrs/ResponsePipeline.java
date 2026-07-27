// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.async.Combinators;
import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.request.AcceptNegotiator;
import dev.vertique.rest.core.request.RequestPreconditions;
import dev.vertique.rest.core.response.ResponseProducer;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.core.response.ResponseSerializer;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Internal response pipeline that translates a JAX-RS method result into an HTTP wire response.
 *
 * <p>This class is package-private. Extension points remain: {@link ResponseProducerBinding},
 * {@link jakarta.ws.rs.ext.ExceptionMapper}, {@link dev.vertique.rest.core.response.ResponseBodyEncoder},
 * and interceptors.
 *
 * <p>The unified pipeline:
 * <ol>
 *   <li>{@link #produce(RoutingContext, Object)} — produce a {@link Response} from the method
 *       result via a registered {@link ResponseProducer}, evaluate conditional request headers,
 *       strip HEAD entity.</li>
 *   <li>{@link #sendResponse(RoutingContext, Response)} — chain {@link RequestInterceptor#transformResponse}
 *       hooks, hand the response off to the wire, fire {@link RequestInterceptor#afterResponse} sync
 *       observers exactly once with the outcome written (or, on a serialization failure, the synthetic
 *       500 via {@code sendFallback500}), then observe the wire-completion future and record any
 *       post-handoff failure under {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE}.</li>
 *   <li>{@link #handle(RoutingContext, Object)} — convenience: {@code produce()} then
 *       {@code sendResponse()}; re-throws any exception from {@code produce()} so callers can
 *       route to the error pipeline.</li>
 * </ol>
 *
 * <p>Both success and error responses flow through {@code sendResponse()}, so
 * {@link RequestInterceptor#transformResponse} and {@link RequestInterceptor#afterResponse} see
 * <em>all</em> terminal outcomes regardless of outcome — including the bare-metal fallback 500
 * written by {@link #sendFallback500(RoutingContext, Throwable)} when the transform chain itself fails.
 *
 * <p>Default producers:
 * <ul>
 *   <li>{@code Void} / {@code null} — 204 No Content</li>
 *   <li>{@link Response} — passthrough (the Response itself is the result)</li>
 *   <li>Any object — wrapped in a 200 OK Response with JSON content type (fallback)</li>
 * </ul>
 *
 * <p>User-contributed {@link ResponseProducerBinding} instances from the Dagger
 * {@code Set<ResponseProducerBinding<?>>} multibinding override defaults for the same type.
 */
@Slf4j
class ResponsePipeline {

    /** Minimal RFC 9457 ProblemDetail JSON for the bare-metal 500 fallback. */
    private static final String FALLBACK_500_BODY =
            "{\"type\":\"about:blank\",\"status\":500,\"title\":\"Internal Server Error\"}";

    /**
     * Routing-context data key marking a response as an <em>error</em> / {@code ProblemDetail} response.
     * Set by {@link ErrorPipeline#mapToResponse} for every error it maps, so the wire-writing path can
     * apply the {@code FR-JSON-058A} vertx fail-open to error-body serialization only (a success-entity
     * serialization failure still propagates as a 500). The value is {@link Boolean#TRUE}; absence means
     * the response is a success entity.
     */
    static final String KEY_ERROR_RESPONSE = "vertique.rest.jaxrs.errorResponse";

    private final Map<Class<?>, ResponseProducer<?>> registry = new ConcurrentHashMap<>();
    private final List<RequestInterceptor> hooks;
    private final ResponseSerializer serializer;

    /**
     * Creates a pipeline with framework defaults, user-contributed bindings,
     * request interceptors, and a serializer.
     *
     * @param bindings   the set of user-contributed response producer bindings
     * @param hooks      the request interceptors for {@code transformResponse} and {@code afterResponse}
     *                   (already sorted by priority)
     * @param serializer the response serializer for writing the response body to the wire
     */
    ResponsePipeline(
            Set<ResponseProducerBinding<?>> bindings, List<RequestInterceptor> hooks, ResponseSerializer serializer) {
        this.hooks = hooks;
        this.serializer = serializer;

        // Framework defaults
        registry.put(Void.class, (ctx, result) -> Response.noContent().build());
        registry.put(Response.class, (ctx, response) -> (Response) response);

        // User bindings override defaults
        for (ResponseProducerBinding<?> binding : bindings) {
            registry.put(binding.type(), binding.producer());
        }
    }

    // --- Produce ---

    /**
     * Produces a JAX-RS {@link Response} from the method result without sending it to the wire.
     *
     * <p>Evaluates conditional request headers ({@code If-None-Match}, {@code If-Match},
     * {@code If-Modified-Since}, {@code If-Unmodified-Since}) against the response's
     * {@code ETag} and {@code Last-Modified} headers per RFC 9110 §13.2.2 — but only when the
     * produced response is 2xx. A non-2xx response (e.g. a {@code 302} redirect or a mapped error)
     * is not a "selected representation" per RFC 9110 §13.2.2, so evaluating preconditions against
     * it would be meaningless at best (no ETag to compare) and actively wrong at worst (an
     * unconditional {@code If-Match} failure rewriting an unrelated redirect into a spurious
     * {@code 412}). Strips the entity from {@code HEAD} responses that passed conditional
     * evaluation.
     *
     * <p>For Accept header negotiation (406 Not Acceptable), the {@code Vary: Accept} header
     * is added to the wire response before returning the 406 {@link Response}.
     *
     * @param ctx    the current routing context
     * @param result the method result, or {@code null} for 204 No Content
     * @return the produced {@link Response}; never {@code null}
     */
    Response produce(RoutingContext ctx, Object result) {
        // 1. Produce
        Response response;
        if (result == null) {
            @SuppressWarnings("unchecked")
            ResponseProducer<Void> voidProducer = (ResponseProducer<Void>) findProducer(Void.class);
            response = voidProducer != null
                    ? voidProducer.produce(ctx, null)
                    : Response.noContent().build();
        } else {
            @SuppressWarnings("unchecked")
            ResponseProducer<Object> producer = (ResponseProducer<Object>) findProducer(result.getClass());
            if (producer != null) {
                response = producer.produce(ctx, result);
            } else {
                // Fallback: negotiate Accept header against @Produces, or default to JSON
                @SuppressWarnings("unchecked")
                List<String> produces = (List<String>) ctx.get(ResourceMethodInvoker.CTX_KEY_PRODUCES);
                List<String> candidates =
                        (produces != null && !produces.isEmpty()) ? produces : List.of("application/json");

                String accept = ctx.request().getHeader("Accept");
                String contentType;
                if (accept != null && !accept.isBlank()) {
                    contentType = AcceptNegotiator.negotiate(accept, candidates);
                    // Vary: Accept whenever negotiation runs (RFC 9110 §12.5.1)
                    ctx.response().headers().add("Vary", "Accept");
                    if (contentType == null) {
                        // 406 Not Acceptable — no match between Accept and @Produces
                        return Response.status(406)
                                .entity(ProblemDetail.of(406, "None of the acceptable media types are supported"))
                                .type("application/problem+json")
                                .build();
                    }
                } else {
                    contentType = candidates.get(0);
                }

                response = Response.ok(result).type(contentType).build();
            }
        }

        // 2. Evaluate conditional request headers (RFC 9110 §13.2.2) — only for a 2xx response that
        // actually carries a validator (ETag or Last-Modified). Two guards, both required:
        //   - A non-2xx response (redirect, client/server error) is not a "selected representation",
        //     so If-Match/If-None-Match must not be evaluated against it — most notably a 3xx
        //     redirect carrying no ETag, which would otherwise fail an If-Match unconditionally.
        //   - A 2xx response with NO validator has not opted into conditional handling; evaluating a
        //     client's If-Match against an absent ETag would spuriously yield 412. A resource
        //     participates in conditional requests precisely by exposing a validator, so a
        //     validator-less bodyless 2xx response is left untouched.
        RequestPreconditions preconditions = RequestPreconditions.from(ctx);
        Response conditional = null;
        int status = response.getStatus();
        boolean carriesValidator = response.getEntityTag() != null || response.getLastModified() != null;
        if (status >= 200 && status < 300 && carriesValidator) {
            conditional = preconditions.evaluate(response);
            if (conditional != null) {
                response = conditional;
            }
        }

        // 3. HEAD with no conditional match → strip entity, keep status + headers
        if (preconditions.isHead() && conditional == null && response.getEntity() != null) {
            Response original = response;
            Response.ResponseBuilder stripped = Response.status(original.getStatus());
            MultivaluedMapCopier.copyHeaders(original, stripped);
            response = stripped.build();
        }

        return response;
    }

    // --- Send ---

    /**
     * Unified send pipeline: chains {@link RequestInterceptor#transformResponse} hooks, hands the
     * response off to the wire, fires {@link RequestInterceptor#afterResponse} sync observers, and
     * then observes the wire-completion future returned by the handoff.
     *
     * <p>This method handles <em>both</em> success and error responses. When a
     * {@code transformResponse} chain fails, or a success-entity write throws synchronously,
     * {@link #sendFallback500(RoutingContext, Throwable)} is called as a last-resort fallback —
     * which itself fires {@code afterResponse} hooks with a synthetic 500 {@link Response}.
     *
     * <p><strong>{@code afterResponse} fires exactly once per request</strong>, at <em>wire
     * handoff</em>, reflecting the terminal outcome that was written to the wire:
     * <ul>
     *   <li>normal success — {@code afterResponse(finalResponse)} once, <em>after</em>
     *       {@link #applyToWire(RoutingContext, Response)} initiated the write;</li>
     *   <li>success-entity serialization throw, or transform-chain failure —
     *       {@code afterResponse(synthetic-500)} once via {@code sendFallback500} (the first and only
     *       {@code afterResponse} on the failure path).</li>
     * </ul>
     * "Wire handoff" means the write was <em>initiated</em>: for a streamed body the bytes may still
     * be in flight when the hooks run. Wire completion is reported separately, through the
     * completion event (see {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE}) — never by
     * delaying or re-firing this hook, because observers must see the outcome while the routing
     * context and the tracing span are still active. Because {@code afterResponse} is an
     * observe-only hook (FR-CORE-001.3 — it does not mutate the response), firing it at handoff is
     * safe and lets observers report the outcome the client was sent.
     *
     * <p><strong>Post-handoff wire failures</strong> are observed asynchronously: the completion
     * future returned by {@code applyToWire} may settle on any thread (serializer SPI contract), so
     * the request {@link Context} is captured before the handoff and the failure handling is
     * redispatched onto it. Handling is deliberately minimal — record the marker, log the cause
     * class, end the response if it is not ended — because the client already received the status
     * line: no bare 500 is written, no hook re-fires, and {@code sendFallback500} is not re-entered.
     *
     * @param ctx      the current routing context
     * @param response the {@link Response} to transform and send
     */
    void sendResponse(RoutingContext ctx, Response response) {
        Combinators.foldSequential(hooks, response, (hook, r) -> hook.transformResponse(ctx, r))
                .onSuccess(finalResponse -> {
                    // Captured BEFORE the handoff: a custom serializer's completion future may settle
                    // on any thread, and the terminal cleanup below must run on the request context.
                    Context requestContext = Vertx.currentContext();
                    // A synchronous serialization failure of a success entity (e.g. the resolved
                    // @JsonProfile mapper throwing EncodeException from JsonBodyEncoder.encode) is raised
                    // inside this onSuccess handler — NOT through the fold future — so .onFailure below
                    // would not catch it, leaving the response unfinished and the client hung. The encode
                    // runs before any byte is written to the wire (JsonBodyEncoder returns a buffered
                    // body), so sendFallback500 can still set a 500. Route the throw there explicitly,
                    // and return so afterResponse is NOT also fired here — sendFallback500 fires it once
                    // with the synthetic 500 (the single, correct terminal outcome).
                    Future<Void> wireCompletion;
                    try {
                        wireCompletion = applyToWire(ctx, finalResponse);
                    } catch (RuntimeException wireFailure) {
                        sendFallback500(ctx, wireFailure);
                        return;
                    }
                    // Fire afterResponse AT WIRE HANDOFF so the single invocation reflects the outcome
                    // written, while the completion future may still be pending (OTel SP-4).
                    // forEachSwallowSync catches Exception and swallows per-hook failures, routing each
                    // to the onFailure logger so one throwing observer cannot abort the rest.
                    // (FR-CORE-001.3)
                    Combinators.forEachSwallowSync(
                            hooks,
                            hook -> hook.afterResponse(ctx, finalResponse),
                            (hook, e) -> log.warn("afterResponse observer threw: {}", e.getMessage(), e));
                    // Attached last so a wire failure that is already settled cannot run terminal
                    // cleanup before the handoff hooks have seen the outcome.
                    wireCompletion.onFailure(
                            cause -> runOnRequestContext(requestContext, () -> completeFailedWire(ctx, cause)));
                })
                .onFailure(cause -> sendFallback500(ctx, cause));
    }

    /**
     * Convenience method: {@link #produce(RoutingContext, Object)} then
     * {@link #sendResponse(RoutingContext, Response)}.
     *
     * <p>If {@code produce()} throws (e.g. when no suitable producer exists), the exception
     * propagates to the caller so it can be routed through the error pipeline.
     *
     * @param ctx    the current routing context
     * @param result the method result to produce and send
     */
    void handle(RoutingContext ctx, Object result) {
        Response response = produce(ctx, result);
        sendResponse(ctx, response);
    }

    /**
     * Sends a bare-metal 500 response, firing {@link RequestInterceptor#afterResponse} observe-only
     * hooks first with a synthetic 500 {@link Response} so that metrics, tracing, and audit
     * consumers see every terminal outcome — including catastrophic pipeline failures.
     *
     * <p>Hook invocation follows the same guarded pattern used on the normal path: each hook is
     * called in {@link #hooks} order; a hook that throws is logged at WARN and skipped, but does
     * not prevent subsequent hooks or the bare-metal write from running. This ensures a throwing
     * observer never prevents the 500 from reaching the client.
     *
     * <p><strong>Single-fire guarantee:</strong> {@code afterResponse} fires exactly once per
     * request, reflecting the outcome actually written. On the failure paths that reach this method
     * — a {@link Combinators#foldSequential foldSequential} short-circuit, or a success-entity wire
     * throw — {@link #sendResponse} does <em>not</em> fire {@code afterResponse} before delegating
     * here, so this method introduces the first and only {@code afterResponse} invocation on the
     * failure path (with a synthetic 500). The normal-success path fires its single
     * {@code afterResponse} at wire handoff and never reaches this method — not even when the wire
     * write later fails, which is handled by the completion observer instead.
     *
     * <p>The bare-metal write is skipped when the response is already ended <em>or its head is
     * already committed</em> ({@code headWritten()}). The {@code headWritten()} guard matters when a
     * partially-written error body throws and {@link #serializeErrorWithFailOpen} deliberately
     * rethrows (re-emitting would corrupt the wire): {@code setStatusCode(500).end(...)} on a
     * committed-but-not-ended response would raise {@link IllegalStateException} / split the
     * response. The single {@code afterResponse(500)} still fires — the request <em>did</em> fail
     * terminally — only the wire write is suppressed.
     *
     * <p>Like the normal path, the hooks fire at <em>wire handoff</em>: the bare-metal write is
     * initiated after them and its own completion future is observed, so a 500 that never reaches
     * the client is logged and recorded under {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE}.
     * On this path the marker may land <em>after</em> the completion event was emitted (the end
     * handler can fire first), so the logging is guaranteed while event enrichment is best-effort.
     *
     * @param ctx   the current routing context
     * @param cause the pipeline failure that triggered this fallback
     */
    void sendFallback500(RoutingContext ctx, Throwable cause) {
        log.error("Response pipeline failed — sending bare-metal 500", cause);
        Response synthetic = Response.status(500).build();
        Combinators.forEachSwallowSync(
                hooks,
                hook -> hook.afterResponse(ctx, synthetic),
                (hook, e) -> log.warn("afterResponse observer threw during fallback-500: {}", e.getMessage(), e));
        if (!ctx.response().ended() && !ctx.response().headWritten()) {
            ctx.response()
                    .setStatusCode(500)
                    .end(FALLBACK_500_BODY)
                    // The response is already terminal here — record and log only; no cleanup end().
                    .onFailure(wireFailure -> recordWireFailure(ctx, wireFailure));
        }
    }

    // --- Wire-completion observation ---

    /**
     * Runs {@code task} on {@code requestContext} — the Vert.x context the response was handed off
     * on. Runs inline when already on that context (the common case) or when no context was
     * captured (non-Vert.x caller); otherwise re-enters it via {@link Context#runOnContext}. Guards
     * the wire-failure tail against a custom serializer's completion future settling on a foreign
     * thread, where touching the routing context would be unsafe.
     *
     * @param requestContext the context captured before the wire handoff; may be {@code null}
     * @param task           the wire-failure handling to run on the request context
     */
    private static void runOnRequestContext(Context requestContext, Runnable task) {
        if (requestContext != null && Vertx.currentContext() != requestContext) {
            requestContext.runOnContext(ignored -> task.run());
        } else {
            task.run();
        }
    }

    /**
     * Handles a wire failure reported <em>after</em> the response was handed off: records the cause
     * for the completion event, logs it, and performs the terminal cleanup the serializer contract
     * leaves to the caller. Never writes a status, never re-fires hooks.
     *
     * @param ctx   the current routing context
     * @param cause the failure the wire-completion future settled with
     */
    private void completeFailedWire(RoutingContext ctx, Throwable cause) {
        recordWireFailure(ctx, cause);
        endAfterWireFailure(ctx);
    }

    /**
     * Records a post-handoff wire failure under {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE}
     * (first writer wins, so the first observed failure is the one reported) and logs it at WARN.
     *
     * <p>The WARN carries the request method, path, status, and the cause's <em>class simple
     * name</em> only: a wire failure message may echo peer or payload detail, so the full throwable
     * is logged at DEBUG instead.
     *
     * @param ctx   the current routing context
     * @param cause the failure the wire-completion future settled with
     */
    private void recordWireFailure(RoutingContext ctx, Throwable cause) {
        ctx.data().putIfAbsent(RestRequestCompletionEmitter.KEY_WIRE_FAILURE, cause);
        String method = ctx.request().method().name();
        String path = ctx.request().path();
        log.warn(
                "Wire write failed after response handoff: {} {} (status {}) — {}",
                method,
                path,
                ctx.response().getStatusCode(),
                cause.getClass().getSimpleName());
        if (log.isDebugEnabled()) {
            log.debug("Wire write failure detail for {} {}", method, path, cause);
        }
    }

    /**
     * Ends the response after a wire failure, unless it is already ended. Termination is
     * pipeline-owned: the serializer deliberately leaves a failed response open (it cannot know
     * whether the caller wants to end it), so this is the only place the truncated response is
     * closed.
     *
     * <p>The {@code end()} is fully guarded. A response with a declared {@code Content-Length} that
     * the truncated body no longer satisfies raises {@link IllegalStateException} synchronously, and
     * ending a connection that is already gone fails the returned future — neither may escape into
     * the completion future's failure handler.
     *
     * @param ctx the current routing context
     */
    private void endAfterWireFailure(RoutingContext ctx) {
        HttpServerResponse httpResponse = ctx.response();
        if (httpResponse.ended()) {
            return;
        }
        try {
            httpResponse
                    .end()
                    .onFailure(
                            endFailure -> log.debug("Terminal cleanup end() failed after a wire failure", endFailure));
        } catch (RuntimeException endFailure) {
            log.debug("Terminal cleanup end() could not be initiated after a wire failure", endFailure);
        }
    }

    // --- Wire writing ---

    /**
     * Writes the response status and headers to the HTTP response, then either ends
     * the response (when the entity is {@code null}) or delegates body encoding to the serializer.
     *
     * <p>Returns the <em>wire-completion</em> future of whichever branch ran: the write has only
     * been initiated when this method returns, and for a streamed body the bytes may still be in
     * flight. The future succeeds once the response is fully written and ended, and fails on a
     * post-handoff wire failure — see {@link ResponseSerializer#serialize} for the full contract.
     *
     * @param ctx      the current routing context
     * @param response the final JAX-RS response to write
     * @return the wire-completion future for the initiated write; never {@code null}
     * @throws RuntimeException if the body could not be encoded, with nothing written to the wire
     */
    private Future<Void> applyToWire(RoutingContext ctx, Response response) {
        HttpServerResponse httpResponse = ctx.response().setStatusCode(response.getStatus());

        // Copy JAX-RS response headers to the HTTP response
        var headers = response.getStringHeaders();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                for (String value : entry.getValue()) {
                    httpResponse.putHeader(entry.getKey(), value);
                }
            }
        }

        Object entity = response.getEntity();
        if (entity == null) {
            // No body: HEAD, 304, 204, 412, etc.
            return httpResponse.end();
        } else if (Boolean.TRUE.equals(ctx.get(KEY_ERROR_RESPONSE))) {
            // Error / ProblemDetail body: serialize with the FR-JSON-058A vertx fail-open.
            return serializeErrorWithFailOpen(ctx, response);
        } else {
            // Success entity: delegate directly (a serialization failure propagates as a 500 — slice 3.1).
            return serializer.serialize(ctx, response);
        }
    }

    /**
     * Serializes an error / {@code ProblemDetail} body, failing open to the {@code vertx} mapper when the
     * resolved profile mapper throws (FR-JSON-058A).
     *
     * <p>The status code, the {@code application/problem+json} media type, and all response headers are
     * already set on the wire by {@link #applyToWire}; the profile mapper throws inside
     * {@link dev.vertique.rest.core.response.ResponseBodyEncoder#encode} <em>before</em> any byte is
     * written, so on a throw we remove the stashed profile mapper (key
     * {@link dev.vertique.rest.jaxrs.request.BoundRequest#KEY_RESOLVED_BODY_MAPPER}) — which makes the
     * JSON body encoder fall back to {@code Json.encode} — and re-serialize, preserving the mapped status
     * and media type. The fail-open is confined to the body bytes; it never alters the status, the RFC
     * 9457 media type, or the exception-mapping chain (FR-JSON-059), and never escalates to a bare 500.
     *
     * <p>Only a <em>synchronous</em> throw drives the fail-open: it means nothing was written, so the
     * retry is safe. A failed wire-completion future is a post-handoff failure and is never retried —
     * the returned future is the one of the attempt that actually ran (the retry's, when there was
     * one), so the caller observes exactly one wire completion.
     *
     * @param ctx      the current routing context
     * @param response the mapped error response to serialize
     * @return the wire-completion future of the serialization attempt that ran; never {@code null}
     * @throws RuntimeException if the fail-open retry is not possible or itself fails synchronously
     */
    private Future<Void> serializeErrorWithFailOpen(RoutingContext ctx, Response response) {
        try {
            return serializer.serialize(ctx, response);
        } catch (RuntimeException profileFailure) {
            if (ctx.response().ended() || ctx.response().headWritten()) {
                // The body was already (partially) written before the throw — re-emitting would corrupt
                // the wire. Surface the failure rather than risk a split response.
                throw profileFailure;
            }
            log.warn(
                    "Profile mapper failed to serialize the error body — failing open to the vertx mapper "
                            + "(status {} and media type preserved)",
                    response.getStatus(),
                    profileFailure);
            // Drop the resolved profile mapper so the JSON body encoder uses Json.encode (vertx), then
            // re-serialize the same mapped response (status + media type already set on the wire).
            ctx.data().remove(BoundRequest.KEY_RESOLVED_BODY_MAPPER);
            return serializer.serialize(ctx, response);
        }
    }

    // --- Producer lookup ---

    /**
     * Finds the most specific registered producer for the given type by walking
     * the superclass hierarchy first, then interfaces via BFS.
     *
     * <p>Phase 1 walks the superclass chain so class-exact matches take priority.
     * Phase 2 does a BFS over all interfaces reachable from the full superclass chain,
     * visiting the most-specific type's direct interfaces before super-interfaces.
     *
     * @param type the result class to find a producer for
     * @return the matching producer, or {@code null} if none found
     */
    private ResponseProducer<?> findProducer(Class<?> type) {
        // Phase 1: Walk superclass chain (class matches take priority)
        Class<?> current = type;
        while (current != null) {
            ResponseProducer<?> producer = registry.get(current);
            if (producer != null) {
                return producer;
            }
            current = current.getSuperclass();
        }
        // Phase 2: Walk interfaces via BFS (most-specific type's interfaces first)
        for (Class<?> iface : TypeResolver.getAllInterfaces(type)) {
            ResponseProducer<?> producer = registry.get(iface);
            if (producer != null) {
                return producer;
            }
        }
        return null;
    }

    // --- Internal header-copy helper ---

    /**
     * Static helper for copying headers from a source {@link Response} into a
     * {@link Response.ResponseBuilder}, used when constructing a stripped HEAD response.
     */
    private static final class MultivaluedMapCopier {

        private MultivaluedMapCopier() {}

        /**
         * Copies all string headers from {@code source} into {@code builder}.
         *
         * @param source  the response whose headers are to be copied
         * @param builder the builder to receive the headers
         */
        static void copyHeaders(Response source, Response.ResponseBuilder builder) {
            var headers = source.getStringHeaders();
            if (headers == null) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                for (String value : entry.getValue()) {
                    builder.header(entry.getKey(), value);
                }
            }
        }
    }
}
