// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Package-private lifecycle and race coordinator shared by direct and pipeline timeout execution. */
final class TimeoutExecution<T> implements Resilience.RuntimeExecution {

    private final Resilience resilience;
    private final Context context;
    private final String operationKey;
    private final long timeoutMs;
    private final Supplier<Future<T>> operation;
    private final Promise<T> result = Promise.promise();
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean startClaimed = new AtomicBoolean();

    private long timerId = -1;

    TimeoutExecution(
            Resilience resilience,
            Context context,
            String operationKey,
            long timeoutMs,
            Supplier<Future<T>> operation) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.context = Objects.requireNonNull(context, "context");
        this.operationKey = Objects.requireNonNull(operationKey, "operationKey");
        this.timeoutMs = timeoutMs;
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    Future<T> future() {
        return result.future();
    }

    void start() {
        if (!startClaimed.compareAndSet(false, true) || settled.get()) {
            return;
        }
        try {
            timerId = resilience.setTimer(timeoutMs, ignored -> settleTimeout());
            Future<T> supplied = Objects.requireNonNull(operation.get(), "operation returned null future");
            supplied.onComplete(outcome -> context.runOnContext(ignored -> {
                if (outcome.succeeded()) {
                    settleSuccess(outcome.result());
                } else {
                    settleFailure(outcome.cause());
                }
            }));
        } catch (Exception failure) {
            settleFailure(failure);
        } catch (Error fatal) {
            failFatal(fatal);
            throw fatal;
        }
    }

    public void requestClose() {
        startClaimed.compareAndSet(false, true);
        context.runOnContext(ignored -> {
            settleClosed();
        });
    }

    void failFatal(Error fatal) {
        if (settled.compareAndSet(false, true)) {
            cancelTimer();
            result.tryFail(fatal);
            resilience.remove(this);
        }
    }

    private void settleSuccess(T value) {
        if (settled.compareAndSet(false, true)) {
            cancelTimer();
            result.tryComplete(value);
            resilience.remove(this);
        }
    }

    private void settleFailure(Throwable failure) {
        if (settled.compareAndSet(false, true)) {
            cancelTimer();
            result.tryFail(Objects.requireNonNull(failure, "failure"));
            resilience.remove(this);
        }
    }

    private void settleTimeout() {
        if (settled.compareAndSet(false, true)) {
            cancelTimer();
            result.tryFail(new ResilienceTimeoutException(operationKey, timeoutMs));
            resilience.remove(this);
        }
    }

    private void settleClosed() {
        if (settled.compareAndSet(false, true)) {
            cancelTimer();
            result.tryFail(new ResilienceClosedException(operationKey));
            resilience.remove(this);
        }
    }

    private void cancelTimer() {
        if (timerId >= 0) {
            resilience.cancelTimer(timerId);
            timerId = -1;
        }
    }
}
