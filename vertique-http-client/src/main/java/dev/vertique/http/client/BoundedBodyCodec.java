// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.codec.spi.BodyStream;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * INTERNAL: a {@link BodyCodec} that bounds the bytes of the response body whatever codec the caller
 * chose. Its stream wraps the stream of the wrapped codec and counts the bytes passed on; the chunk
 * that would take the total over the limit is not passed on, the overrun callback runs once and
 * every later chunk is refused.
 *
 * @param <T> the type the wrapped codec decodes to
 */
final class BoundedBodyCodec<T> implements BodyCodec<T> {

    private final BodyCodec<T> delegate;
    private final long limitBytes;
    private final Runnable onOverrun;
    private final @Nullable Consumer<Buffer> onCopied;

    /**
     * Creates a bounded codec.
     *
     * @param delegate the codec whose stream is wrapped
     * @param limitBytes the most body bytes passed on, above zero
     * @param onOverrun runs once, when a chunk would exceed the limit; it resets the exchange
     * @param onCopied receives a copy of every byte passed on once the body completed, or {@code null} to
     *     copy nothing
     */
    BoundedBodyCodec(BodyCodec<T> delegate, long limitBytes, Runnable onOverrun, @Nullable Consumer<Buffer> onCopied) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.limitBytes = limitBytes;
        this.onOverrun = Objects.requireNonNull(onOverrun, "onOverrun");
        this.onCopied = onCopied;
    }

    @Override
    public BodyStream<T> stream() throws Exception {
        return new CountingStream<>(delegate.stream(), limitBytes, onOverrun, onCopied);
    }

    /** The counting delegate: passes bytes on up to the limit and never buffers. */
    private static final class CountingStream<T> implements BodyStream<T> {

        private static final String REFUSED = "The response body exceeds the configured byte bound";

        private final BodyStream<T> delegate;
        private final long limitBytes;
        private final Runnable onOverrun;
        private final @Nullable Consumer<Buffer> onCopied;
        private final @Nullable Buffer copy;
        private long received;
        private boolean overrun;

        private CountingStream(
                BodyStream<T> delegate, long limitBytes, Runnable onOverrun, @Nullable Consumer<Buffer> onCopied) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
            this.onOverrun = onOverrun;
            this.onCopied = onCopied;
            this.copy = onCopied == null ? null : Buffer.buffer();
        }

        @Override
        public Future<T> result() {
            return delegate.result();
        }

        @Override
        public void handle(Throwable failure) {
            delegate.handle(failure);
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            delegate.exceptionHandler(handler);
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            if (overrun) {
                return Future.failedFuture(REFUSED);
            }
            received += data.length();
            if (received > limitBytes) {
                overrun = true;
                onOverrun.run();
                return Future.failedFuture(REFUSED);
            }
            if (copy != null) {
                copy.appendBuffer(data);
            }
            return delegate.write(data);
        }

        @Override
        public Future<Void> end() {
            if (overrun) {
                return Future.failedFuture(REFUSED);
            }
            if (onCopied != null && copy != null) {
                onCopied.accept(copy);
            }
            return delegate.end();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            delegate.setWriteQueueMaxSize(maxSize);
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return delegate.writeQueueFull();
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            delegate.drainHandler(handler);
            return this;
        }
    }
}
