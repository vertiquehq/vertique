// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;

/**
 * Per-request bridge between an application's async event producer and the HTTP SSE response
 * stream.
 *
 * <p>An {@link SseChannel} is created by {@link SseChannelFactory} at the start of each SSE
 * request. The application holds a reference to the channel, enqueues events via
 * {@link #send(SseEvent)}, and signals completion via {@link #complete()} or failure via
 * {@link #fail(Throwable)}. The framework drains the channel by consuming
 * {@link #stream()} and writing each event to the HTTP response in the SSE wire format.
 *
 * <p>Typical usage pattern in a JAX-RS resource:
 * <pre>{@code
 * @GET
 * @Path("/events")
 * @Produces("text/event-stream")
 * @Operation(operationId = "streamEvents")
 * public ReadStream<SseEvent> streamEvents() {
 *     SseChannel channel = sseChannelFactory.create();
 *     eventService.subscribe(event -> channel.send(SseEvent.of(event)));
 *     channel.onClose(() -> eventService.unsubscribe());
 *     return channel.stream();
 * }
 * }</pre>
 *
 * <p>Implementations are not required to be thread-safe — all calls should be made from the
 * Vert.x event loop context that owns the channel.
 */
public interface SseChannel {

    /**
     * Returns the {@link ReadStream} that the framework consumes to write SSE frames to the
     * HTTP response. The resource method should return this stream directly.
     *
     * @return the read stream of {@link SseEvent} values backing this channel
     */
    ReadStream<SseEvent> stream();

    /**
     * Enqueues a pre-built {@link SseEvent} for delivery to the connected client. Returns a
     * succeeded future once the event is accepted into the channel buffer, or a failed future
     * if the buffer is full and the channel's {@link BufferOverflowPolicy} is
     * {@link BufferOverflowPolicy#FAIL}.
     *
     * @param event the SSE event to send; must not be {@code null}
     * @return a {@link Future} that completes when the event is accepted or fails on overflow
     */
    Future<Void> send(SseEvent event);

    /**
     * Convenience overload that wraps {@code data} in an {@link SseEvent} via
     * {@link SseEvent#of(Object)} and delegates to {@link #send(SseEvent)}.
     *
     * @param data the event payload; {@link String} values are emitted verbatim, structured
     *             objects are JSON-serialized by the framework
     * @return a {@link Future} that completes when the event is accepted or fails on overflow
     */
    Future<Void> send(Object data);

    /**
     * Convenience overload that creates a typed {@link SseEvent} with the given event name and
     * data payload, then delegates to {@link #send(SseEvent)}.
     *
     * @param event the SSE event type name (maps to the {@code event:} field on the wire)
     * @param data  the event payload; {@link String} values are emitted verbatim, structured
     *              objects are JSON-serialized by the framework
     * @return a {@link Future} that completes when the event is accepted or fails on overflow
     */
    Future<Void> send(String event, Object data);

    /**
     * Signals that the event stream has ended normally. The framework will flush any remaining
     * buffered events, then close the HTTP response. Calling this method on an already-closed
     * channel has no effect.
     */
    void complete();

    /**
     * Signals that the event stream has ended with an error. The framework will attempt to flush
     * any remaining buffered events, then close the HTTP response. Calling this method on an
     * already-closed channel has no effect.
     *
     * @param cause the error that caused the stream to terminate
     */
    void fail(Throwable cause);

    /**
     * Returns {@code true} if this channel has been closed — either because {@link #complete()}
     * or {@link #fail(Throwable)} was called, or because the client disconnected.
     *
     * @return {@code true} if the channel is closed
     */
    boolean isClosed();

    /**
     * Registers a handler that is invoked when the channel closes, regardless of whether closure
     * was initiated by the server (via {@link #complete()} or {@link #fail(Throwable)}) or by the
     * client disconnecting. Use this to clean up subscriptions or release resources.
     *
     * @param handler the callback to invoke on close; must not be {@code null}
     * @return this channel instance for fluent chaining
     */
    SseChannel onClose(Runnable handler);
}
