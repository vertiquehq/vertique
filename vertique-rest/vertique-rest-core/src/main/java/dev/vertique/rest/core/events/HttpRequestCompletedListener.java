// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

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
 * invokes them synchronously, on the thread that ended the response, inside the
 * {@link RequestCompletionScope} bracket. Implementations MUST NOT block — any I/O or heavy
 * processing must be dispatched to a worker thread or handled via {@code Future} composition.
 * Listeners are unordered.
 *
 * <p>The emitter isolates each listener: an exception thrown by one listener is caught, logged at
 * {@code WARN}, and does not prevent subsequent listeners from receiving the event or affect the
 * HTTP response. Because the caught exception's message reaches the application log, an
 * implementation MUST NOT put credentials, tokens, personal data, or raw request values into the
 * exception message or type it throws.
 */
public interface HttpRequestCompletedListener {

    /**
     * Called once per request that no transport claimed, after the HTTP response has completed.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. The exception message is logged, so it must carry no credentials, tokens,
     * personal data, or raw request values.
     *
     * @param event the completed-request event; never {@code null}
     */
    void onCompleted(HttpRequestCompletedEvent event);
}
