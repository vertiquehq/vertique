// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.event.ResilienceConcern;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Executes timeout and retry policies through isolated Vert.x circuit-breaker engines. */
final class CircuitBreakerPolicyExecution<T> implements Resilience.RuntimeExecution {

    private final Resilience resilience;
    private final Context context;
    private final String operationKey;
    private final TimeoutConfig timeoutConfiguration;
    private final RetryConfig retryConfiguration;
    private final Supplier<Future<T>> operation;
    private final ResilienceExecutionObservation observation;
    private final BooleanSupplier contextOpen;
    private final Function<Runnable, Runnable> executionRegistrar;
    private final io.vertx.circuitbreaker.CircuitBreaker retryEngine;
    private final io.vertx.circuitbreaker.CircuitBreaker timeoutEngine;
    private final Promise<T> result = Promise.promise();
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean startClaimed = new AtomicBoolean();
    private final AtomicBoolean policyCallbackFailed = new AtomicBoolean();
    private final AtomicInteger lastAttemptOrdinal = new AtomicInteger();
    private final AtomicReference<RetryDecision> retryDecision = new AtomicReference<>();
    private final AtomicReference<Attempt> currentAttempt = new AtomicReference<>();
    private Runnable deregistration = () -> {};

    private CircuitBreakerPolicyExecution(
            Resilience resilience,
            Context context,
            String operationKey,
            TimeoutConfig timeoutConfiguration,
            RetryConfig retryConfiguration,
            Supplier<Future<T>> operation,
            ResilienceExecutionObservation observation,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.context = Objects.requireNonNull(context, "context");
        this.operationKey = Objects.requireNonNull(operationKey, "operationKey");
        this.timeoutConfiguration = timeoutConfiguration;
        this.retryConfiguration = retryConfiguration;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.observation = observation;
        this.contextOpen = Objects.requireNonNull(contextOpen, "contextOpen");
        this.executionRegistrar = executionRegistrar == null ? ignored -> () -> {} : executionRegistrar;
        this.retryEngine = io.vertx.circuitbreaker.CircuitBreaker.create(
                operationKey + ":retry", resilience.vertx(), options(-1L, retryConfiguration));
        if (retryConfiguration != null) {
            retryEngine.retryPolicy((failure, retryCount) -> {
                RetryDecision decision = retryDecision.getAndSet(null);
                return decision == null ? 0L : decision.delayMs();
            });
        }
        this.timeoutEngine = timeoutConfiguration == null
                ? null
                : io.vertx.circuitbreaker.CircuitBreaker.create(
                        operationKey + ":timeout", resilience.vertx(), options(timeoutConfiguration.timeoutMs(), null));
    }

    static <T> Future<T> execute(
            Resilience resilience,
            String operationKey,
            TimeoutConfig timeoutConfiguration,
            RetryConfig retryConfiguration,
            Supplier<Future<T>> operation,
            ResilienceExecutionObservation observation,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        Context context = resilience.executionContext();
        CircuitBreakerPolicyExecution<T> execution = new CircuitBreakerPolicyExecution<>(
                resilience,
                context,
                operationKey,
                timeoutConfiguration,
                retryConfiguration,
                operation,
                observation,
                contextOpen,
                executionRegistrar);
        if (!resilience.register(execution)) {
            return resilience.failedOnContext(context, operationKey);
        }
        context.runOnContext(ignored -> execution.start());
        return execution.result.future();
    }

    private static CircuitBreakerOptions options(long timeoutMs, RetryConfig retryConfiguration) {
        return new CircuitBreakerOptions()
                .setMaxFailures(Integer.MAX_VALUE)
                .setTimeout(timeoutMs)
                .setResetTimeout(-1L)
                .setMaxRetries(retryConfiguration == null ? 0 : retryConfiguration.maxRetries());
    }

    private void start() {
        if (!startClaimed.compareAndSet(false, true)
                || settled.get()
                || closeRequested.get()
                || resilience.isClosed()
                || !contextOpen.getAsBoolean()) {
            settleClosed();
            return;
        }
        deregistration =
                Objects.requireNonNull(executionRegistrar.apply(this::requestClose), "execution deregistration handle");
        if (closeRequested.get() || settled.get() || !contextOpen.getAsBoolean()) {
            settleClosed();
            return;
        }
        Future<AttemptResult<T>> outcome;
        try {
            outcome = resilience.startIfOpen(this, () -> retryEngine.execute(this::invokeAttempt));
            if (outcome == null) {
                settleClosed();
                return;
            }
        } catch (Throwable failure) {
            handle(Future.failedFuture(failure));
            return;
        }
        outcome.onComplete(ignored -> context.runOnContext(done -> handle(outcome)));
    }

    private Future<AttemptResult<T>> invokeAttempt() {
        int ordinal = observation == null ? lastAttemptOrdinal.incrementAndGet() : observation.attemptStarted();
        lastAttemptOrdinal.set(ordinal);
        Attempt attempt = new Attempt(ordinal);
        currentAttempt.set(attempt);
        Future<T> supplied;
        try {
            supplied = Objects.requireNonNull(operation.get(), "operation returned null future");
        } catch (Exception failure) {
            attempt.complete(failure);
            return resolveFailure(failure, ordinal);
        } catch (Error fatal) {
            attempt.complete(fatal);
            return Future.succeededFuture(AttemptResult.failure(fatal));
        }
        if (timeoutEngine == null) {
            supplied.onComplete(outcome -> attempt.complete(outcome.succeeded() ? null : outcome.cause()));
            return supplied.map(value -> AttemptResult.success(value))
                    .recover(failure -> resolveFailure(failure, ordinal));
        }
        Future<T> timed = timeoutEngine.execute(() -> supplied);
        timed.onComplete(outcome -> {
            if (isTimeout(outcome.cause())) {
                attempt.timeout();
            } else {
                attempt.complete(outcome.succeeded() ? null : outcome.cause());
            }
        });
        return timed.map(value -> AttemptResult.success(value)).recover(failure -> resolveFailure(failure, ordinal));
    }

    private Future<AttemptResult<T>> resolveFailure(Throwable failure, int ordinal) {
        Throwable normalized = normalizeTimeout(failure);
        if (isFatal(normalized)) {
            return Future.succeededFuture(AttemptResult.failure(normalized));
        }
        if (retryConfiguration == null || ordinal > retryConfiguration.maxRetries()) {
            return Future.succeededFuture(AttemptResult.failure(normalized));
        }
        try {
            if (retryConfiguration.abortOn().stream().anyMatch(type -> type.isInstance(normalized))) {
                return Future.succeededFuture(AttemptResult.failure(normalized));
            }
            if (!retryConfiguration.retryOn().isEmpty()
                    && retryConfiguration.retryOn().stream().noneMatch(type -> type.isInstance(normalized))) {
                return Future.succeededFuture(AttemptResult.failure(normalized));
            }
            if (retryConfiguration.retryOn().isEmpty()
                    && !retryConfiguration.fallbackPolicy().shouldRetry(normalized, ordinal - 1)) {
                return Future.succeededFuture(AttemptResult.failure(normalized));
            }
            long delay = delay(retryConfiguration.backoff(), ordinal - 1);
            if (delay < 0L) {
                return Future.succeededFuture(AttemptResult.failure(normalized));
            }
            retryDecision.set(new RetryDecision(delay));
            if (observation != null) {
                observation.retryScheduled(ordinal, ordinal + 1, delay, normalized);
            }
            return Future.failedFuture(normalized);
        } catch (Throwable callbackFailure) {
            if (isFatal(callbackFailure)) {
                throw (Error) callbackFailure;
            }
            policyCallbackFailed.set(true);
            PolicyCallbackKind callbackKind = callbackFailure instanceof BackoffFailure
                    ? PolicyCallbackKind.BACKOFF
                    : PolicyCallbackKind.RETRY_ELIGIBILITY;
            Throwable metadataFailure =
                    callbackFailure instanceof BackoffFailure backoffFailure ? backoffFailure.cause() : callbackFailure;
            if (observation != null) {
                observation.policyEvaluationFailed(ResilienceConcern.RETRY, callbackKind, metadataFailure);
            }
            return Future.succeededFuture(AttemptResult.failure(new ResiliencePolicyException(
                    operationKey, callbackKind, metadataFailure.getClass(), Optional.of(normalized.getClass()))));
        }
    }

    private long delay(RetryBackoff backoff, int retryCount) {
        try {
            return backoff.delayMs(retryCount, resilience::randomDouble);
        } catch (Throwable failure) {
            throw new BackoffFailure(failure);
        }
    }

    private void handle(Future<AttemptResult<T>> outcome) {
        if (settled.get()) {
            return;
        }
        if (closeRequested.get() || !contextOpen.getAsBoolean()) {
            settleClosed();
            return;
        }
        if (outcome.succeeded()) {
            AttemptResult<T> value = outcome.result();
            if (value.failure() == null) {
                settleSuccess(value.value());
            } else {
                if (isFatal(value.failure())) {
                    settleFailure(value.failure());
                    rethrowFatal((Error) value.failure());
                    return;
                }
                if (retryConfiguration != null && !policyCallbackFailed.get() && observation != null) {
                    observation.retryExhausted(lastAttemptOrdinal.get(), value.failure());
                }
                settleFailure(value.failure());
            }
            return;
        }
        Throwable failure = normalizeTimeout(outcome.cause());
        Attempt attempt = currentAttempt.get();
        if (isTimeout(outcome.cause()) && attempt != null) {
            attempt.timeout();
        }
        if (retryConfiguration != null && !policyCallbackFailed.get() && observation != null) {
            observation.retryExhausted(lastAttemptOrdinal.get(), failure);
        }
        settleFailure(failure);
        if (isFatal(failure)) {
            context.runOnContext(ignored -> {
                throw (Error) failure;
            });
        }
    }

    private Throwable normalizeTimeout(Throwable failure) {
        if (isTimeout(failure) && timeoutConfiguration != null) {
            return new ResilienceTimeoutException(operationKey, timeoutConfiguration.timeoutMs());
        }
        return failure;
    }

    private static boolean isTimeout(Throwable failure) {
        return failure instanceof io.vertx.circuitbreaker.TimeoutException || failure instanceof TimeoutException;
    }

    private void settleSuccess(T value) {
        if (settled.compareAndSet(false, true)) {
            closeEngines();
            result.tryComplete(value);
            resilience.remove(this);
            deregistration.run();
        }
    }

    private void settleFailure(Throwable failure) {
        if (settled.compareAndSet(false, true)) {
            closeEngines();
            result.tryFail(Objects.requireNonNull(failure, "failure"));
            resilience.remove(this);
            deregistration.run();
        }
    }

    private void settleClosed() {
        settleFailure(new ResilienceClosedException(operationKey));
    }

    private void rethrowFatal(Error fatal) {
        context.runOnContext(ignored -> {
            throw fatal;
        });
    }

    private void closeEngines() {
        retryEngine.close();
        if (timeoutEngine != null) {
            timeoutEngine.close();
        }
    }

    @Override
    public void requestClose() {
        if (closeRequested.compareAndSet(false, true)) {
            startClaimed.compareAndSet(false, true);
            closeEngines();
            context.runOnContext(ignored -> settleClosed());
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private record AttemptResult<T>(T value, Throwable failure) {
        private static <T> AttemptResult<T> success(T value) {
            return new AttemptResult<>(value, null);
        }

        private static <T> AttemptResult<T> failure(Throwable failure) {
            return new AttemptResult<>(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    private record RetryDecision(long delayMs) {}

    private static final class BackoffFailure extends RuntimeException {
        private BackoffFailure(Throwable cause) {
            super(cause);
        }

        private Throwable cause() {
            return getCause();
        }
    }

    private final class Attempt {
        private final int ordinal;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Attempt(int ordinal) {
            this.ordinal = ordinal;
        }

        private void complete(Throwable failure) {
            if (completed.compareAndSet(false, true) && observation != null) {
                observation.attemptCompleted(ordinal, failure);
            }
        }

        private void timeout() {
            if (completed.compareAndSet(false, true) && observation != null) {
                long timeoutMs = timeoutConfiguration.timeoutMs();
                ResilienceTimeoutException failure = new ResilienceTimeoutException(operationKey, timeoutMs);
                observation.timeoutTriggered(ordinal, timeoutMs);
                observation.attemptCompleted(ordinal, failure);
            }
        }
    }
}
