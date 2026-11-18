// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.core.streams.ReadStream;

/**
 * A response whose head has been accepted and whose body is still being received, returned by
 * {@link VertiqueHttpClient#stream}.
 *
 * <p>The head is available before any body byte. The body is read through {@link #body()}, which stays
 * paused until a handler is set and passes back-pressure to the connection. The end or the failure of
 * the exchange is reported by {@link #completion()}, separately from the head. A stream that is not
 * consumed holds its connection: call {@link #cancel()} for a stream that is dropped.
 *
 * <p>Handlers run on the Vert.x context of the call; the stream's methods may be called from any
 * thread.
 */
public interface HttpStream {

    /**
     * Returns the status, message, version and headers of the accepted response head.
     *
     * @return the response head, a snapshot that never changes
     */
    HttpResponseHead head();

    /**
     * Returns the response body. It is paused until a handler is set; pausing it stops reading from the
     * connection once the stream's small buffer is full.
     *
     * @return the body stream
     */
    ReadStream<Buffer> body();

    /**
     * Returns the future that reports how the stream ended. It succeeds when the whole body has been
     * passed to the body handler; it fails with the native throwable of a failed exchange, with a
     * {@link java.util.concurrent.TimeoutException} when the stream idle timeout expired, with
     * {@link dev.vertique.http.client.exception.HttpExchangeCancelledException} after {@link #cancel()}
     * and with {@link dev.vertique.http.client.exception.HttpClientClosedException} when the client
     * closed.
     *
     * @return the terminal future
     */
    Future<Void> completion();

    /**
     * Cancels the stream: resets the exchange and fails {@link #completion()} with
     * {@link dev.vertique.http.client.exception.HttpExchangeCancelledException}. Cancelling a stream that
     * has ended, or cancelling again, does nothing.
     */
    void cancel();
}
