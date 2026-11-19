// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientAttemptTarget;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The lifetime of one scoped {@code WebClient}: it tracks the exchanges started through it and, when
 * it ends, resets every exchange that is still open and refuses new ones.
 *
 * <p>The scope also tells the client's interceptors how each of its exchanges ended, exactly once per
 * exchange. A scope that belongs to one attempt of a call defers the notification of an exchange that
 * completed with a response until the attempt ends, because only then is a status expectation the
 * attempt applied known; every other ending is notified when it happens. The scope also remembers the
 * failures that were refused before a request was written, which a call's pipeline execution reads.
 */
final class AttemptScope {

    /** Failure message of a request sent after the scope ended. */
    static final String ENDED_MESSAGE = "The web client is no longer valid because its attempt has ended";

    private final Set<Exchange> open = new LinkedHashSet<>();
    private final List<Exchange> awaiting = new ArrayList<>();
    private final HttpClientInterceptors interceptors;
    private final @Nullable String callId;
    private final int attemptOrdinal;
    /** Typed refusals recorded while nothing was sent, with whether the refusing exchange created a request. */
    private final Map<Throwable, Boolean> refusedBeforeSend = new IdentityHashMap<>();

    private boolean closedByClient;
    private @Nullable Throwable endReason;
    private int attachedExchanges;
    private boolean ended;
    private volatile @Nullable StreamAttempt streamAttempt;

    /**
     * Creates a scope.
     *
     * @param interceptors the interceptors told how the scope's exchanges ended
     * @param callId the id shared by the exchanges of one call, or {@code null} for a scope whose every
     *     exchange is a call of its own
     * @param attemptOrdinal the 1-based ordinal of the attempt within its call, reported to interceptors
     */
    AttemptScope(HttpClientInterceptors interceptors, @Nullable String callId, int attemptOrdinal) {
        this.interceptors = interceptors;
        this.callId = callId;
        this.attemptOrdinal = attemptOrdinal;
    }

    HttpClientInterceptors interceptors() {
        return interceptors;
    }

    /** Marks the scope as the attempt of a stream call, which takes over the exchange at its accepted head. */
    void streamAttempt(StreamAttempt attempt) {
        this.streamAttempt = attempt;
    }

    /** The stream attempt this scope belongs to, or {@code null} for any other scope. */
    @Nullable
    StreamAttempt streamAttempt() {
        return streamAttempt;
    }

    /**
     * Starts tracking an exchange.
     *
     * @return the exchange, or {@code null} when the scope has ended
     */
    synchronized Exchange begin() {
        if (ended) {
            return null;
        }
        Exchange exchange = new Exchange(this);
        open.add(exchange);
        return exchange;
    }

    synchronized boolean hasEnded() {
        return ended;
    }

    /** The failure the scope ended with: the call's failure (cancel, timeout, close), or {@code null}. */
    @Nullable
    synchronized Throwable endReason() {
        return endReason;
    }

    /** The failure a request made after the scope ended fails with. */
    synchronized Throwable endedFailure() {
        return closedByClient ? new HttpClientClosedException() : new IllegalStateException(ENDED_MESSAGE);
    }

    /**
     * The raw timeout a request that is refused after the scope ended on a timeout fails with, or
     * {@code null} when the scope ended any other way or is still open.
     */
    @Nullable
    synchronized Throwable timeoutCause() {
        if (!ended || !isTimeout(endReason)) {
            return null;
        }
        return endReason instanceof TimeoutException ? endReason : new TimeoutException(CallExecution.TIMED_OUT);
    }

    /**
     * Whether {@code failure} (or a failure in its cause chain) is a typed refusal recorded while nothing was
     * sent, and still no other exchange of this attempt has created a request: an exchange started on another
     * context after the refusal was recorded may have sent one since.
     */
    synchronized boolean wasRefusedBeforeSend(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            Boolean sentItself = refusedBeforeSend.get(current);
            if (sentItself != null) {
                return attachedExchanges - (sentItself ? 1 : 0) == 0;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    /** Counts an exchange that created its first request. */
    private synchronized void exchangeAttached() {
        attachedExchanges++;
    }

    /**
     * Records a typed governance refusal: a credential failure, an egress denial or a refused request body
     * counts only while no other exchange of this attempt has created a request, so an attempt that already
     * sent something fails like any other attempt.
     *
     * @param sentItself whether the failing exchange created a request itself
     */
    private synchronized void refusedBeforeSend(Throwable failure, boolean sentItself) {
        if (attachedExchanges - (sentItself ? 1 : 0) > 0) {
            return;
        }
        if (failure instanceof HttpCredentialException
                || failure instanceof HttpEgressDeniedException
                || failure instanceof HttpBodyLimitException limit
                        && limit.direction() == HttpBodyLimitException.Direction.REQUEST) {
            refusedBeforeSend.put(failure, sentItself);
        }
    }

    private synchronized void finished(Exchange exchange) {
        open.remove(exchange);
    }

    /**
     * Records an exchange that completed with a response. Its notification waits for the end of a call's
     * scope and is made at once for any other scope.
     */
    private void responded(Exchange exchange) {
        boolean deferred = false;
        synchronized (this) {
            open.remove(exchange);
            if (callId != null && !ended) {
                awaiting.add(exchange);
                deferred = true;
            }
        }
        if (!deferred) {
            exchange.complete(null);
        }
    }

    /**
     * Ends the scope: tells the interceptors how every exchange that has not been told ended, then resets
     * every exchange still open. Calling it again does nothing.
     *
     * @param callFailure the failure the call ends with, or {@code null} when it succeeded
     */
    void end(@Nullable Throwable callFailure) {
        List<Exchange> toReset;
        List<Exchange> toNotify;
        synchronized (this) {
            if (ended) {
                return;
            }
            ended = true;
            closedByClient = callFailure instanceof HttpClientClosedException;
            endReason = callFailure;
            toReset = new ArrayList<>(open);
            toNotify = new ArrayList<>(awaiting);
            open.clear();
            awaiting.clear();
        }
        for (Exchange exchange : toNotify) {
            exchange.complete(exchange.refusedBy(callFailure));
        }
        Throwable cause = new IllegalStateException(ENDED_MESSAGE);
        Throwable resetCause =
                isTimeout(callFailure) || callFailure instanceof HttpClientClosedException ? callFailure : cause;
        Throwable timeout = timeoutCause();
        for (Exchange exchange : toReset) {
            exchange.complete(callFailure != null ? callFailure : cause);
            exchange.reset(resetCause);
            if (timeout != null) {
                exchange.failBeforeRequest(timeout);
            }
        }
    }

    private static boolean isTimeout(@Nullable Throwable failure) {
        return failure instanceof TimeoutException || failure instanceof ResilienceTimeoutException;
    }

    /** One exchange of the scope: the request before the head and the response after it. */
    static final class Exchange {

        private final AttemptScope scope;
        private final String callId;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean notified = new AtomicBoolean();
        private volatile HttpClientRequestContext snapshot;
        private volatile HttpClientResponseHead head;
        private volatile HttpClientAttemptTarget target = new HttpClientAttemptTarget(null, null, -1);
        private volatile long endNanos = -1;
        private volatile Instant endedAt;
        private volatile HttpClientRequest request;
        private volatile HttpClientResponse response;
        private final AtomicInteger hops = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private volatile boolean expired;
        private volatile boolean done;
        private volatile Runnable onFinish;
        private volatile @Nullable ActiveStream stream;
        private volatile @Nullable Consumer<Throwable> beforeRequest;
        private volatile boolean capturing;
        private volatile PayloadSource requestBody = PayloadSources.absent();
        private volatile boolean responseDone;
        private volatile boolean responseValue;
        private volatile CodecKind codecKind = CodecKind.DECODING;
        private volatile @Nullable Buffer responseBytes;
        private volatile @Nullable Buffer copiedBytes;

        private Exchange(AttemptScope scope) {
            this.scope = scope;
            this.callId =
                    scope.callId != null ? scope.callId : UUID.randomUUID().toString();
        }

        /** The id of the call this exchange belongs to. */
        String callId() {
            return callId;
        }

        /** Records the request snapshot the interceptors are shown when the exchange ends. */
        void snapshot(HttpClientRequestContext request) {
            snapshot = request;
        }

        /** Marks the exchange as one whose bodies the interceptors receive. */
        void capturing() {
            capturing = true;
        }

        /** Records the request body the interceptors are shown, as the request preparation measured it. */
        void requestBody(PayloadSource body) {
            if (!capturing) {
                return;
            }
            requestBody = body;
        }

        /** Records the bytes a bounded codec passed on for the hop being received; they are a copy. */
        void copiedBytes(Buffer bytes) {
            if (!capturing) {
                return;
            }
            copiedBytes = bytes;
        }

        /**
         * Records the final response, whose body the codec decoded: a {@code Buffer} is kept as it is, any other
         * value has a body only when a bounded codec copied its bytes.
         *
         * @param decoded the value the body codec produced, or {@code null}
         */
        void responseDecoded(@Nullable Object decoded) {
            if (!capturing) {
                return;
            }
            responseBytes = decoded instanceof Buffer buffer ? buffer : copiedBytes;
            responseValue = decoded != null;
            responseDone = true;
        }

        /** How the caller's body codec treats the bytes of the response body. */
        enum CodecKind {
            /** The default {@code Buffer} codec: the decoded value is the body. */
            BUFFER,
            /** A codec that pipes or discards the bytes, so no decoded value stands for the body. */
            DISCARDING,
            /** Any other codec: it decodes the bytes into a value. */
            DECODING
        }

        /** Records how the caller's body codec treats the response body. */
        void codecKind(CodecKind kind) {
            codecKind = kind;
        }

        /** Whether the interceptors receive this exchange's bodies. */
        boolean isCapturing() {
            return capturing;
        }

        /** The response body the interceptors are shown, from the head and the body the exchange completed with. */
        private PayloadSource responseBodyOf(@Nullable HttpClientResponseHead received) {
            if (received == null) {
                return PayloadSources.absent();
            }
            String contentType = received.headers().get("Content-Type");
            long declared = HeaderRules.declaredLength(received.headers().get("Content-Length"));
            if (stream != null || !responseDone) {
                return PayloadSources.streaming(contentType, declared);
            }
            if (bodilessAnswer(received)) {
                return PayloadSources.absent();
            }
            Buffer bytes = responseBytes;
            if (bytes != null) {
                if (bytes.length() > 0) {
                    return PayloadSources.buffered(bytes, contentType);
                }
                return declared > 0 ? PayloadSources.streaming(contentType, declared) : PayloadSources.absent();
            }
            if (codecKind == CodecKind.BUFFER && !responseValue || declared == 0) {
                return PayloadSources.absent();
            }
            return PayloadSources.streaming(contentType, declared);
        }

        /** Whether the answer cannot carry a body: a {@code HEAD} request, a 204 or a 304. */
        private boolean bodilessAnswer(HttpClientResponseHead received) {
            HttpClientRequestContext sent = snapshot;
            return received.statusCode() == 204
                    || received.statusCode() == 304
                    || sent != null && "HEAD".equalsIgnoreCase(sent.method());
        }

        /** Records that Vert.x creates a request for a hop: the first hop, or a followed redirect. */
        void hopCreated() {
            created.incrementAndGet();
        }

        /** Records the hop the exchange currently goes to. */
        void target(HttpClientAttemptTarget hop) {
            target = hop;
        }

        /** Records the head of the response that arrived. */
        void head(@Nullable HttpClientResponseHead received) {
            head = received;
        }

        /**
         * Records the request Vert.x is about to send.
         *
         * @param sent the client request
         * @return {@code false} when the scope has ended, so the request must not be sent
         */
        boolean attach(HttpClientRequest sent) {
            request = sent;
            if (hops.incrementAndGet() == 1) {
                scope.exchangeAttached();
            }
            head = null; // a followed redirect starts a new hop
            copiedBytes = null;
            responseBytes = null;
            responseValue = false;
            responseDone = false;
            response = null; // a followed redirect reuses the exchange: never reset an earlier hop's response
            return !expired && !scope.hasEnded();
        }

        /**
         * Records the response head.
         *
         * @param received the client response
         * @return {@code false} when the scope has ended, so the exchange must be reset
         */
        boolean attach(HttpClientResponse received) {
            response = received;
            return !expired && !scope.hasEnded();
        }

        /**
         * Hands the exchange to its stream at the accepted head: the exchange no longer expires and the
         * attempt's end neither resets nor reports it. The stream decides how it ends.
         */
        void adopt(ActiveStream owner) {
            stream = owner;
            done = true;
            Runnable cleanup = onFinish;
            if (cleanup != null) {
                cleanup.run();
            }
            scope.finished(this);
        }

        void finish() {
            markEnded();
            done = true;
            Runnable cleanup = onFinish;
            if (cleanup != null) {
                cleanup.run();
            }
            scope.finished(this);
        }

        /** The response was dispatched: the exchange is over and completed with a response. */
        void respond() {
            ActiveStream owner = stream;
            if (owner != null) {
                owner.bodyEnded();
                return;
            }
            markEnded();
            done = true;
            Runnable cleanup = onFinish;
            if (cleanup != null) {
                cleanup.run();
            }
            scope.responded(this);
        }

        /**
         * The exchange failed with {@code failure}: it is over and the interceptors are told.
         *
         * @param failure the failure, or {@code null}
         * @param answered whether a response had already arrived for the hop that failed, which a redirect
         *     rule refusal comes after
         */
        void fail(@Nullable Throwable failure, boolean answered) {
            ActiveStream owner = stream;
            if (owner != null) {
                owner.failed(failure != null ? failure : new IllegalStateException(ENDED_MESSAGE));
                return;
            }
            if (failure != null && created.get() <= 1 && hops.get() <= 1 && response == null && !answered) {
                scope.refusedBeforeSend(failure, hops.get() > 0);
            }
            finish();
            complete(failure != null ? failure : new IllegalStateException(ENDED_MESSAGE));
        }

        private void markEnded() {
            if (endedAt == null) {
                endNanos = System.nanoTime();
                endedAt = Instant.now();
            }
        }

        /** The failure a call's end reports for a completed exchange: a status rejection of its status. */
        @Nullable
        Throwable refusedBy(@Nullable Throwable callFailure) {
            HttpClientResponseHead received = head;
            if (callFailure instanceof HttpStatusRejectedException rejected
                    && received != null
                    && rejected.statusCode() == received.statusCode()) {
                return callFailure;
            }
            return null;
        }

        /**
         * Tells the interceptors how the exchange ended. Only the first call has an effect, whichever
         * path reaches it first.
         *
         * @param error the failure the exchange ended with, or {@code null} when it completed with a
         *     response
         */
        void complete(@Nullable Throwable error) {
            HttpClientInterceptors interceptors = scope.interceptors;
            HttpClientRequestContext request = snapshot;
            if (interceptors.isEmpty() || request == null || !notified.compareAndSet(false, true)) {
                return;
            }
            markEnded();
            HttpClientResponseHead received = head;
            Integer status = received == null ? null : received.statusCode();
            Throwable reported = error == null && received == null ? new IllegalStateException(ENDED_MESSAGE) : error;
            long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos));
            interceptors.completed(
                    request,
                    received,
                    new HttpClientAttemptCompletion(
                            status,
                            reported,
                            callId,
                            scope.attemptOrdinal,
                            durationMs,
                            endedAt,
                            target,
                            capturing ? requestBody : PayloadSources.absent(),
                            capturing ? responseBodyOf(received) : PayloadSources.absent()));
        }

        /** Registers what runs when the exchange completes or fails. */
        void onFinish(Runnable cleanup) {
            onFinish = cleanup;
        }

        boolean isExpired() {
            return expired;
        }

        /** Whether the exchange must not go on: it timed out or its scope has ended. */
        boolean isAbandoned() {
            return expired || scope.hasEnded();
        }

        /** Whether Vert.x has created a request for the exchange yet. */
        boolean hasRequest() {
            return request != null;
        }

        /**
         * Marks the exchange as timed out.
         *
         * @return {@code false} when it already completed or failed
         */
        boolean expire() {
            if (done) {
                return false;
            }
            expired = true;
            return true;
        }

        /** Registers how the exchange fails its context while Vert.x has not created a request for it. */
        void beforeRequest(Consumer<Throwable> failure) {
            beforeRequest = failure;
        }

        /**
         * Fails an exchange that has no request yet, because it still waits for a pooled connection or
         * for one to open, with {@code cause}. Does nothing once a request exists.
         */
        void failBeforeRequest(Throwable cause) {
            Consumer<Throwable> failure = beforeRequest;
            if (failure != null && request == null) {
                failure.accept(cause);
            }
        }

        /**
         * Resets the exchange: through the response after the head, through the request before it.
         *
         * @param cause the failure the exchange ends with
         */
        void reset(Throwable cause) {
            HttpClientResponse head = response;
            if (head != null) {
                head.request().reset(0, cause);
                return;
            }
            HttpClientRequest sent = request;
            if (sent != null) {
                sent.reset(0, cause);
            }
        }
    }
}
