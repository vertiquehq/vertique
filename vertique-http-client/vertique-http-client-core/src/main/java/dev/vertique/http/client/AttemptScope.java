// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientAttemptTarget;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The lifetime of one scoped {@code WebClient}: it tracks the exchanges started through it and, when
 * it ends, resets every exchange that is still open and refuses new ones.
 *
 * <p>The scope also tells the client's interceptors how each of its exchanges ended, exactly once per
 * exchange. A scope that belongs to one attempt of a call defers the notification of an exchange that
 * completed with a response until the attempt ends, because only then is a status expectation the
 * attempt applied known; every other ending is notified when it happens. The scope also remembers the
 * methods of its exchanges and the failures that were refused before a request was written, which the
 * client's resilience classification reads.
 */
final class AttemptScope {

    /** Failure message of a request sent after the scope ended. */
    static final String ENDED = "The web client is no longer valid because its attempt has ended";

    private final Set<Exchange> open = new LinkedHashSet<>();
    private final List<Exchange> awaiting = new ArrayList<>();
    private final HttpClientInterceptors interceptors;
    private final @Nullable String callId;
    private final int attemptOrdinal;
    private final Set<Throwable> refusedBeforeSend = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean nonIdempotent;
    private boolean requestCreated;
    private boolean ended;

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

    /** Records the method of an exchange of this scope. */
    synchronized void noteMethod(String method) {
        if (!isIdempotent(method)) {
            nonIdempotent = true;
        }
    }

    /** Whether some exchange of this scope used a method that is not idempotent. */
    synchronized boolean hasNonIdempotentExchange() {
        return nonIdempotent;
    }

    static boolean isIdempotent(String method) {
        return switch (method) {
            case "GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE" -> true;
            default -> false;
        };
    }

    /**
     * Whether {@code failure} (or a failure in its cause chain) refused an exchange before the first hop's
     * request was written.
     */
    synchronized boolean wasRefusedBeforeSend(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (refusedBeforeSend.contains(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    /**
     * Records that the attempt's function threw {@code failure} itself, which refuses the request before
     * any exchange sent a request when it is an illegal-argument failure such as an invalid header.
     */
    synchronized void refusedWithoutSending(Throwable failure) {
        if (!requestCreated) {
            refusedBeforeSend(failure, true);
        }
    }

    private synchronized void requestCreated() {
        requestCreated = true;
    }

    /**
     * Records a refusal of the first hop. An illegal-argument failure counts only when no request was
     * created yet; the other refusals count while the first hop has not been answered.
     */
    private synchronized void refusedBeforeSend(Throwable failure, boolean noRequestYet) {
        if (failure instanceof HttpCredentialException
                || failure instanceof HttpEgressDeniedException
                || failure instanceof IllegalArgumentException && noRequestYet
                || failure instanceof HttpBodyLimitException limit
                        && limit.direction() == HttpBodyLimitException.Direction.REQUEST) {
            refusedBeforeSend.add(failure);
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
            toReset = new ArrayList<>(open);
            toNotify = new ArrayList<>(awaiting);
            open.clear();
            awaiting.clear();
        }
        for (Exchange exchange : toNotify) {
            exchange.complete(exchange.refusedBy(callFailure));
        }
        Throwable cause = new IllegalStateException(ENDED);
        Throwable resetCause = isTimeout(callFailure) ? callFailure : cause;
        for (Exchange exchange : toReset) {
            exchange.complete(callFailure != null ? callFailure : cause);
            exchange.reset(resetCause);
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
        private volatile int hops;
        private volatile boolean expired;
        private volatile boolean done;
        private volatile Runnable onFinish;

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

        /** Records the method of the exchange's request. */
        void method(String method) {
            scope.noteMethod(method);
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
            hops = hops + 1;
            scope.requestCreated();
            head = null; // a followed redirect starts a new hop
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
            markEnded();
            done = true;
            Runnable cleanup = onFinish;
            if (cleanup != null) {
                cleanup.run();
            }
            scope.responded(this);
        }

        /** The exchange failed with {@code failure}: it is over and the interceptors are told. */
        void fail(@Nullable Throwable failure) {
            if (failure != null && hops <= 1 && response == null) {
                scope.refusedBeforeSend(failure, hops == 0);
            }
            finish();
            complete(failure != null ? failure : new IllegalStateException(ENDED));
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
            Throwable reported = error == null && received == null ? new IllegalStateException(ENDED) : error;
            long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos));
            interceptors.completed(
                    request,
                    received,
                    new HttpClientAttemptCompletion(
                            status, reported, callId, scope.attemptOrdinal, durationMs, endedAt, target));
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
