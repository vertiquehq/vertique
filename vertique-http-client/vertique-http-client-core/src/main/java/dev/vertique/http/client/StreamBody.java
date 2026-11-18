// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.codec.spi.BodyStream;
import jakarta.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * The body of an {@link ActiveStream}: the client's own {@link ReadStream}, fed by the response through
 * {@link #sink()}. It starts paused and flows once a handler is set, holds at most a small queue of
 * chunks and reports a full write queue to the response so back-pressure reaches the connection.
 *
 * <p>The state is confined to the stream's Vert.x context; calls from other threads hop onto it.
 */
final class StreamBody implements ReadStream<Buffer> {

    /** The queued bytes above which the response is paused. */
    private static final long HIGH_WATER_BYTES = 64L * 1024;

    private final ActiveStream owner;
    private final Context context;
    private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
    private final Sink sink = new Sink();
    private long queuedBytes;
    private long demand;
    private boolean flowChosen;
    private boolean full;
    private boolean ended;
    private boolean closed;
    private boolean draining;
    private boolean terminalTold;
    private @Nullable Throwable closeFailure;
    private @Nullable Handler<Buffer> handler;
    private @Nullable Handler<Void> endHandler;
    private @Nullable Handler<Throwable> exceptionHandler;
    private @Nullable Handler<Void> drainHandler;

    StreamBody(ActiveStream owner, Context context) {
        this.owner = owner;
        this.context = context;
    }

    /** The write side the response body is piped into. */
    BodyStream<Void> sink() {
        return sink;
    }

    // --- consumer side ---

    @Override
    public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
        onContext(() -> {
            this.exceptionHandler = handler;
            tellTerminal();
        });
        return this;
    }

    @Override
    public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
        onContext(() -> {
            this.handler = handler;
            if (handler != null && !flowChosen) {
                flowChosen = true;
                demand = Long.MAX_VALUE;
            }
            drain();
        });
        return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(@Nullable Handler<Void> handler) {
        onContext(() -> {
            this.endHandler = handler;
            tellTerminal();
        });
        return this;
    }

    @Override
    public ReadStream<Buffer> pause() {
        onContext(() -> {
            flowChosen = true;
            demand = 0;
        });
        return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
        return fetch(Long.MAX_VALUE);
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
        if (amount <= 0) {
            return this;
        }
        onContext(() -> {
            flowChosen = true;
            demand = demand + amount < 0 ? Long.MAX_VALUE : demand + amount;
            drain();
        });
        return this;
    }

    // --- stream side ---

    /** The response body ended: the stream completes once the queued chunks have been delivered. */
    void upstreamEnded() {
        ended = true;
        drain();
    }

    /**
     * Ends the body: drops what was not delivered and tells the consumer's end or exception handler.
     *
     * @param failure why the stream ended, or {@code null} when the whole body was delivered
     */
    void close(@Nullable Throwable failure) {
        if (closed) {
            return;
        }
        closed = true;
        closeFailure = failure;
        queue.clear();
        queuedBytes = 0;
        tellTerminal();
    }

    /**
     * Tells the end or exception handler how the body ended, once, as soon as the body has ended and such a
     * handler is set: a handler set after the end still hears it.
     */
    private void tellTerminal() {
        if (!closed || terminalTold) {
            return;
        }
        if (closeFailure == null && endHandler != null) {
            terminalTold = true;
            endHandler.handle(null);
        } else if (closeFailure != null && exceptionHandler != null) {
            terminalTold = true;
            exceptionHandler.handle(closeFailure);
        }
    }

    private void receive(Buffer chunk) {
        owner.activity();
        if (closed) {
            return;
        }
        queue.add(chunk);
        queuedBytes += chunk.length();
        if (queuedBytes > HIGH_WATER_BYTES) {
            full = true;
        }
        drain();
    }

    private void drain() {
        if (draining || closed) {
            return;
        }
        draining = true;
        try {
            boolean released;
            do {
                while (!closed && demand > 0 && handler != null && !queue.isEmpty()) {
                    Buffer chunk = queue.poll();
                    queuedBytes -= chunk.length();
                    if (demand != Long.MAX_VALUE) {
                        demand--;
                    }
                    handler.handle(chunk);
                }
                released = releaseWriter();
            } while (released && !closed);
            if (ended && !closed && queue.isEmpty()) {
                owner.bodyConsumed();
            }
        } finally {
            draining = false;
        }
    }

    /** Tells the response it may write again once the queue is down to half its bound. */
    private boolean releaseWriter() {
        if (!full || closed || queuedBytes > HIGH_WATER_BYTES / 2) {
            return false;
        }
        full = false;
        Handler<Void> drained = drainHandler;
        if (drained == null) {
            return false;
        }
        drained.handle(null);
        return true;
    }

    private void onContext(Runnable action) {
        if (Vertx.currentContext() == context) {
            action.run();
        } else {
            context.runOnContext(ignored -> action.run());
        }
    }

    /** What Vert.x pipes the response body into. */
    private final class Sink implements BodyStream<Void> {

        private final Promise<Void> result = Promise.promise();

        @Override
        public Future<Void> result() {
            return result.future();
        }

        @Override
        public void handle(Throwable failure) {
            // a failed response reaches the stream through the exchange tracker
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            receive(data);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            result.tryComplete();
            return Future.succeededFuture();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return full;
        }

        @Override
        public WriteStream<Buffer> drainHandler(@Nullable Handler<Void> handler) {
            drainHandler = handler;
            return this;
        }
    }
}
