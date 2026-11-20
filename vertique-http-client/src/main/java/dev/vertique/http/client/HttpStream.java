// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.codec.SseEvent;
import java.util.Optional;

/**
 * A response whose head has been accepted and whose body is still being received, returned by
 * {@link HttpClient#stream}.
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
     * Returns the server-sent events of the response body, parsed incrementally under the WHATWG event
     * stream grammar. Read the body through {@code events()} or through {@link #body()}, not both. The
     * events are paused until a handler is set; pausing them pauses the response once the events of one
     * chunk are held, so back-pressure reaches the connection. Every delivered event carries the last
     * event ID at its dispatch, and a comment line is consumed silently while still keeping the stream from
     * idling.
     *
     * <p>When the head's media type is not {@code text/event-stream}, the stream is cancelled at once:
     * {@link #completion()} fails with {@link dev.vertique.http.client.exception.SseProtocolException} and
     * the exchange is reset. More raw bytes than {@code maxEventBytes} in the event being assembled (finished
     * comment lines excluded, the current partial line included) fail {@link #completion()} with {@link dev.vertique.http.client.exception.HttpBodyLimitException} (direction
     * {@code EVENT}) and reset the exchange. {@link #completion()} succeeds once every event has been
     * delivered. Calling this method again returns the same stream.
     *
     * @return the event stream
     */
    ReadStream<SseEvent> events();

    /**
     * Returns the last event ID as it stood at the last event delivered to the consumer: the id of a
     * parsed event that is still waiting, or of an {@code id} line of an event that was never dispatched,
     * does not show. A consumer that resumes after a failure passes it to the server as
     * {@code Last-Event-ID}.
     *
     * @return the last event ID, or empty when no event stream is read or no id has been set
     */
    Optional<String> lastEventId();

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
