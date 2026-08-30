// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.response.SerializedBody;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * SPI for cross-cutting logic applied to the server-side HTTP request and response pipeline.
 *
 * <p>The interface provides callbacks split into two categories:
 *
 * <h3>Sync observers (fire-and-forget, cannot affect outcome)</h3>
 * <ul>
 *   <li>{@link #onRequest} — called when the request arrives at the framework layer, before
 *       any async handling begins; suitable for structured logging or metrics</li>
 *   <li>{@link #onError} — called when an error enters the error pipeline, with the
 *       <em>original</em> cause before any {@link ErrorInterceptor} mapping; suitable for
 *       error metrics or alerting</li>
 *   <li>{@link #onSerialize} — called read-only just before the serialized body is written
 *       to the wire; suitable for digest or checksum computation</li>
 *   <li>{@link #afterResponse} — called at wire handoff for every terminal outcome: success
 *       responses, mapped error responses, and the fallback-500 path when
 *       {@link #transformResponse} fails catastrophically; suitable for audit emission</li>
 * </ul>
 *
 * <h3>Async handlers (can affect outcome)</h3>
 * <ul>
 *   <li>{@link #beforeRequest} — called before the OpenAPI validation layer; a failed
 *       {@link Future} short-circuits the request pipeline</li>
 *   <li>{@link #transformResponse} — called after the response is produced but before
 *       serialization; implementations may return a modified {@link Response} to add headers,
 *       change status, or replace the entity</li>
 * </ul>
 *
 * <p>All methods have default no-op implementations. Interceptors are sorted by the
 * {@link OrderedExtension} ordering contract — phase first, then {@link #priority()} ascending,
 * then {@link #orderKey()} as a stable tie-break (lower value runs first within a phase).
 *
 * <p><strong>Naming note:</strong> This interface is the server-side counterpart to
 * {@link dev.vertique.rest.client.interceptor.RestClientInterceptor}. Vocabulary intentionally
 * differs where semantics differ: {@code beforeRequest}/{@code transformResponse}/{@code afterResponse}
 * on the server side vs {@code beforeRequest}/{@code afterResponse} on the client side.
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Request-scoped MDC context propagation — {@code beforeRequest}</li>
 *   <li>Response header enrichment (e.g. correlation ID, cache-control) — {@code transformResponse}</li>
 *   <li>Response body digest computation — {@code onSerialize}</li>
 *   <li>Error rate metrics — {@code onError}</li>
 *   <li>Access logging — {@code onRequest}</li>
 *   <li>Audit emission for both success and error responses — {@code afterResponse}</li>
 * </ul>
 *
 * <p>Register interceptors via Dagger multibinding ({@code @IntoSet}).
 *
 * @see OrderedExtension
 */
public interface RequestInterceptor extends OrderedExtension {

    /**
     * Well-known key under which the original {@link Throwable} is stored in
     * {@link RoutingContext#data()} during error pipeline processing.
     *
     * <p>Use this key in {@code afterResponse} or {@link OperationInterceptor} callbacks to
     * distinguish error responses from success responses and access the root cause.
     *
     * <pre>{@code
     * Throwable originalError = (Throwable) rc.data().get(RequestInterceptor.ORIGINAL_ERROR_KEY);
     * }</pre>
     */
    String ORIGINAL_ERROR_KEY = "dev.vertique.rest.originalError";

    // --- Sync observers ---

    /**
     * Synchronous observer called when the request arrives at the framework layer, before any
     * {@link #beforeRequest} async handlers run. Suitable for access logging or request-count
     * metrics.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation. Use {@link #beforeRequest} to modify or short-circuit the pipeline.
     *
     * @param rc the Vert.x {@link RoutingContext} for the incoming request
     */
    default void onRequest(RoutingContext rc) {}

    /**
     * Synchronous observer called when an error enters the error pipeline, with the
     * <em>original</em> cause before any {@link ErrorInterceptor#beforeMapping} transformation.
     * Suitable for error-rate metrics or structured error logging.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param rc    the Vert.x {@link RoutingContext} for the failed request
     * @param error the original failure, before any mapping
     */
    default void onError(RoutingContext rc, Throwable error) {}

    /**
     * Synchronous observer called read-only just before the serialized body is written to the
     * wire. Suitable for digest or checksum computation over the final encoded bytes.
     *
     * <p>This callback is read-only: neither the {@link Response} nor the
     * {@link SerializedBody} should be modified here. Use {@link #transformResponse} to
     * modify the response before serialization.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * @param rc       the Vert.x {@link RoutingContext}
     * @param response the JAX-RS {@link Response} that produced the body
     * @param body     the serialized body about to be written to the wire
     */
    default void onSerialize(RoutingContext rc, Response response, SerializedBody body) {}

    /**
     * Synchronous observer called at <strong>wire handoff</strong>, for <strong>every</strong>
     * terminal outcome — success responses, error responses, and the bare-metal fallback 500 that
     * fires when the {@link #transformResponse} chain fails catastrophically.
     *
     * <p>"Wire handoff" means the response write has been <em>initiated</em>: the status and headers
     * are on the wire and the body write has started. For a streamed body the bytes may still be in
     * flight when this hook runs, so a response observed here can still be truncated by a mid-stream
     * failure or a client abort. That is deliberate — the hook fires while the routing context and
     * the tracing span are still active, which is what lets observers attribute the outcome to the
     * request. Wire <em>completion</em> is reported separately, through the completion event's
     * wire-failure classification, not by delaying or re-firing this hook.
     *
     * <p>For the fallback-500 path the {@code response} argument is a synthetic
     * {@code Response.status(500)} with no entity. The response is final and cannot be modified
     * in any case — use this for audit, metrics, or logging only.
     *
     * <p>For error responses, the original throwable is available via
     * {@code rc.data().get(ORIGINAL_ERROR_KEY)}.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. Use {@link #transformResponse} to modify responses.
     *
     * @param rc       the current routing context
     * @param response the final response after all transformations, as handed off to the wire; on
     *                 the fallback-500 path this is a synthetic {@code 500} with no entity
     */
    default void afterResponse(RoutingContext rc, Response response) {}

    // --- Async handlers ---

    /**
     * Async handler called before the OpenAPI validation layer processes the request.
     * Implementations may perform authentication token extraction, MDC population, or other
     * request enrichment. A failed {@link Future} short-circuits the entire request pipeline.
     *
     * <p>The default implementation returns an immediately-succeeded future.
     *
     * @param rc the Vert.x {@link RoutingContext} for the incoming request
     * @return a {@link Future} that completes when pre-request processing is done; a failed
     *         future short-circuits the request pipeline
     */
    default Future<Void> beforeRequest(RoutingContext rc) {
        return Future.succeededFuture();
    }

    /**
     * Async handler called after the response is produced but before serialization. Implementations
     * may add response headers, change the status code, or replace the response entity by returning
     * a new {@link Response} instance.
     *
     * <p>Each interceptor in the chain receives the response returned by the previous interceptor,
     * so transformations accumulate through the chain. The default implementation returns the
     * response unchanged.
     *
     * @param rc       the Vert.x {@link RoutingContext}
     * @param response the response to transform
     * @return a {@link Future} containing the (possibly updated) {@link Response}; a failed
     *         future routes the request to the error pipeline
     */
    default Future<Response> transformResponse(RoutingContext rc, Response response) {
        return Future.succeededFuture(response);
    }
}
