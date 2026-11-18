// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.exception.SseProtocolException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.codec.SseEvent;
import io.vertx.ext.web.codec.spi.BodyStream;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The stream of an accepted response head. It owns the exchange from the head on: the attempt that
 * obtained the head no longer resets or reports it. The stream ends exactly once, through
 * {@link #terminate(Throwable)}: it tells the consumer, resets an exchange that is still open, reports
 * the exchange to the client's interceptors and settles {@link #completion()}.
 *
 * <p>State is confined to the stream's Vert.x context. The entries from other threads are
 * {@link #cancel()} and {@link #cancel(Throwable)}, which fix the reason at once and hop onto that
 * context.
 */
final class ActiveStream implements HttpStream {

    /** Message of the failure a stream idle timeout ends the stream with. */
    static final String IDLE = "The HTTP stream timed out waiting for data";

    private static final String EVENT_STREAM = "text/event-stream";

    private final Vertx vertx;
    private final Context context;
    private final HttpResponseHead head;
    private final AttemptScope.Exchange exchange;
    private final long idleTimeoutMs;
    private final int maxEventBytes;
    private final Consumer<ActiveStream> onTerminal;
    private final StreamBody body;
    private final Promise<Void> completion = Promise.promise();
    private final AtomicReference<Throwable> reason = new AtomicReference<>();
    private long timerId = -1;
    private long lastActivityNanos;
    private boolean bodyEnded;
    private boolean terminal;
    private @Nullable SseEventStream events;

    /**
     * Creates the stream of an accepted head.
     *
     * @param vertx the Vert.x instance that runs the idle timer
     * @param context the context the stream's handlers run on
     * @param response the response whose head was accepted
     * @param exchange the exchange the stream takes over
     * @param idleTimeoutMs how long the body may go without a byte, {@code 0} for no bound
     * @param maxEventBytes the most raw bytes of one server-sent event, above 0
     * @param onTerminal runs once when the stream has ended
     */
    ActiveStream(
            Vertx vertx,
            Context context,
            HttpClientResponse response,
            AttemptScope.Exchange exchange,
            long idleTimeoutMs,
            int maxEventBytes,
            Consumer<ActiveStream> onTerminal) {
        this.vertx = vertx;
        this.context = context;
        this.head = new StreamHead(
                response.version(),
                response.statusCode(),
                response.statusMessage(),
                response.headers().copy(false),
                List.copyOf(response.cookies()));
        this.exchange = exchange;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxEventBytes = maxEventBytes;
        this.onTerminal = onTerminal;
        this.body = new StreamBody(this, context);
        this.lastActivityNanos = System.nanoTime();
    }

    @Override
    public HttpResponseHead head() {
        return head;
    }

    @Override
    public ReadStream<Buffer> body() {
        return body;
    }

    @Override
    public synchronized ReadStream<SseEvent> events() {
        if (events == null) {
            String contentType = head.getHeader("Content-Type");
            boolean accepted = EVENT_STREAM.equals(mediaType(contentType));
            events = new SseEventStream(this, body, context, maxEventBytes, accepted);
            if (!accepted) {
                cancel(new SseProtocolException(contentType));
            }
        }
        return events;
    }

    @Override
    public Optional<String> lastEventId() {
        SseEventStream current;
        synchronized (this) {
            current = events;
        }
        return current == null ? Optional.empty() : current.lastEventId();
    }

    @Override
    public Future<Void> completion() {
        return completion.future();
    }

    @Override
    public void cancel() {
        cancel(new HttpExchangeCancelledException());
    }

    /**
     * Cancels the stream from any thread with {@code cause}. The reason is fixed at once, so a failure the
     * cancellation itself causes never replaces it; later cancellations do nothing.
     */
    void cancel(Throwable cause) {
        if (!reason.compareAndSet(null, cause)) {
            return;
        }
        context.runOnContext(ignored -> terminate(cause));
    }

    /** The reason a cancellation fixed, or {@code null} when none did. */
    @Nullable
    Throwable cancelReason() {
        return reason.get();
    }

    /** The write side of the body the response is piped into. */
    BodyStream<Void> sink() {
        return body.sink();
    }

    /** Starts the idle timer. */
    void start() {
        if (terminal) {
            return;
        }
        lastActivityNanos = System.nanoTime();
        if (idleTimeoutMs > 0) {
            armIdleTimer(idleTimeoutMs);
        }
    }

    /** A body byte arrived: the stream is not idle. */
    void activity() {
        lastActivityNanos = System.nanoTime();
    }

    /** The response body ended; the stream completes once the consumer has received every byte. */
    void bodyEnded() {
        if (!terminal) {
            bodyEnded = true;
            body.upstreamEnded();
        }
    }

    /** The consumer received every byte of an ended body. */
    void bodyConsumed() {
        SseEventStream reading;
        synchronized (this) {
            reading = events;
        }
        if (reading == null) {
            terminate(null);
        } else {
            reading.upstreamDone();
        }
    }

    /** The number of parsed events waiting for the consumer, 0 when events are not read. Call on the context. */
    int heldEvents() {
        SseEventStream reading;
        synchronized (this) {
            reading = events;
        }
        return reading == null ? 0 : reading.heldEvents();
    }

    /** Whether the stream has ended. */
    boolean ended() {
        return terminal;
    }

    /** The exchange failed after the head with {@code failure}. */
    void failed(Throwable failure) {
        terminate(failure);
    }

    /**
     * Ends the stream once. A failure resets an exchange that is still open, unless a cancellation
     * already fixed another reason, which then replaces it.
     *
     * @param cause why the stream ended, or {@code null} when the whole body was delivered
     */
    void terminate(@Nullable Throwable cause) {
        if (terminal) {
            return;
        }
        terminal = true;
        cancelTimer();
        Throwable fixed = cause == null ? null : reason.get() != null ? reason.get() : cause;
        if (fixed != null && !bodyEnded) {
            exchange.reset(fixed);
        }
        try {
            body.close(fixed);
        } finally {
            exchange.complete(fixed);
            onTerminal.accept(this);
            if (fixed == null) {
                completion.complete();
            } else {
                completion.fail(fixed);
            }
        }
    }

    @Nullable
    private static String mediaType(@Nullable String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private void armIdleTimer(long delayMs) {
        timerId = vertx.setTimer(Math.max(1, delayMs), id -> onContext(this::idleTimerFired));
    }

    private void idleTimerFired() {
        if (terminal) {
            return;
        }
        long idleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivityNanos);
        if (idleMs < idleTimeoutMs) {
            armIdleTimer(idleTimeoutMs - idleMs);
            return;
        }
        terminate(new TimeoutException(IDLE));
    }

    private void cancelTimer() {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    private void onContext(Runnable action) {
        if (Vertx.currentContext() == context) {
            action.run();
        } else {
            context.runOnContext(ignored -> action.run());
        }
    }

    /** The accepted head as a response-head snapshot that never changes. */
    private record StreamHead(
            HttpVersion version, int statusCode, String statusMessage, MultiMap headers, List<String> cookies)
            implements HttpResponseHead {

        @Override
        public String getHeader(String headerName) {
            return headers.get(headerName);
        }

        @Override
        public String getHeader(CharSequence headerName) {
            return headers.get(headerName);
        }
    }
}
