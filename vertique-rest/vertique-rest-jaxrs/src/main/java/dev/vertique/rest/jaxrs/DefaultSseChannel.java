// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.sse.BufferOverflowPolicy;
import dev.vertique.rest.core.sse.SseChannel;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link SseChannel} that also implements {@link ReadStream}{@code <SseEvent>}.
 *
 * <p>The channel maintains a bounded {@link ArrayDeque} buffer for in-flight events. When
 * {@link #send(SseEvent)} is called, the event is enqueued and, if a data handler is registered
 * and the stream is not paused, any buffered events are drained immediately on the Vert.x context.
 *
 * <p>Flow control follows the standard Vert.x {@link ReadStream} contract:
 * <ul>
 *   <li>{@link #pause()} — stops draining; events continue to accumulate in the buffer up to capacity.</li>
 *   <li>{@link #resume()} — resumes draining from the buffer.</li>
 *   <li>{@link #fetch(long)} — demand-based mode: drain at most {@code n} events from the buffer.</li>
 *   <li>Setting the handler to {@code null} via {@link #handler(Handler)} cancels the channel.</li>
 * </ul>
 *
 * <p>Instances are not thread-safe. All operations must be performed from the Vert.x event loop
 * context that owns the channel.
 */
@Slf4j
class DefaultSseChannel implements SseChannel, ReadStream<SseEvent> {

    // --- Fields ---

    private final ArrayDeque<SseEvent> buffer;
    private final int bufferSize;
    private final BufferOverflowPolicy overflowPolicy;
    private final Context context;

    private Handler<SseEvent> dataHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;

    private final List<Runnable> closeHandlers = new ArrayList<>();

    private boolean closed = false;
    /** Whether the channel completed normally (vs. failed). Only meaningful when {@link #closed}. */
    private boolean completed = false;
    /** The failure cause when the channel was closed via {@link #fail(Throwable)}. */
    private Throwable failureCause;

    private boolean paused = false;
    /** Prevents redundant {@code runOnContext} scheduling when multiple sends arrive in sequence. */
    private boolean drainScheduled = false;
    /** Demand counter used in fetch mode ({@code -1} means infinite / resume mode). */
    private long demand = Long.MAX_VALUE;

    // --- Constructor ---

    /**
     * Creates a new channel bound to the given Vert.x instance.
     *
     * @param vertx          the Vert.x instance used to obtain the current event loop context
     * @param bufferSize     maximum number of events that may be buffered before the overflow policy
     *                       is applied; must be {@code >= 1}
     * @param overflowPolicy behavior when the buffer is full; must not be {@code null}
     */
    DefaultSseChannel(Vertx vertx, int bufferSize, BufferOverflowPolicy overflowPolicy) {
        this.bufferSize = bufferSize;
        this.overflowPolicy = overflowPolicy;
        this.buffer = new ArrayDeque<>(bufferSize);
        this.context = vertx.getOrCreateContext();
    }

    // --- SseChannel ---

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code this} because this class implements both {@link SseChannel} and
     * {@link ReadStream}{@code <SseEvent>}.
     *
     * @return this instance as a {@link ReadStream}
     */
    @Override
    public ReadStream<SseEvent> stream() {
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Enqueues the event in the internal buffer. If the buffer is full:
     * <ul>
     *   <li>{@link BufferOverflowPolicy#FAIL} — returns a failed future.</li>
     *   <li>{@link BufferOverflowPolicy#DROP_OLDEST} — removes the head of the queue to make room.</li>
     * </ul>
     * After successful enqueue, buffered events are drained if a handler is registered and the
     * stream is not paused.
     *
     * @param event the SSE event to send; must not be {@code null}
     * @return a succeeded future when the event is accepted, or a failed future on overflow with
     *         {@link BufferOverflowPolicy#FAIL}
     */
    @Override
    public Future<Void> send(SseEvent event) {
        if (closed) {
            return Future.failedFuture(new IllegalStateException("SSE channel is closed"));
        }
        if (buffer.size() >= bufferSize) {
            if (overflowPolicy == BufferOverflowPolicy.FAIL) {
                return Future.failedFuture(new IllegalStateException("SSE channel buffer full"));
            } else {
                // DROP_OLDEST — make room
                buffer.poll();
            }
        }
        buffer.offer(event);
        drainIfReady();
        return Future.succeededFuture();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Wraps {@code data} in an {@link SseEvent} via {@link SseEvent#of(Object)} and delegates
     * to {@link #send(SseEvent)}.
     *
     * @param data the event payload
     * @return a future that completes when the event is accepted or fails on overflow
     */
    @Override
    public Future<Void> send(Object data) {
        return send(SseEvent.of(data));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Constructs a typed {@link SseEvent} with the given event name and data payload and
     * delegates to {@link #send(SseEvent)}.
     *
     * @param event the SSE event type name
     * @param data  the event payload
     * @return a future that completes when the event is accepted or fails on overflow
     */
    @Override
    public Future<Void> send(String event, Object data) {
        return send(SseEvent.builder().event(event).data(data).build());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Marks the channel as closed, drains any remaining buffered events to the handler, then
     * fires the {@link #endHandler(Handler) end handler} and all registered close callbacks.
     * Calling this on an already-closed channel has no effect.
     */
    @Override
    public void complete() {
        if (closed) {
            return;
        }
        closed = true;
        completed = true;
        drainAll();
        fireEndHandler();
        fireCloseHandlers();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Marks the channel as closed and fires the {@link #exceptionHandler(Handler) exception
     * handler} followed by all registered close callbacks. Calling this on an already-closed
     * channel has no effect.
     *
     * @param cause the error that caused the stream to terminate
     */
    @Override
    public void fail(Throwable cause) {
        if (closed) {
            return;
        }
        closed = true;
        failureCause = cause;
        if (exceptionHandler != null) {
            try {
                exceptionHandler.handle(cause);
            } catch (Exception e) {
                log.warn("SSE exception handler threw", e);
            }
        }
        fireCloseHandlers();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * {@inheritDoc}
     *
     * @param handler the callback to invoke when the channel closes; must not be {@code null}
     * @return this channel instance for fluent chaining
     */
    @Override
    public SseChannel onClose(Runnable handler) {
        closeHandlers.add(handler);
        return this;
    }

    // --- ReadStream ---

    /**
     * Sets the data handler for this stream. When a non-null handler is registered, any buffered
     * events are drained immediately (subject to the paused state and demand). Setting the handler
     * to {@code null} cancels the channel.
     *
     * @param handler the handler to receive {@link SseEvent} items, or {@code null} to cancel
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<SseEvent> handler(Handler<SseEvent> handler) {
        this.dataHandler = handler;
        if (handler == null) {
            // Treat null handler as client cancel
            if (!closed) {
                closed = true;
                fireCloseHandlers();
            }
        } else if (closed) {
            // Replay terminal state when handlers are attached after close
            drainAll();
            if (completed) {
                fireEndHandler();
            } else if (failureCause != null && exceptionHandler != null) {
                try {
                    exceptionHandler.handle(failureCause);
                } catch (Exception e) {
                    log.warn("SSE exception handler threw during replay", e);
                }
            }
        } else {
            drainIfReady();
        }
        return this;
    }

    /**
     * Pauses the stream. While paused, buffered events are retained but not delivered to the
     * data handler.
     *
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<SseEvent> pause() {
        paused = true;
        demand = 0;
        return this;
    }

    /**
     * Resumes the stream and drains any buffered events to the data handler.
     *
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<SseEvent> resume() {
        paused = false;
        demand = Long.MAX_VALUE;
        drainIfReady();
        return this;
    }

    /**
     * Switches the stream to demand mode, draining up to {@code amount} events from the buffer.
     *
     * @param amount the number of additional events to request; must be {@code >= 0}
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<SseEvent> fetch(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("fetch amount must be >= 0");
        }
        paused = false;
        // Guard against overflow
        demand = (demand == Long.MAX_VALUE) ? amount : Math.min(Long.MAX_VALUE, demand + amount);
        drainIfReady();
        return this;
    }

    /**
     * Registers a handler that is called when the stream ends normally (via {@link #complete()}).
     *
     * @param endHandler the end handler
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<SseEvent> endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }

    /**
     * Registers a handler that is called when the stream fails (via {@link #fail(Throwable)}).
     *
     * @param exceptionHandler the exception handler
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<SseEvent> exceptionHandler(Handler<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    // --- Internal helpers ---

    /**
     * Drains buffered events to the data handler if the stream is ready: a handler is set, the
     * stream is not paused, and there is remaining demand.
     */
    private void drainIfReady() {
        if (dataHandler == null || paused || demand <= 0 || buffer.isEmpty()) {
            return;
        }
        // Drain synchronously if we're already on the event loop context
        if (context == Vertx.currentContext()) {
            drain();
        } else if (!drainScheduled) {
            drainScheduled = true;
            context.runOnContext(v -> {
                drainScheduled = false;
                drain();
            });
        }
    }

    /**
     * Synchronously drains up to {@link #demand} events from the buffer to the data handler.
     * Stops early if the stream becomes paused, demand reaches zero, or the buffer empties.
     */
    private void drain() {
        while (!paused && demand > 0 && !buffer.isEmpty()) {
            SseEvent event = buffer.poll();
            if (demand != Long.MAX_VALUE) {
                demand--;
            }
            try {
                dataHandler.handle(event);
            } catch (Exception e) {
                log.warn("SSE data handler threw during drain", e);
            }
        }
    }

    /**
     * Drains all remaining buffered events unconditionally (used on {@link #complete()}).
     * The paused flag and demand are bypassed to ensure complete flushing on close.
     */
    private void drainAll() {
        if (dataHandler == null) {
            return;
        }
        while (!buffer.isEmpty()) {
            SseEvent event = buffer.poll();
            try {
                dataHandler.handle(event);
            } catch (Exception e) {
                log.warn("SSE data handler threw during final drain", e);
            }
        }
    }

    /** Fires the {@link #endHandler} if one is registered. */
    private void fireEndHandler() {
        if (endHandler != null) {
            try {
                endHandler.handle(null);
            } catch (Exception e) {
                log.warn("SSE end handler threw", e);
            }
        }
    }

    /** Invokes all registered {@link #closeHandlers} in registration order. */
    private void fireCloseHandlers() {
        for (Runnable handler : closeHandlers) {
            try {
                handler.run();
            } catch (Exception e) {
                log.warn("SSE close handler threw", e);
            }
        }
    }
}
