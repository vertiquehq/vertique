// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.internal.SseParser;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.codec.SseEvent;
import jakarta.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * The server-sent events of an {@link ActiveStream}: the parse of its body into {@link SseEvent} values.
 *
 * <p>The events are pulled: the stream asks the body for one chunk at a time, only while it has demand and no
 * parsed event is waiting, so a paused consumer holds at most the events of one chunk and the body's own
 * queue then pauses the response. The stream completes once every event has been delivered. An event that
 * exceeds the bound fails the stream.
 *
 * <p>The state is confined to the stream's Vert.x context; calls from other threads hop onto it.
 */
final class SseEventStream implements ReadStream<SseEvent> {

    private final ActiveStream owner;
    private final StreamBody body;
    private final Context context;
    private final SseParser parser;
    private final ArrayDeque<SseEvent> held = new ArrayDeque<>();
    private final boolean live;
    private long demand;
    private boolean flowChosen;
    private boolean pumping;
    private boolean requested;
    private boolean upstreamDone;
    private volatile @Nullable String lastEventId;
    private @Nullable Handler<SseEvent> handler;

    /**
     * Creates the event stream of a body.
     *
     * @param owner the stream whose body is parsed
     * @param body the body that is read
     * @param context the context the stream's handlers run on
     * @param maxEventBytes the most raw bytes of one event
     * @param live {@code false} for a stream that never reads the body and only passes on how the exchange
     *     ended, because the head is not an event stream
     */
    SseEventStream(ActiveStream owner, StreamBody body, Context context, int maxEventBytes, boolean live) {
        this.owner = owner;
        this.body = body;
        this.context = context;
        this.live = live;
        this.parser = new SseParser(maxEventBytes, held::add);
        if (live) {
            body.pause();
            body.handler(this::onChunk);
        }
    }

    /** The id as it stood at the last event delivered to the consumer. */
    Optional<String> lastEventId() {
        return Optional.ofNullable(lastEventId);
    }

    /** The number of parsed events waiting for the consumer. */
    int heldEvents() {
        return held.size();
    }

    /** The body ended and the consumer received every chunk: the stream completes once no event is waiting. */
    void upstreamDone() {
        upstreamDone = true;
        if (!pumping && held.isEmpty()) {
            owner.terminate(owner.cancelReason());
        }
    }

    // --- consumer side ---

    @Override
    public ReadStream<SseEvent> exceptionHandler(@Nullable Handler<Throwable> handler) {
        body.exceptionHandler(handler);
        return this;
    }

    @Override
    public ReadStream<SseEvent> handler(@Nullable Handler<SseEvent> handler) {
        onContext(() -> {
            this.handler = handler;
            if (handler != null && !flowChosen) {
                flowChosen = true;
                demand = Long.MAX_VALUE;
            }
            pump();
        });
        return this;
    }

    @Override
    public ReadStream<SseEvent> endHandler(@Nullable Handler<Void> handler) {
        body.endHandler(handler);
        return this;
    }

    @Override
    public ReadStream<SseEvent> pause() {
        onContext(() -> {
            flowChosen = true;
            demand = 0;
        });
        return this;
    }

    @Override
    public ReadStream<SseEvent> resume() {
        return fetch(Long.MAX_VALUE);
    }

    @Override
    public ReadStream<SseEvent> fetch(long amount) {
        if (amount <= 0) {
            return this;
        }
        onContext(() -> {
            flowChosen = true;
            demand = demand + amount < 0 ? Long.MAX_VALUE : demand + amount;
            pump();
        });
        return this;
    }

    // --- stream side ---

    private void onChunk(Buffer chunk) {
        requested = false;
        if (owner.ended()) {
            return;
        }
        try {
            byte[] bytes = chunk.getBytes();
            parser.feed(bytes, 0, bytes.length);
        } catch (HttpBodyLimitException failure) {
            held.clear();
            owner.terminate(failure);
            return;
        }
        pump();
    }

    private void pump() {
        if (pumping || !live) {
            return;
        }
        pumping = true;
        try {
            while (!stopped()) {
                while (demand > 0 && handler != null && !held.isEmpty() && !stopped()) {
                    SseEvent event = held.poll();
                    if (demand != Long.MAX_VALUE) {
                        demand--;
                    }
                    lastEventId = event.id();
                    deliver(event);
                }
                if (!stopped() && held.isEmpty() && demand > 0 && handler != null && !requested && !upstreamDone) {
                    requested = true;
                    body.fetch(1);
                } else {
                    break;
                }
            }
            if (stopped()) {
                held.clear();
            }
        } finally {
            pumping = false;
        }
        if (upstreamDone && held.isEmpty()) {
            // A cancellation set by the last event's handler still fails the stream.
            owner.terminate(owner.cancelReason());
        }
    }

    /** Whether the stream ended or a cancellation was set: nothing more is delivered. */
    private boolean stopped() {
        return owner.ended() || owner.cancelReason() != null;
    }

    /** Hands an event to the handler; a throw ends the stream with that throwable and drops the held events. */
    private void deliver(SseEvent event) {
        try {
            handler.handle(event);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            held.clear();
            owner.terminate(failure);
        }
    }

    private void onContext(Runnable action) {
        if (Vertx.currentContext() == context) {
            action.run();
        } else {
            context.runOnContext(ignored -> action.run());
        }
    }
}
