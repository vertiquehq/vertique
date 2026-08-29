// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Provides a non-blocking deadline fence for asynchronous Redis operations. */
public final class RedisDeadline {
    private static final String TIMEOUT_MESSAGE = "Redis operation exceeded deadline";

    private RedisDeadline() {}

    /**
     * Completes with the backend result unless the deadline is reached first.
     *
     * <p>The backend future is not canceled when the deadline is reached. Its late completion is
     * routed to the captured event-loop context and ignored after the returned future settles.
     *
     * @param vertx the Vert.x instance that owns the event-loop timer
     * @param backend the asynchronous backend operation to fence
     * @param deadline the positive maximum duration to wait
     * @param <T> the backend result type
     * @return a future settled by the backend or by the deadline timeout
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code deadline} is not positive
     */
    public static <T> Future<T> withDeadline(Vertx vertx, Future<T> backend, Duration deadline) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }

        long timeoutMs = timeoutMillis(deadline);
        Promise<T> promise = Promise.promise();
        vertx.runOnContext(ignored -> {
            Context capturedContext = Vertx.currentContext();
            long[] timerId = new long[1];
            timerId[0] = vertx.setTimer(timeoutMs, ignoredTimer -> {
                vertx.cancelTimer(timerId[0]);
                promise.tryFail(new TimeoutException(TIMEOUT_MESSAGE));
            });
            backend.onComplete(result -> capturedContext.runOnContext(ignoredBackend -> {
                vertx.cancelTimer(timerId[0]);
                if (result.succeeded()) {
                    promise.tryComplete(result.result());
                } else {
                    promise.tryFail(result.cause());
                }
            }));
        });
        return promise.future();
    }

    private static long timeoutMillis(Duration deadline) {
        long timeoutMs;
        try {
            timeoutMs = deadline.toMillis();
        } catch (ArithmeticException overflow) {
            timeoutMs = Long.MAX_VALUE;
        }
        return Math.max(1L, timeoutMs);
    }
}
