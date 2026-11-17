// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The lifetime of one scoped {@code WebClient}: it tracks the exchanges started through it and, when
 * it ends, resets every exchange that is still open and refuses new ones.
 */
final class AttemptScope {

    /** Failure message of a request sent after the scope ended. */
    static final String ENDED = "The web client is no longer valid because its attempt has ended";

    private final Set<Exchange> open = new LinkedHashSet<>();
    private boolean ended;

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

    private synchronized void finished(Exchange exchange) {
        open.remove(exchange);
    }

    /** Ends the scope and resets every exchange still open. Calling it again does nothing. */
    void end() {
        List<Exchange> toReset;
        synchronized (this) {
            if (ended) {
                return;
            }
            ended = true;
            toReset = new ArrayList<>(open);
            open.clear();
        }
        if (toReset.isEmpty()) {
            return;
        }
        Throwable cause = new IllegalStateException(ENDED);
        for (Exchange exchange : toReset) {
            exchange.reset(cause);
        }
    }

    /** One exchange of the scope: the request before the head and the response after it. */
    static final class Exchange {

        private final AttemptScope scope;
        private volatile HttpClientRequest request;
        private volatile HttpClientResponse response;
        private volatile boolean expired;
        private volatile boolean done;
        private volatile Runnable onFinish;

        private Exchange(AttemptScope scope) {
            this.scope = scope;
        }

        /**
         * Records the request Vert.x is about to send.
         *
         * @param sent the client request
         * @return {@code false} when the scope has ended, so the request must not be sent
         */
        boolean attach(HttpClientRequest sent) {
            request = sent;
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
            done = true;
            Runnable cleanup = onFinish;
            if (cleanup != null) {
                cleanup.run();
            }
            scope.finished(this);
        }

        /** Registers what runs when the exchange completes or fails. */
        void onFinish(Runnable cleanup) {
            onFinish = cleanup;
        }

        boolean isExpired() {
            return expired;
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
