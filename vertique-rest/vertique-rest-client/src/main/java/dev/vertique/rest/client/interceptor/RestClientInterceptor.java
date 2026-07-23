// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * SPI for cross-cutting logic applied to REST client requests and responses.
 *
 * <p>The interface provides eight callbacks split into two categories:
 *
 * <h3>Sync observers (fire-and-forget, cannot affect outcome)</h3>
 * <ul>
 *   <li>{@link #onRequest} — called after the request is built, before it is sent</li>
 *   <li>{@link #onResponse} — called after any HTTP response is received</li>
 *   <li>{@link #onError} — called on any failure (transport or response)</li>
 *   <li>{@link #onAttemptCompleted} — called once per physical HTTP attempt, before the
 *       retry/recovery decision</li>
 * </ul>
 *
 * <h3>Async handlers (can affect outcome)</h3>
 * <ul>
 *   <li>{@link #beforeRequest} — returns a (possibly new) context; a failed {@link Future} aborts
 *       the request</li>
 *   <li>{@link #afterResponse} — may inspect response; a failed {@link Future} turns a success
 *       into an error</li>
 *   <li>{@link #recoverRequest} — called on failure before the normal error pipeline; a succeeded
 *       {@link Future} with a new context triggers exactly one recovery retry; a failed
 *       {@link Future} skips recovery and proceeds to {@code transformError}</li>
 *   <li>{@link #transformError} — may wrap or replace the error; the returned {@link Throwable}
 *       replaces the original failure</li>
 * </ul>
 *
 * <p>All methods have default implementations that are no-ops, so implementations only need to
 * override the callbacks relevant to their concern.
 *
 * <p>Interceptors are applied in {@link OrderedExtension} order (phase, then priority, then
 * orderKey). Common use cases:
 * <ul>
 *   <li>Injecting authorization headers (e.g. Bearer tokens) — {@code beforeRequest}</li>
 *   <li>Structured request/response logging — {@code onRequest} + {@code onResponse}</li>
 *   <li>Distributed tracing header propagation — {@code beforeRequest}</li>
 *   <li>Error enrichment or classification — {@code transformError}</li>
 *   <li>Auth token refresh / request rewriting on failure — {@code recoverRequest}</li>
 * </ul>
 *
 * <p>Register interceptors globally via Dagger multibinding ({@code @IntoSet}) to apply them to
 * all clients, or per-client via the builder's {@code register(RestClientInterceptor)} method.
 */
public interface RestClientInterceptor extends OrderedExtension {

    // --- Sync observers ---

    /**
     * Synchronous observer called after the request context is built and before
     * {@link #beforeRequest} async handlers run. Suitable for structured logging or metrics.
     *
     * <p>Exceptions thrown here are swallowed — use {@link #beforeRequest} if you need to modify
     * the request or fail the pipeline.
     *
     * @param ctx the populated request context (read-only; use {@link #beforeRequest} to modify)
     */
    default void onRequest(RestClientRequestContext ctx) {}

    /**
     * Synchronous observer called after any HTTP response is received, regardless of status code.
     * Suitable for structured logging, metrics, or audit.
     *
     * <p>Exceptions thrown here are swallowed.
     *
     * @param request the request context that produced this response
     * @param response the received response context
     */
    default void onResponse(RestClientRequestContext request, RestClientResponseContext response) {}

    /**
     * Synchronous observer called whenever a request fails (transport error, timeout, or
     * response-turned-error via {@link #afterResponse}). Suitable for structured error logging.
     *
     * <p>Exceptions thrown here are swallowed.
     *
     * @param request the request context of the failed request
     * @param response the response context if an HTTP response was received before the failure,
     *     {@code null} for transport-level errors
     * @param error the failure
     */
    default void onError(
            RestClientRequestContext request, @Nullable RestClientResponseContext response, Throwable error) {}

    /**
     * Synchronous observer fired when a physical HTTP attempt COMPLETES (its send future resolves) —
     * including attempts that get retried by the circuit breaker and attempts of a recovery re-dispatch.
     * It fires from inside the send, BEFORE the retry/recovery decision, so every wire attempt that
     * completes is observed (unlike {@link #onResponse}, which fires only when a response is received —
     * never on a transport failure — and {@link #onError}, which fires once after all retries). Suitable
     * for per-attempt audit / metrics.
     * Exactly one of {response, error} is non-null on the {@code completion}. Exceptions are swallowed;
     * implementations MUST NOT block.
     *
     * <p><strong>Circuit-breaker caveat:</strong> this hook observes <em>physical send completions</em>,
     * not breaker-level outcomes. If the call runs inside a circuit breaker whose own timeout is shorter
     * than the HTTP timeout, the breaker may fail the call before the send resolves — the send then fires
     * this hook <em>late</em> (recording the physical attempt that did complete, after the caller already
     * saw the breaker failure). If the breaker is <em>open</em>, the call is short-circuited with no send,
     * so no attempt fires. For breaker-level attempt accounting, observe the breaker itself.
     *
     * @param request    the request context for this attempt
     * @param completion the attempt's completion facts (response/error, callId, ordinal, duration, completedAt, target)
     */
    default void onAttemptCompleted(RestClientRequestContext request, RestClientAttemptCompletion completion) {}

    // --- Async handlers ---

    /**
     * Async handler called before the HTTP request is sent. Implementations may return a modified
     * copy of the context (using the {@code with*} copy-on-write methods) to change headers, body,
     * or URI. A failed returned {@link Future} aborts the request with the given failure.
     *
     * <p>Each interceptor in the chain receives the context returned by the previous interceptor,
     * so changes accumulate through the chain. The default implementation returns the context
     * unchanged.
     *
     * @param ctx the current request context (immutable)
     * @return a {@link Future} containing the (possibly updated) context to pass to the next
     *     interceptor; a failed future aborts the request
     */
    default Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
        return Future.succeededFuture(ctx);
    }

    /**
     * Async handler called after a successful HTTP response is received. A failed returned
     * {@link Future} converts a successful response into an error — useful for enforcing custom
     * validation rules on the response.
     *
     * <p>The default implementation returns an immediately-succeeded future.
     *
     * @param request the request context that produced this response
     * @param response the received response context
     * @return a {@link Future} that completes when post-response processing is done; a failed
     *     future propagates as a client failure
     */
    default Future<Void> afterResponse(RestClientRequestContext request, RestClientResponseContext response) {
        return Future.succeededFuture();
    }

    /**
     * Async recovery handler called when the request fails, before the normal error pipeline.
     *
     * <p>Implementations may rewrite the request context — for example to refresh an auth token
     * and add a fresh {@code Authorization} header — then return a succeeded future with the new
     * context. The proxy will send the request exactly once more using the returned context
     * (no infinite recovery loops).
     *
     * <p>Return a failed future (the default) to skip recovery and proceed directly to
     * {@link #transformError}. The original error is propagated.
     *
     * <p>Interceptors are tried in priority order; the first interceptor that returns a succeeded
     * future wins. Later interceptors are not invoked once recovery is accepted.
     *
     * @param request the request context of the failed request
     * @param response the response context if an HTTP response was received before the failure,
     *     {@code null} for transport-level errors
     * @param error the original failure
     * @return a succeeded {@link Future} with the (possibly updated) request context to retry,
     *     or a failed {@link Future} to decline recovery and pass the error to
     *     {@link #transformError}
     */
    default Future<RestClientRequestContext> recoverRequest(
            RestClientRequestContext request, @Nullable RestClientResponseContext response, Throwable error) {
        return Future.failedFuture(error);
    }

    /**
     * Async handler called when the request fails. Implementations may wrap, replace, or suppress
     * the error by returning a different (or the same) {@link Throwable}. The returned throwable
     * replaces the original failure propagated to the caller.
     *
     * <p>The default implementation returns the original {@code error} unchanged.
     *
     * @param request the request context of the failed request
     * @param response the response context if an HTTP response was received before the failure,
     *     {@code null} for transport-level errors
     * @param error the original failure
     * @return a {@link Future} containing the throwable to propagate; must not be a failed future
     */
    default Future<Throwable> transformError(
            RestClientRequestContext request, @Nullable RestClientResponseContext response, Throwable error) {
        return Future.succeededFuture(error);
    }
}
