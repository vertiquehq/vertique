// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import io.vertx.ext.web.RoutingContext;

/**
 * SPI for receiving {@link HttpRequestCompletedEvent} notifications.
 *
 * <p>Called once for each HTTP request that completes through the normal HTTP response lifecycle
 * without any transport claiming it (success and all failure paths). A request that a JAX-RS
 * operation route claimed is observed through {@link RestRequestCompletedListener} instead, and a
 * request that another transport claimed produces no rest-core completion event.
 *
 * <p>Implementations are contributed via the Dagger {@code @Multibinds
 * Set<HttpRequestCompletedListener>} multibinding. The emitter ({@link RestRequestCompletionEmitter})
 * reaches each one through {@link #onCompleted(HttpRequestCompletedEvent, RoutingContext)}, with the
 * live root routing context of the request; that overload's default delegates to
 * {@link #onCompleted(HttpRequestCompletedEvent)}. The emitter calls each listener inline, on the
 * thread that ended the response, which is usually but not always the Vert.x event loop, inside the
 * {@link RequestCompletionScope} bracket. Implementations MUST NOT block — any I/O or heavy
 * processing must be dispatched to a worker thread or handled via {@code Future} composition.
 *
 * <p>Listeners are unordered: the emitter promises no invocation order, and no implementation may
 * depend on the side effects of another.
 *
 * <p>The emitter isolates each listener: an {@link Exception} thrown by one listener is caught,
 * logged at {@code WARN}, and does not prevent the remaining listeners from receiving the event or
 * affect the HTTP response. An {@link Error} is not caught and propagates. Because the caught
 * exception's message reaches the application log, an implementation MUST NOT put credentials,
 * tokens, personal data, or raw request values into the exception message or type it throws.
 *
 * <p>Invocation is fire-and-forget: the emitter does not wait for any asynchronous work a listener
 * might initiate. If a listener needs to emit to a durable sink it should do so asynchronously and
 * handle its own failure path.
 */
public interface HttpRequestCompletedListener {

    /**
     * Called once per request that no transport claimed, after the HTTP response has completed.
     *
     * <p>The framework reaches this method only through the default
     * {@link #onCompleted(HttpRequestCompletedEvent, RoutingContext)}, which delegates to it. An
     * implementation that overrides that overload without delegating never sees this call.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. The exception message is logged, so it must carry no credentials, tokens,
     * personal data, or raw request values.
     *
     * @param event the completed-request event; never {@code null}
     */
    void onCompleted(HttpRequestCompletedEvent event);

    /**
     * Called once per request that no transport claimed, after the HTTP response has completed,
     * with the live root routing context of the request. This is the method the framework calls; the
     * default delegates to {@link #onCompleted(HttpRequestCompletedEvent)}, so a listener that
     * implements only that method receives each event exactly once. Override this method when the
     * listener needs per-request state or the live request.
     *
     * <p>The call happens on the thread that ended the response. That is usually the Vert.x event
     * loop, but not always: a response ended from another thread completes on that thread.
     *
     * <p>{@code routingContext} is the live root context of the request, and remains so for a
     * request served by a sub-router. A sub-router route handler receives a different
     * {@code RoutingContext} wrapper, which shares {@code request()}, {@code response()} and
     * {@code data()} with the root context. Key per-request state by
     * {@code routingContext.request()}, never by the {@code RoutingContext} object.
     *
     * <p>An implementation MUST NOT block, MUST NOT write to the response, MUST NOT call
     * {@code next()} or {@code fail()} on the context, and MUST NOT keep the context after
     * returning.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. The exception message is logged, so it must carry no credentials, tokens,
     * personal data, or raw request values.
     *
     * @param event          the completed-request event; never {@code null}
     * @param routingContext the live root routing context of the request; never {@code null}
     */
    default void onCompleted(HttpRequestCompletedEvent event, RoutingContext routingContext) {
        onCompleted(event);
    }
}
