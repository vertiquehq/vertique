// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.CircuitFailureClassifier;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceClosedException;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Independently executable local circuit breaker with explicit instance-owned state. */
public final class CircuitBreaker implements Resilience.RuntimeExecution {

    private final Resilience resilience;
    private final String stateKey;
    private final io.vertx.circuitbreaker.CircuitBreaker engine;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Promise<Void> closePromise = Promise.promise();

    private CircuitBreaker(Resilience resilience, String stateKey, CircuitBreakerConfig configuration) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.stateKey = Objects.requireNonNull(stateKey, "stateKey");
        CircuitBreakerOptions options = new CircuitBreakerOptions()
                .setMaxFailures(configuration.maxFailures())
                .setTimeout(-1L)
                .setResetTimeout(configuration.resetTimeoutMs())
                .setMaxRetries(0);
        this.engine = io.vertx.circuitbreaker.CircuitBreaker.create(stateKey, resilience.vertx(), options);
    }

    /**
     * Starts construction of a circuit breaker for an application operation.
     *
     * @param resilience owning runtime
     * @param stateName construction-time state name
     * @return a single-use builder
     */
    public static Builder builder(Resilience resilience, String stateName) {
        Objects.requireNonNull(resilience, "resilience");
        return new Builder(resilience, ResilienceIdentity.applicationOperationKey(stateName));
    }

    static Builder builderForDerivedKey(Resilience resilience, String stateKey) {
        return new Builder(
                Objects.requireNonNull(resilience, "resilience"), Objects.requireNonNull(stateKey, "stateKey"));
    }

    static CircuitBreaker forAdapterIdentity(
            Resilience resilience, AdapterOperationIdentity identity, CircuitBreakerConfig configuration) {
        CircuitBreaker breaker =
                new CircuitBreaker(resilience, ResiliencePipeline.deriveAdapterOperationKey(identity), configuration);
        if (!resilience.register(breaker)) {
            throw new IllegalStateException("Resilience runtime is closed");
        }
        return breaker;
    }

    /**
     * Executes one logical supplier under this breaker.
     *
     * @param operation asynchronous operation supplier
     * @param <T> operation result type
     * @return the settled operation result
     */
    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        return execute(stateKey, null, operation, () -> true, ignored -> {});
    }

    <T> Future<T> execute(
            String operationKey,
            CircuitFailureClassifier classifier,
            Supplier<Future<T>> operation,
            BooleanSupplier contextOpen,
            Consumer<Runnable> executionRegistrar) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(operation, "operation");
        Context context = resilience.executionContext();
        Execution<T> execution =
                new Execution<>(context, operationKey, classifier, operation, contextOpen, executionRegistrar);
        if (!resilience.register(execution)) {
            return resilience.failedOnContext(context, operationKey);
        }
        context.runOnContext(ignored -> execution.start());
        return execution.future();
    }

    /**
     * Closes this breaker and releases its Vert.x engine resources.
     *
     * @return the idempotent close future
     */
    public Future<Void> close() {
        requestClose();
        return closePromise.future();
    }

    String stateKey() {
        return stateKey;
    }

    Resilience resilience() {
        return resilience;
    }

    io.vertx.circuitbreaker.CircuitBreaker engine() {
        return engine;
    }

    @Override
    public void requestClose() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        closed.set(true);
        engine.close();
        resilience.remove(this);
        closePromise.tryComplete();
    }

    boolean isClosed() {
        return closed.get();
    }

    /** Builder for one immutable breaker component. */
    public static final class Builder {
        private final Resilience resilience;
        private final String stateKey;
        private int maxFailures = 5;
        private Duration resetTimeout = Duration.ofSeconds(10);
        private boolean built;

        private Builder(Resilience resilience, String stateKey) {
            this.resilience = resilience;
            this.stateKey = stateKey;
        }

        /**
         * Sets the number of final failures that opens the breaker.
         *
         * @param value positive failure threshold
         * @return this builder
         */
        public Builder maxFailures(int value) {
            ensureMutable();
            maxFailures = value;
            return this;
        }

        /**
         * Sets the delay before an OPEN breaker admits a HALF_OPEN probe.
         *
         * @param value positive reset duration
         * @return this builder
         */
        public Builder resetTimeout(Duration value) {
            ensureMutable();
            resetTimeout = Objects.requireNonNull(value, "resetTimeout");
            return this;
        }

        /**
         * Builds the immutable breaker.
         *
         * @return the runtime-owned breaker
         */
        public CircuitBreaker build() {
            ensureMutable();
            built = true;
            resilience.ensureOpenForConstruction();
            CircuitBreaker breaker =
                    new CircuitBreaker(resilience, stateKey, CircuitBreakerConfig.of(maxFailures, resetTimeout));
            if (!resilience.register(breaker)) {
                throw new IllegalStateException("Resilience runtime is closed");
            }
            return breaker;
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("circuit-breaker builder has already been used");
            }
        }
    }

    private final class Execution<T> implements Resilience.RuntimeExecution {
        private final Context context;
        private final String operationKey;
        private final CircuitFailureClassifier classifier;
        private final Supplier<Future<T>> operation;
        private final BooleanSupplier contextOpen;
        private final Consumer<Runnable> executionRegistrar;
        private final Promise<T> result = Promise.promise();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean invoked = new AtomicBoolean();

        private Execution(
                Context context,
                String operationKey,
                CircuitFailureClassifier classifier,
                Supplier<Future<T>> operation,
                BooleanSupplier contextOpen,
                Consumer<Runnable> executionRegistrar) {
            this.context = context;
            this.operationKey = operationKey;
            this.classifier = classifier;
            this.operation = operation;
            this.contextOpen = contextOpen;
            this.executionRegistrar = executionRegistrar == null ? ignored -> {} : executionRegistrar;
            this.executionRegistrar.accept(this::requestClose);
        }

        private Future<T> future() {
            return result.future();
        }

        private void start() {
            if (settled.get() || isClosed() || !contextOpen.getAsBoolean()) {
                settleClosed();
                return;
            }
            Future<EngineOutcome<T>> outcome;
            try {
                outcome = engine.execute(() -> {
                    invoked.set(true);
                    Future<T> supplied;
                    try {
                        supplied = Objects.requireNonNull(operation.get(), "operation returned null future");
                    } catch (Exception failure) {
                        return classifiedFailure(failure);
                    } catch (Error fatal) {
                        if (isFatal(fatal)) {
                            return Future.succeededFuture(new EngineOutcome<>(null, fatal));
                        }
                        throw fatal;
                    }
                    return supplied.map(value -> new EngineOutcome<>(value, null))
                            .recover(this::recoverFailure);
                });
            } catch (Throwable failure) {
                handle(Future.failedFuture(failure));
                return;
            }
            outcome.onComplete(ignored -> context.runOnContext(done -> handle(outcome)));
        }

        private Future<EngineOutcome<T>> classifiedFailure(Throwable failure) {
            if (classifier == null) {
                return Future.failedFuture(failure);
            }
            try {
                return classifier.countsAsFailure(failure)
                        ? Future.failedFuture(failure)
                        : Future.succeededFuture(new EngineOutcome<>(null, failure));
            } catch (Throwable classifierFailure) {
                return isFatal(classifierFailure)
                        ? Future.succeededFuture(new EngineOutcome<>(null, classifierFailure))
                        : Future.failedFuture(failure);
            }
        }

        private Future<EngineOutcome<T>> recoverFailure(Throwable failure) {
            if (isFatal(failure)) {
                return Future.succeededFuture(new EngineOutcome<>(null, failure));
            }
            if (classifier == null) {
                return Future.failedFuture(failure);
            }
            try {
                return classifier.countsAsFailure(failure)
                        ? Future.failedFuture(failure)
                        : Future.succeededFuture(new EngineOutcome<>(null, failure));
            } catch (Throwable classifierFailure) {
                return isFatal(classifierFailure)
                        ? Future.succeededFuture(new EngineOutcome<>(null, classifierFailure))
                        : Future.failedFuture(failure);
            }
        }

        private void handle(Future<EngineOutcome<T>> outcome) {
            if (settled.get()) {
                return;
            }
            if (isClosed() || !contextOpen.getAsBoolean()) {
                settleClosed();
                return;
            }
            if (outcome.succeeded()) {
                EngineOutcome<T> engineOutcome = outcome.result();
                if (engineOutcome.failure() == null) {
                    settleSuccess(engineOutcome.value());
                } else if (isFatal(engineOutcome.failure())) {
                    settleFatal((Error) engineOutcome.failure());
                } else {
                    settleFailure(engineOutcome.failure());
                }
                return;
            }
            Throwable failure = outcome.cause();
            if (!invoked.get()) {
                settleFailure(new CircuitOpenException(operationKey, stateKey));
                return;
            }
            settleFailure(failure);
        }

        private void settleSuccess(T value) {
            if (settled.compareAndSet(false, true)) {
                result.tryComplete(value);
                resilience.remove(this);
            }
        }

        private void settleFailure(Throwable failure) {
            if (settled.compareAndSet(false, true)) {
                result.tryFail(Objects.requireNonNull(failure, "failure"));
                resilience.remove(this);
            }
        }

        private void settleClosed() {
            settleFailure(new ResilienceClosedException(operationKey));
        }

        private void settleFatal(Error fatal) {
            if (settled.compareAndSet(false, true)) {
                result.tryFail(fatal);
                resilience.remove(this);
                context.runOnContext(ignored -> {
                    throw fatal;
                });
            }
        }

        @Override
        public void requestClose() {
            settleClosed();
        }

        private boolean isFatal(Throwable failure) {
            return failure instanceof VirtualMachineError
                    || failure instanceof ThreadDeath
                    || failure instanceof LinkageError;
        }
    }

    private record EngineOutcome<T>(T value, Throwable failure) {}
}
