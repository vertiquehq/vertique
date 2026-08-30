// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

/**
 * SPI for receiving {@link RestRequestCompletedEvent} notifications.
 *
 * <p>Emitted for every handled request that completes through the normal HTTP response lifecycle
 * (success and all failure paths). Successful protocol upgrades (e.g. WebSocket 101) complete
 * out-of-band via {@code RequestContextLifecycle.completeNow()} and do NOT produce a completion
 * event; channel lifecycle observers receive those transitions separately. A <em>failed</em>
 * upgrade that ends with an HTTP error response DOES produce a completion event.
 *
 * <p>Implementations are contributed via Dagger {@code Set<RestRequestCompletedListener>}
 * multibinding and are invoked synchronously on the Vert.x event loop thread after each handled
 * request completes. Implementations MUST NOT block the event loop — any I/O or heavy processing
 * must be dispatched to a worker thread or handled via {@code Future} composition.
 *
 * <p>The emitter ({@link RestRequestCompletionEmitter}) isolates each listener: an exception thrown
 * by one listener is caught, logged at {@code WARN}, and does not prevent subsequent listeners from
 * receiving the event or affect the HTTP response.
 *
 * <p>Invocation is fire-and-forget: the emitter does not wait for any asynchronous work a listener
 * might initiate. If a listener needs to emit to a durable sink it should do so asynchronously and
 * handle its own failure path.
 *
 * <p>Implementations of this interface MUST remain side-effect/metrics-only. Request evidence
 * capture belongs to the {@link dev.vertique.rest.core.capture.RestRequestCaptureCoordinator} SPI;
 * duplicating capture from a listener would violate the single-capture invariant.
 */
public interface RestRequestCompletedListener {

    /**
     * Called once per handled request after the HTTP response has completed.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param event the completed-request event; never {@code null}
     */
    void onCompleted(RestRequestCompletedEvent event);
}
