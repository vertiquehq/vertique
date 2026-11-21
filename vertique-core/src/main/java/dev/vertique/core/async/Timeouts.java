// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.async;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Shared timeout helpers for asynchronous work. */
public final class Timeouts {

    private Timeouts() {}

    /**
     * Completes when {@code condition} becomes true, polling it on a Vert.x periodic timer until the
     * timeout expires.
     *
     * <p>The condition is evaluated once immediately, on the calling thread, and then at each poll
     * interval; when the first evaluation is true no timer is started. The polling timer is cancelled
     * on success, timeout, or condition failure. The first poll at or after {@code timeoutMs} fails
     * the future with a {@link TimeoutException} without evaluating the condition again, so the
     * failure can arrive up to one poll interval after the deadline. A {@link RuntimeException}
     * thrown by the condition fails the future with that exception.
     *
     * @param vertx the Vert.x instance that owns the polling timer
     * @param condition the condition that signals the awaited work is complete
     * @param pollIntervalMs time between condition checks; must be positive
     * @param timeoutMs maximum time to wait; must be positive
     * @param operation short description included in timeout failures
     * @return a succeeded future when the condition becomes true, otherwise a failed future
     * @throws IllegalArgumentException if {@code pollIntervalMs} or {@code timeoutMs} is not positive
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Future<Void> awaitCondition(
            Vertx vertx, BooleanSupplier condition, long pollIntervalMs, long timeoutMs, String operation) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(condition, "condition");
        requirePositive(pollIntervalMs, "pollIntervalMs");
        requirePositive(timeoutMs, "timeoutMs");
        Objects.requireNonNull(operation, "operation");

        try {
            if (condition.getAsBoolean()) {
                return Future.succeededFuture();
            }
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }

        Promise<Void> promise = Promise.promise();
        AtomicBoolean settled = new AtomicBoolean();
        long startedNanos = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        vertx.setPeriodic(pollIntervalMs, timerId -> {
            if (settled.get()) {
                vertx.cancelTimer(timerId);
                return;
            }

            if (System.nanoTime() - startedNanos >= timeoutNanos) {
                if (settled.compareAndSet(false, true)) {
                    vertx.cancelTimer(timerId);
                    promise.fail(timeout(operation, timeoutMs));
                }
                return;
            }

            try {
                if (condition.getAsBoolean() && settled.compareAndSet(false, true)) {
                    vertx.cancelTimer(timerId);
                    promise.complete();
                }
            } catch (RuntimeException e) {
                if (settled.compareAndSet(false, true)) {
                    vertx.cancelTimer(timerId);
                    promise.fail(e);
                }
            }
        });
        return promise.future();
    }

    /**
     * Returns a future that mirrors {@code operation}, failing with {@link TimeoutException} if it
     * does not settle within the timeout.
     *
     * <p>Timing out does not cancel the underlying operation. It may still settle later, but its
     * result is ignored after the returned future times out.
     *
     * @param operation the asynchronous operation to bound
     * @param timeoutMs maximum time to wait; must be positive
     * @param description short description included in timeout failures
     * @param <T> the result type
     * @return a future with the same result or failure, or a timeout failure; a {@link TimeoutException}
     *     raised by the operation itself passes through unchanged
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T> Future<T> withTimeout(Future<T> operation, long timeoutMs, String description) {
        Objects.requireNonNull(operation, "operation");
        requirePositive(timeoutMs, "timeoutMs");
        Objects.requireNonNull(description, "description");

        return operation.timeout(timeoutMs, TimeUnit.MILLISECONDS).recover(cause -> {
            boolean operationOwnFailure = operation.failed() && operation.cause() == cause;
            if (cause instanceof TimeoutException && !operationOwnFailure) {
                return Future.failedFuture(timeout(description, timeoutMs));
            }
            return Future.failedFuture(cause);
        });
    }

    private static TimeoutException timeout(String operation, long timeoutMs) {
        return new TimeoutException(operation + " did not complete within " + timeoutMs + " ms");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
