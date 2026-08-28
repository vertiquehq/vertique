// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runtime-owned retry component that executes a bounded, zero-based retry plan. */
public final class Retry {

    private final Resilience resilience;
    private final String operationKey;
    private final RetryConfig configuration;

    private Retry(Resilience resilience, String operationKey, RetryConfig configuration) {
        this.resilience = resilience;
        this.operationKey = operationKey;
        this.configuration = configuration;
    }

    /**
     * Starts construction of a retry component.
     *
     * @param resilience owning runtime
     * @param operationName raw construction-time operation identity
     * @return a single-use retry builder
     */
    public static Builder builder(Resilience resilience, String operationName) {
        Objects.requireNonNull(resilience, "resilience");
        return new Builder(resilience, ResilienceIdentity.applicationOperationKey(operationName));
    }

    static Builder builderForDerivedKey(Resilience resilience, String operationKey) {
        return new Builder(
                Objects.requireNonNull(resilience, "resilience"), Objects.requireNonNull(operationKey, "operationKey"));
    }

    Resilience resilience() {
        return resilience;
    }

    String operationKey() {
        return operationKey;
    }

    RetryConfig configuration() {
        return configuration;
    }

    static long cappedExponentialDelay(RetryBackoff.Exponential backoff, int retryCount) {
        if (backoff.initialDelayMs() == 0L || backoff.maxDelayMs() == 0L) {
            return 0L;
        }
        double value = backoff.initialDelayMs();
        for (int index = 0; index < retryCount && value < backoff.maxDelayMs(); index++) {
            value *= backoff.multiplier();
            if (!Double.isFinite(value)) {
                return backoff.maxDelayMs();
            }
        }
        if (value >= backoff.maxDelayMs()) {
            return backoff.maxDelayMs();
        }
        return Math.min(backoff.maxDelayMs(), (long) Math.ceil(value));
    }

    /**
     * Executes the supplier under this retry policy.
     *
     * @param operation asynchronous operation supplier
     * @param <T> operation result type
     * @return a future settled by the retry plan
     */
    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        return resilience.executeRetry(operationKey, configuration, null, operation);
    }

    /** Builder for one immutable retry component. */
    public static final class Builder {

        private final Resilience resilience;
        private final String operationKey;
        private final RetryConfig.Builder configuration = RetryConfig.builder();
        private boolean built;

        private Builder(Resilience resilience, String operationKey) {
            this.resilience = resilience;
            this.operationKey = operationKey;
        }

        /**
         * Sets the maximum number of additional attempts.
         *
         * @param value additional-attempt count in the range {@code 0..100}
         * @return this builder
         */
        public Builder maxRetries(int value) {
            ensureMutable();
            configuration.maxRetries(value);
            return this;
        }

        /**
         * Sets the retry backoff.
         *
         * @param value backoff policy
         * @return this builder
         */
        public Builder backoff(RetryBackoff value) {
            ensureMutable();
            configuration.backoff(value);
            return this;
        }

        /**
         * Sets the selective retry exception set.
         *
         * @param value exception types eligible for retry
         * @return this builder
         */
        public Builder retryOn(Set<Class<? extends Throwable>> value) {
            ensureMutable();
            configuration.retryOn(value);
            return this;
        }

        /**
         * Sets the abort exception set.
         *
         * @param value exception types that always stop retrying
         * @return this builder
         */
        public Builder abortOn(Set<Class<? extends Throwable>> value) {
            ensureMutable();
            configuration.abortOn(value);
            return this;
        }

        /**
         * Sets the fallback retry predicate.
         *
         * @param value retry eligibility predicate used when {@code retryOn} is empty
         * @return this builder
         */
        public Builder fallbackPolicy(RetryPolicy value) {
            ensureMutable();
            configuration.fallbackPolicy(value);
            return this;
        }

        /**
         * Builds the immutable retry component.
         *
         * @return the retry component
         */
        public Retry build() {
            ensureMutable();
            built = true;
            resilience.ensureOpenForConstruction();
            return new Retry(resilience, operationKey, configuration.build());
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("retry builder has already been used");
            }
        }
    }

    static final class Execution<T> implements Resilience.RuntimeExecution {

        private final Resilience resilience;
        private final Context context;
        private final String operationKey;
        private final RetryConfig configuration;
        private final TimeoutConfig timeoutConfiguration;
        private final Supplier<Future<T>> operation;
        private final Promise<T> result = Promise.promise();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean startClaimed = new AtomicBoolean();
        private final AtomicBoolean closeRequested = new AtomicBoolean();

        private volatile TimeoutExecution<T> currentAttempt;
        private volatile long retryTimerId = -1L;
        private int attemptNumber;

        Execution(
                Resilience resilience,
                Context context,
                String operationKey,
                RetryConfig configuration,
                TimeoutConfig timeoutConfiguration,
                Supplier<Future<T>> operation) {
            this.resilience = Objects.requireNonNull(resilience, "resilience");
            this.context = Objects.requireNonNull(context, "context");
            this.operationKey = Objects.requireNonNull(operationKey, "operationKey");
            this.configuration = Objects.requireNonNull(configuration, "configuration");
            this.timeoutConfiguration = timeoutConfiguration;
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        Future<T> future() {
            return result.future();
        }

        void start() {
            if (!startClaimed.compareAndSet(false, true) || settled.get() || closeRequested.get()) {
                return;
            }
            startAttempt();
        }

        @Override
        public void requestClose() {
            closeRequested.set(true);
            startClaimed.compareAndSet(false, true);
            cancelRetryTimer();
            TimeoutExecution<T> attempt = currentAttempt;
            if (attempt != null) {
                attempt.requestClose();
            }
            context.runOnContext(ignored -> settleClosed());
        }

        private void startAttempt() {
            if (settled.get() || closeRequested.get()) {
                settleClosedIfRequested();
                return;
            }
            try {
                if (timeoutConfiguration == null) {
                    invokeSupplier(operation);
                } else {
                    TimeoutExecution<T> timeout = resilience.scheduleTimeout(
                            context,
                            operationKey,
                            timeoutConfiguration.timeoutMs(),
                            this::invokeSupplierWithoutRethrow);
                    if (timeout == null) {
                        settleClosed();
                        return;
                    }
                    currentAttempt = timeout;
                    timeout.future()
                            .onComplete(outcome -> context.runOnContext(ignored -> {
                                currentAttempt = null;
                                handleAttempt(outcome);
                            }));
                }
            } catch (Error fatal) {
                if (isFatal(fatal)) {
                    if (!settled.get()) {
                        failFatal(fatal);
                    }
                    throw fatal;
                }
                throw fatal;
            }
        }

        private Future<T> invokeSupplierWithoutRethrow() {
            try {
                return Objects.requireNonNull(operation.get(), "operation returned null future");
            } catch (Exception failure) {
                return Future.failedFuture(failure);
            } catch (Error fatal) {
                if (isFatal(fatal)) {
                    return Future.failedFuture(fatal);
                }
                return Future.failedFuture(fatal);
            }
        }

        private void invokeSupplier(Supplier<Future<T>> supplier) {
            Future<T> supplied = null;
            try {
                supplied = Objects.requireNonNull(supplier.get(), "operation returned null future");
            } catch (Exception failure) {
                Future<T> failed = Future.failedFuture(failure);
                failed.onComplete(outcome -> context.runOnContext(ignored -> handleAttempt(outcome)));
                return;
            } catch (Error fatal) {
                if (isFatal(fatal)) {
                    if (!settled.get()) {
                        failFatal(fatal);
                    }
                    throw fatal;
                }
                supplied = Future.failedFuture(fatal);
            }
            supplied.onComplete(outcome -> context.runOnContext(ignored -> handleAttempt(outcome)));
        }

        private void handleAttempt(io.vertx.core.AsyncResult<T> outcome) {
            if (settled.get()) {
                return;
            }
            if (closeRequested.get()) {
                settleClosed();
                return;
            }
            Throwable failure = outcome.succeeded() ? null : Objects.requireNonNull(outcome.cause(), "failure");
            if (failure == null) {
                settleSuccess(outcome.result());
                return;
            }
            if (isFatal(failure)) {
                failFatal((Error) failure);
                return;
            }
            int retryCount = attemptNumber;
            if (retryCount >= configuration.maxRetries()) {
                settleFailure(failure);
                return;
            }
            if (!isEligible(failure, retryCount)) {
                settleFailure(failure);
                return;
            }
            long delay = delay(failure, retryCount);
            if (delay < 0L) {
                settleFailure(failure);
                return;
            }
            attemptNumber++;
            scheduleRetry(delay);
        }

        private boolean isEligible(Throwable failure, int retryCount) {
            for (Class<? extends Throwable> abortType : configuration.abortOn()) {
                if (abortType.isInstance(failure)) {
                    settleFailure(failure);
                    return false;
                }
            }
            if (!configuration.retryOn().isEmpty()) {
                boolean matches = configuration.retryOn().stream().anyMatch(type -> type.isInstance(failure));
                if (!matches) {
                    return false;
                }
                return true;
            }
            try {
                return configuration.fallbackPolicy().shouldRetry(failure, retryCount);
            } catch (Throwable callbackFailure) {
                if (isFatal(callbackFailure)) {
                    failFatal((Error) callbackFailure);
                }
                settleCallbackFailure(PolicyCallbackKind.RETRY_ELIGIBILITY, callbackFailure, failure);
                return false;
            }
        }

        private long delay(Throwable failure, int retryCount) {
            try {
                RetryBackoff backoff = configuration.backoff();
                if (backoff instanceof RetryBackoff.Fixed fixed) {
                    return fixed.delayMs();
                }
                if (backoff instanceof RetryBackoff.Exponential exponential) {
                    return exponentialDelay(exponential, retryCount);
                }
                return ((RetryBackoff.Custom) backoff).delegate().delay(retryCount);
            } catch (Throwable callbackFailure) {
                if (isFatal(callbackFailure)) {
                    failFatal((Error) callbackFailure);
                }
                settleCallbackFailure(PolicyCallbackKind.BACKOFF, callbackFailure, failure);
                return -1L;
            }
        }

        private long exponentialDelay(RetryBackoff.Exponential backoff, int retryCount) {
            long base = cappedExponentialDelay(backoff, retryCount);
            long jitterUpperBound = Math.min(base, backoff.maxJitterMs());
            if (jitterUpperBound == 0L) {
                return base;
            }
            long jitter = (long) (resilience.randomDouble() * jitterUpperBound);
            return ExecutionBudget.saturatingAdd(base, jitter);
        }

        private void scheduleRetry(long delay) {
            if (settled.get() || closeRequested.get()) {
                settleClosedIfRequested();
                return;
            }
            if (delay == 0L) {
                startAttempt();
                return;
            }
            long timerId = resilience.setTimer(
                    delay,
                    ignored -> context.runOnContext(timer -> {
                        retryTimerId = -1L;
                        startAttempt();
                    }));
            retryTimerId = timerId;
            if (closeRequested.get() || settled.get()) {
                cancelRetryTimer();
            }
        }

        private void settleSuccess(T value) {
            if (settled.compareAndSet(false, true)) {
                cancelRetryTimer();
                result.tryComplete(value);
                resilience.remove(this);
            }
        }

        private void settleFailure(Throwable failure) {
            if (settled.compareAndSet(false, true)) {
                cancelRetryTimer();
                result.tryFail(Objects.requireNonNull(failure, "failure"));
                resilience.remove(this);
            }
        }

        private void settleCallbackFailure(
                PolicyCallbackKind callbackKind, Throwable callbackFailure, Throwable attemptFailure) {
            settleFailure(new ResiliencePolicyException(
                    operationKey, callbackKind, callbackFailure.getClass(), Optional.of(attemptFailure.getClass())));
        }

        private void settleClosed() {
            settleFailure(new ResilienceClosedException(operationKey));
        }

        private void settleClosedIfRequested() {
            if (closeRequested.get()) {
                settleClosed();
            }
        }

        private void failFatal(Error fatal) {
            if (settled.compareAndSet(false, true)) {
                cancelRetryTimer();
                result.tryFail(fatal);
                resilience.remove(this);
            }
            throw fatal;
        }

        private void cancelRetryTimer() {
            long timer = retryTimerId;
            if (timer >= 0L) {
                retryTimerId = -1L;
                resilience.cancelTimer(timer);
            }
        }

        private static boolean isFatal(Throwable failure) {
            return failure instanceof VirtualMachineError
                    || failure instanceof ThreadDeath
                    || failure instanceof LinkageError;
        }
    }
}
