// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.exception.BulkheadQueueTimeoutException;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.spi.event.BulkheadMode;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Independently executable local reject-or-queue bulkhead with explicit instance-owned capacity. */
public final class Bulkhead implements Resilience.RuntimeExecution {

    private final Resilience resilience;
    private final String stateKey;
    private final BulkheadConfig configuration;
    private final Object stateMonitor = new Object();
    private final ArrayDeque<Execution<?>> waiting = new ArrayDeque<>();
    private final Set<Execution<?>> executions = new HashSet<>();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Promise<Void> closePromise = Promise.promise();

    private int activeCalls;

    private Bulkhead(Resilience resilience, String stateKey, BulkheadConfig configuration) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.stateKey = Objects.requireNonNull(stateKey, "stateKey");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * Starts construction of a bulkhead for an application state name.
     *
     * @param resilience owning runtime
     * @param stateName construction-time state name
     * @return a single-use builder
     */
    public static Builder builder(Resilience resilience, String stateName) {
        Objects.requireNonNull(resilience, "resilience");
        return new Builder(resilience, ResilienceIdentity.applicationStateKey(stateName));
    }

    static Builder builderForDerivedKey(Resilience resilience, String stateKey) {
        return new Builder(
                Objects.requireNonNull(resilience, "resilience"), Objects.requireNonNull(stateKey, "stateKey"));
    }

    static Bulkhead forAdapterIdentity(
            Resilience resilience, AdapterOperationIdentity identity, BulkheadConfig configuration) {
        Bulkhead bulkhead = new Bulkhead(
                Objects.requireNonNull(resilience, "resilience"),
                ResiliencePipeline.deriveAdapterOperationKey(Objects.requireNonNull(identity, "identity")),
                Objects.requireNonNull(configuration, "configuration"));
        if (!resilience.register(bulkhead)) {
            throw new IllegalStateException("Resilience runtime is closed");
        }
        return bulkhead;
    }

    Resilience resilience() {
        return resilience;
    }

    String stateKey() {
        return stateKey;
    }

    BulkheadConfig configuration() {
        return configuration;
    }

    /**
     * Executes one logical supplier under this bulkhead.
     *
     * @param operation asynchronous operation supplier
     * @param <T> operation result type
     * @return the settled operation result
     */
    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        return execute(stateKey, operation, () -> true, ignored -> () -> {});
    }

    <T> Future<T> execute(
            String operationKey,
            Supplier<Future<T>> operation,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        return execute(operationKey, operation, contextOpen, executionRegistrar, null);
    }

    <T> Future<T> execute(
            String operationKey,
            Supplier<Future<T>> operation,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar,
            ResilienceExecutionObservation observation) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(contextOpen, "contextOpen");
        Objects.requireNonNull(executionRegistrar, "executionRegistrar");

        Context context = resilience.executionContext();
        Execution<T> execution =
                new Execution<>(context, operationKey, operation, contextOpen, executionRegistrar, observation);
        if (!resilience.register(execution)) {
            execution.removeRegistration();
            return resilience.failedOnContext(context, new ResilienceClosedException(operationKey));
        }
        boolean accepted;
        synchronized (stateMonitor) {
            accepted = !closeStarted.get() && !execution.isTerminal();
            if (accepted) {
                executions.add(execution);
            }
        }
        if (!accepted) {
            execution.requestClose();
        } else {
            context.runOnContext(ignored -> execution.admit());
        }
        return execution.future();
    }

    /**
     * Closes this bulkhead and fences queued and active public executions.
     *
     * @return the idempotent close future
     */
    public Future<Void> close() {
        requestClose();
        return closePromise.future();
    }

    @Override
    public void requestClose() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        Set<Execution<?>> toClose;
        synchronized (stateMonitor) {
            toClose = Set.copyOf(executions);
        }
        toClose.forEach(Execution::requestClose);
        completeCloseIfIdle();
    }

    /** Builder for one immutable bulkhead component. */
    public static final class Builder {

        private final Resilience resilience;
        private final String stateKey;
        private int maxConcurrentCalls;
        private AdmissionMode mode;
        private int maxQueueSize;
        private Duration queueTimeout;
        private boolean built;

        private Builder(Resilience resilience, String stateKey) {
            this.resilience = resilience;
            this.stateKey = stateKey;
        }

        /**
         * Sets the number of logical executions admitted at once.
         *
         * @param value positive concurrency limit
         * @return this builder
         */
        public Builder maxConcurrentCalls(int value) {
            ensureMutable();
            maxConcurrentCalls = value;
            return this;
        }

        /**
         * Selects immediate rejection when all permits are occupied.
         *
         * @return this builder
         */
        public Builder reject() {
            ensureMutable();
            ensureModeNotConfigured();
            mode = AdmissionMode.REJECT;
            return this;
        }

        /**
         * Selects a FIFO bounded wait queue.
         *
         * @param maxQueueSize maximum number of waiting executions
         * @param queueTimeout maximum wait from enqueue to admission
         * @return this builder
         */
        public Builder queue(int maxQueueSize, Duration queueTimeout) {
            ensureMutable();
            ensureModeNotConfigured();
            mode = AdmissionMode.QUEUE;
            this.maxQueueSize = maxQueueSize;
            this.queueTimeout = Objects.requireNonNull(queueTimeout, "queueTimeout");
            return this;
        }

        /**
         * Builds the immutable bulkhead.
         *
         * @return the runtime-owned bulkhead
         */
        public Bulkhead build() {
            ensureMutable();
            built = true;
            if (mode == null) {
                throw new IllegalStateException("bulkhead admission mode has not been configured");
            }
            resilience.ensureOpenForConstruction();
            BulkheadConfig configuration = mode == AdmissionMode.REJECT
                    ? BulkheadConfig.reject(maxConcurrentCalls)
                    : BulkheadConfig.queue(maxConcurrentCalls, maxQueueSize, queueTimeout);
            Bulkhead bulkhead = new Bulkhead(resilience, stateKey, configuration);
            if (!resilience.register(bulkhead)) {
                throw new IllegalStateException("Resilience runtime is closed");
            }
            return bulkhead;
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("bulkhead builder has already been used");
            }
        }

        private void ensureModeNotConfigured() {
            if (mode != null) {
                throw new IllegalStateException("bulkhead admission mode already configured");
            }
        }
    }

    private final class Execution<T> implements Resilience.RuntimeExecution {

        private final String operationKey;
        private final BooleanSupplier contextOpen;
        private final Function<Runnable, Runnable> executionRegistrar;
        private final ResilienceExecutionObservation observation;
        private final Promise<T> result = Promise.promise();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private Runnable deregistration = () -> {};

        private Context context;
        private Supplier<Future<T>> operation;
        private ExecutionState state = ExecutionState.NEW;
        private long queueTimerId = -1L;
        private long queuedAtNanos;

        private Execution(
                Context context,
                String operationKey,
                Supplier<Future<T>> operation,
                BooleanSupplier contextOpen,
                Function<Runnable, Runnable> executionRegistrar,
                ResilienceExecutionObservation observation) {
            this.context = Objects.requireNonNull(context, "context");
            this.operationKey = Objects.requireNonNull(operationKey, "operationKey");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.contextOpen = contextOpen;
            this.executionRegistrar = executionRegistrar;
            this.observation = observation;
            this.deregistration = Objects.requireNonNull(
                    this.executionRegistrar.apply(this::requestClose), "execution deregistration handle");
        }

        private Future<T> future() {
            return result.future();
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private void admit() {
            Supplier<Future<T>> supplier = null;
            Throwable admissionFailure = null;
            synchronized (stateMonitor) {
                if (state != ExecutionState.NEW) {
                    return;
                }
                if (closeStarted.get() || !contextOpen.getAsBoolean()) {
                    state = ExecutionState.TERMINAL;
                    clearReferences();
                    admissionFailure = new ResilienceClosedException(operationKey);
                } else if (activeCalls < maxConcurrentCalls()) {
                    state = ExecutionState.ACTIVE;
                    activeCalls++;
                    supplier = operation;
                    if (observation != null && configuration instanceof BulkheadConfig.Queue) {
                        observation.bulkheadAdmitted(0L, activeCalls);
                    }
                } else if (configuration instanceof BulkheadConfig.Reject reject) {
                    state = ExecutionState.TERMINAL;
                    clearReferences();
                    admissionFailure = new BulkheadRejectedException(operationKey, reject.maxConcurrentCalls());
                } else {
                    BulkheadConfig.Queue queue = (BulkheadConfig.Queue) configuration;
                    if (waiting.size() >= queue.maxQueueSize()) {
                        state = ExecutionState.TERMINAL;
                        clearReferences();
                        admissionFailure = new BulkheadRejectedException(operationKey, queue.maxConcurrentCalls());
                        if (observation != null) {
                            observation.bulkheadRejected(
                                    BulkheadMode.QUEUE, activeCalls, waiting.size(), queue.maxQueueSize());
                        }
                    } else {
                        state = ExecutionState.WAITING;
                        waiting.addLast(this);
                        queuedAtNanos = System.nanoTime();
                        if (observation != null) {
                            observation.bulkheadQueued(waiting.size(), queue.maxQueueSize());
                        }
                        queueTimerId = resilience.setTimer(queue.queueTimeoutMs(), ignored -> {
                            Context queuedContext = context;
                            if (queuedContext != null) {
                                queuedContext.runOnContext(ignoredContext -> timeoutInQueue());
                            }
                        });
                    }
                }
            }
            if (admissionFailure != null) {
                if (observation != null
                        && admissionFailure instanceof BulkheadRejectedException
                        && configuration instanceof BulkheadConfig.Reject reject) {
                    observation.bulkheadRejected(BulkheadMode.REJECT, activeCalls, 0, reject.maxConcurrentCalls());
                }
                onTerminal();
                publishFailure(admissionFailure);
            } else if (supplier != null) {
                startSupplier(supplier);
            }
        }

        @Override
        public void requestClose() {
            Throwable failure = null;
            synchronized (stateMonitor) {
                if (state == ExecutionState.TERMINAL) {
                    return;
                }
                if (state == ExecutionState.WAITING) {
                    waiting.remove(this);
                    cancelQueueTimer();
                } else if (state == ExecutionState.ACTIVE) {
                    activeCalls--;
                    admitNextLocked();
                }
                state = ExecutionState.TERMINAL;
                clearReferences();
                failure = new ResilienceClosedException(operationKey);
            }
            onTerminal();
            publishFailure(failure);
        }

        private void timeoutInQueue() {
            boolean timedOut;
            synchronized (stateMonitor) {
                timedOut = state == ExecutionState.WAITING && waiting.remove(this);
                if (timedOut) {
                    state = ExecutionState.TERMINAL;
                    cancelQueueTimer();
                    clearReferences();
                }
            }
            if (timedOut) {
                if (observation != null) {
                    observation.bulkheadQueueTimedOut(
                            elapsedQueueMs(), ((BulkheadConfig.Queue) configuration).queueTimeoutMs());
                }
                onTerminal();
                publishFailure(new BulkheadQueueTimeoutException(operationKey, queueTimeoutMs()));
            }
        }

        private void startSupplier(Supplier<Future<T>> supplier) {
            Context capturedContext = context;
            if (capturedContext == null) {
                return;
            }
            try {
                Future<T> supplied = Objects.requireNonNull(supplier.get(), "operation returned null future");
                supplied.onComplete(outcome -> capturedContext.runOnContext(ignored -> complete(outcome)));
            } catch (Exception failure) {
                completeFailure(failure);
            } catch (Error fatal) {
                if (isFatal(fatal)) {
                    completeFailure(fatal);
                    throw fatal;
                }
                completeFailure(fatal);
            }
        }

        private void complete(io.vertx.core.AsyncResult<T> outcome) {
            if (outcome.succeeded()) {
                completeSuccess(outcome.result());
            } else {
                Throwable failure = Objects.requireNonNull(outcome.cause(), "failure");
                completeFailure(failure);
            }
        }

        private void completeSuccess(T value) {
            if (!claimTerminalFromActive()) {
                return;
            }
            onTerminal();
            result.tryComplete(value);
        }

        private void completeFailure(Throwable failure) {
            if (!claimTerminalFromActive()) {
                return;
            }
            onTerminal();
            result.tryFail(failure);
        }

        private boolean claimTerminalFromActive() {
            synchronized (stateMonitor) {
                if (state != ExecutionState.ACTIVE || !terminal.compareAndSet(false, true)) {
                    return false;
                }
                activeCalls--;
                cancelQueueTimer();
                clearReferences();
                return true;
            }
        }

        private void publishFailure(Throwable failure) {
            terminal.set(true);
            result.tryFail(failure);
        }

        private void onTerminal() {
            Execution<?> next;
            synchronized (stateMonitor) {
                executions.remove(this);
                next = admitNextLocked();
            }
            if (next != null) {
                next.scheduleAdmittedSupplier();
            }
            resilience.remove(this);
            completeCloseIfIdle();
            deregistration.run();
        }

        private void removeRegistration() {
            deregistration.run();
            deregistration = () -> {};
        }

        private void startAdmittedSupplier() {
            Supplier<Future<T>> supplier;
            synchronized (stateMonitor) {
                if (state != ExecutionState.ACTIVE || closeStarted.get() || !contextOpen.getAsBoolean()) {
                    supplier = null;
                } else {
                    supplier = operation;
                }
            }
            if (supplier == null) {
                requestClose();
            } else {
                startSupplier(supplier);
            }
        }

        private void scheduleAdmittedSupplier() {
            Context admittedContext;
            synchronized (stateMonitor) {
                admittedContext = context;
            }
            if (admittedContext != null) {
                if (observation != null) {
                    observation.bulkheadAdmitted(elapsedQueueMs(), activeCount());
                }
                admittedContext.runOnContext(ignored -> startAdmittedSupplier());
            }
        }

        private Execution<?> admitNextLocked() {
            if (closeStarted.get() || activeCalls >= maxConcurrentCalls()) {
                return null;
            }
            Execution<?> next = waiting.pollFirst();
            if (next == null) {
                return null;
            }
            next.cancelQueueTimer();
            next.state = ExecutionState.ACTIVE;
            activeCalls++;
            return next;
        }

        private void clearReferences() {
            operation = null;
            context = null;
        }

        private void cancelQueueTimer() {
            if (queueTimerId >= 0L) {
                resilience.cancelTimer(queueTimerId);
                queueTimerId = -1L;
            }
        }

        private int maxConcurrentCalls() {
            return configuration instanceof BulkheadConfig.Reject reject
                    ? reject.maxConcurrentCalls()
                    : ((BulkheadConfig.Queue) configuration).maxConcurrentCalls();
        }

        private long queueTimeoutMs() {
            return ((BulkheadConfig.Queue) configuration).queueTimeoutMs();
        }

        private long elapsedQueueMs() {
            return queuedAtNanos == 0L ? 0L : Math.max(0L, (System.nanoTime() - queuedAtNanos) / 1_000_000L);
        }

        private int activeCount() {
            synchronized (stateMonitor) {
                return activeCalls;
            }
        }

        private boolean isFatal(Throwable failure) {
            return failure instanceof VirtualMachineError
                    || failure instanceof ThreadDeath
                    || failure instanceof LinkageError;
        }
    }

    private void completeCloseIfIdle() {
        synchronized (stateMonitor) {
            if (!closeStarted.get() || !executions.isEmpty()) {
                return;
            }
        }
        closePromise.tryComplete();
        resilience.remove(this);
    }

    private enum ExecutionState {
        NEW,
        WAITING,
        ACTIVE,
        TERMINAL
    }

    private enum AdmissionMode {
        REJECT,
        QUEUE
    }
}
