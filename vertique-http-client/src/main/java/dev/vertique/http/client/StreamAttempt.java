// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpStatusRejectedException;
import io.vertx.core.Context;
import io.vertx.core.Expectation;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.impl.HttpContext;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * One attempt of a stream call: it decides at the response head whether the head is accepted. An accepted
 * head hands the exchange to an {@link ActiveStream}, lets the response body flow into it and completes
 * the attempt's head future with the stream. A refused head is read for a short excerpt, reset and fails
 * the attempt with the expectation's failure.
 */
final class StreamAttempt {

    /** The most body bytes read from a refused head. */
    private static final int MAX_EXCERPT_BYTES = 2048;

    private final Vertx vertx;
    private final AttemptScope scope;
    private final Expectation<HttpResponseHead> expectation;
    private final long idleTimeoutMs;
    private final int maxEventBytes;
    private final StreamCodec codec;
    private final Consumer<ActiveStream> onOpened;
    private final Consumer<ActiveStream> onTerminal;
    private final Promise<HttpStream> head = Promise.promise();

    /**
     * Creates the attempt.
     *
     * @param vertx the Vert.x instance that runs the timers
     * @param scope the scope of the attempt
     * @param expectation decides whether a head is accepted
     * @param idleTimeoutMs the stream idle timeout, {@code 0} for none
     * @param maxEventBytes the most raw bytes of one server-sent event, above 0
     * @param codec the body codec of the attempt's request
     * @param onOpened runs for each stream an accepted head creates, before it is delivered
     * @param onTerminal runs once for each such stream when it has ended
     */
    StreamAttempt(
            Vertx vertx,
            AttemptScope scope,
            Expectation<HttpResponseHead> expectation,
            long idleTimeoutMs,
            int maxEventBytes,
            StreamCodec codec,
            Consumer<ActiveStream> onOpened,
            Consumer<ActiveStream> onTerminal) {
        this.vertx = vertx;
        this.scope = scope;
        this.expectation = expectation;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxEventBytes = maxEventBytes;
        this.codec = codec;
        this.onOpened = onOpened;
        this.onTerminal = onTerminal;
    }

    /** The future of the accepted head's stream. */
    Future<HttpStream> head() {
        return head.future();
    }

    /** Whether {@code request} is the request of this stream, as opposed to another sent by the same web client. */
    boolean owns(HttpRequest<?> request) {
        return request.bodyCodec() == codec;
    }

    /** The request failed before a head was accepted. */
    void failHead(Throwable failure) {
        head.tryFail(failure);
    }

    /**
     * Decides the head of the response of {@code ctx}. The exchange tracker calls it in
     * {@code RECEIVE_RESPONSE}; it resumes or fails the context itself.
     */
    void receive(HttpContext<?> ctx, AttemptScope.Exchange exchange) {
        HttpClientResponse response = ctx.clientResponse();
        Context context = Vertx.currentContext();
        ActiveStream.StreamHead snapshot = ActiveStream.StreamHead.of(response);
        boolean accepted;
        Throwable broken = null;
        try {
            accepted = expectation.test(snapshot);
        } catch (RuntimeException failure) {
            accepted = false;
            broken = failure;
        }
        if (!accepted) {
            if (broken != null) {
                refuse(ctx, response, broken);
            } else {
                new ExcerptReader(ctx, exchange, response, snapshot, context).read();
            }
            return;
        }
        ActiveStream stream =
                new ActiveStream(vertx, context, response, exchange, idleTimeoutMs, maxEventBytes, onTerminal);
        codec.bind(stream.sink());
        exchange.adopt(stream);
        onOpened.accept(stream);
        ctx.next();
        // Deliver after the chain resumed, so the body is piped before any consumer reacts to the head.
        context.runOnContext(ignored -> deliver(stream));
    }

    private void deliver(ActiveStream stream) {
        if (scope.hasEnded()) {
            Throwable reason = scope.endReason();
            stream.terminate(reason != null ? reason : scope.endedFailure());
            return;
        }
        Throwable cancelled = stream.cancelReason();
        if (cancelled != null && stream.completion().failed()) {
            head.tryFail(cancelled);
            return;
        }
        stream.start();
        head.tryComplete(stream);
    }

    private static void refuse(HttpContext<?> ctx, HttpClientResponse response, Throwable cause) {
        response.request().reset(0, cause);
        ctx.fail(cause);
    }

    /**
     * Reads the first chunk of a refused head's body (at most 2048 bytes), then resets the exchange and fails
     * the attempt.
     */
    private final class ExcerptReader {

        private final HttpContext<?> ctx;
        private final AttemptScope.Exchange exchange;
        private final HttpClientResponse response;
        private final HttpResponseHead snapshot;
        private final Context context;
        private final Buffer bytes = Buffer.buffer();
        private long timerId = -1;
        private boolean finished;

        private ExcerptReader(
                HttpContext<?> ctx,
                AttemptScope.Exchange exchange,
                HttpClientResponse response,
                HttpResponseHead snapshot,
                Context context) {
            this.ctx = ctx;
            this.snapshot = snapshot;
            this.exchange = exchange;
            this.response = response;
            this.context = context;
        }

        private void read() {
            response.handler(chunk -> {
                int room = MAX_EXCERPT_BYTES - bytes.length();
                if (room > 0) {
                    bytes.appendBuffer(chunk, 0, Math.min(room, chunk.length()));
                }
                // Reset at the first chunk: once a response has ended, resetting it no longer closes the
                // connection, and a refused head must not leave its exchange open.
                finish();
            });
            response.endHandler(ignored -> finish());
            response.exceptionHandler(ignored -> finish());
            response.resume();
            if (idleTimeoutMs > 0) {
                timerId = vertx.setTimer(idleTimeoutMs, id -> context.runOnContext(ignored -> finish()));
            }
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (timerId >= 0) {
                vertx.cancelTimer(timerId);
            }
            response.handler(null);
            if (exchange.isAbandoned()) {
                // the attempt ended or timed out while the excerpt was read: that is the failure, not a rejection
                refuse(
                        ctx,
                        response,
                        exchange.isExpired() ? new TimeoutException(CallExecution.TIMED_OUT) : scope.endedFailure());
                return;
            }
            Throwable cause;
            try {
                cause = expectation.describe(new ExcerptHead(snapshot, bytes));
            } catch (RuntimeException failure) {
                cause = failure;
            }
            if (cause == null) {
                cause = new HttpStatusRejectedException(response.statusCode(), response.headers(), null, bytes);
            }
            refuse(ctx, response, cause);
        }
    }
}
