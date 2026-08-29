// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.CircuitFailureClassifier;
import dev.vertique.resilience.adapter.ResilienceAdapterSupport;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-scoped owner of resilience components, execution contexts, and runtime lifecycle.
 *
 * <p>A runtime is deliberately created per application graph. It does not maintain a process-wide
 * registry, and retains derived operation identities instead of their raw input names.
 */
public final class Resilience {

    private static final Logger LOGGER = LoggerFactory.getLogger(Resilience.class);

    private final Vertx vertx;
    private final Context fallbackContext;
    private final TimerScheduler timerScheduler;
    private final DoubleSupplier randomSource;
    private final ResilienceAdapterSupport adapterSupport;
    private final ResiliencePolicyResolver policyResolver;
    private final Set<ResilienceObserver> observers;
    private long nextExecutionId;
    private final Object lifecycleMonitor = new Object();
    private final Set<RuntimeExecution> activeExecutions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final AtomicReference<Promise<Void>> closePromise = new AtomicReference<>();

    private Resilience(Vertx vertx) {
        this(vertx, new VertxTimerScheduler(vertx), ThreadLocalRandom.current()::nextDouble, Set.of());
    }

    Resilience(Vertx vertx, TimerScheduler timerScheduler, DoubleSupplier randomSource) {
        this(vertx, timerScheduler, randomSource, Set.of());
    }

    Resilience(
            Vertx vertx,
            TimerScheduler timerScheduler,
            DoubleSupplier randomSource,
            Set<ResilienceObserver> observers) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.timerScheduler = Objects.requireNonNull(timerScheduler, "timerScheduler");
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource");
        this.observers = Set.copyOf(Objects.requireNonNull(observers, "observers"));
        this.fallbackContext = vertx.getOrCreateContext();
        this.adapterSupport = ResilienceAdapterSupport.create(this);
        this.policyResolver = new ResiliencePolicyResolver();
    }

    /**
     * Creates a standalone application-scoped resilience runtime.
     *
     * @param vertx the Vert.x instance that owns runtime contexts and timers
     * @return a new resilience runtime
     * @throws NullPointerException if {@code vertx} is {@code null}
     */
    public static Resilience create(Vertx vertx) {
        return new Resilience(vertx);
    }

    /**
     * Creates a standalone runtime with explicitly contributed synchronous observers.
     *
     * @param vertx the Vert.x instance that owns runtime contexts and timers
     * @param observers resilience-only observers
     * @return a new resilience runtime
     */
    public static Resilience create(Vertx vertx, Set<ResilienceObserver> observers) {
        return new Resilience(
                Objects.requireNonNull(vertx, "vertx"),
                new VertxTimerScheduler(vertx),
                ThreadLocalRandom.current()::nextDouble,
                observers);
    }

    /**
     * Returns the framework-adapter facade owned by this runtime.
     *
     * @return the runtime-owned adapter support facade
     */
    public ResilienceAdapterSupport adapterSupport() {
        return adapterSupport;
    }

    /**
     * Returns the pure resolver owned by this runtime's policy construction surface.
     *
     * @return policy resolver
     */
    public ResiliencePolicyResolver policyResolver() {
        return policyResolver;
    }

    /**
     * Bridges the runtime-owned adapter facade to structured pipeline construction.
     *
     * @param identity structured adapter operation identity
     * @param policy complete resolved policy
     * @return executable timeout/retry pipeline
     */
    public ResiliencePipeline adapterPipeline(AdapterOperationIdentity identity, ResolvedResiliencePolicy policy) {
        return ResiliencePipeline.fromAdapterPolicy(this, identity, policy);
    }

    /**
     * Framework-only bridge for creating an adapter-owned bulkhead from a structured identity.
     *
     * @param identity structured adapter identity
     * @param configuration bulkhead configuration
     * @return runtime-owned bulkhead
     */
    public Bulkhead adapterBulkhead(AdapterOperationIdentity identity, BulkheadConfig configuration) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(configuration, "configuration");
        ensureOpenForConstruction();
        return Bulkhead.forAdapterIdentity(this, identity, configuration);
    }

    /**
     * Framework-only bridge for an explicitly owned adapter circuit-breaker context.
     *
     * @param identity structured adapter identity
     * @param policy policy without the breaker concern
     * @param circuitBreaker same-owner breaker instance
     * @param classifier adapter final-failure classifier
     * @param contextOpen context lifecycle predicate
     * @param executionRegistrar active execution registrar returning a removal handle
     * @return executable adapter pipeline
     */
    public ResiliencePipeline adapterPipeline(
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policy,
            CircuitBreaker circuitBreaker,
            CircuitFailureClassifier classifier,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        return ResiliencePipeline.fromAdapterPolicy(
                this, identity, policy, circuitBreaker, classifier, contextOpen, executionRegistrar);
    }

    /** Framework-only bridge for an adapter context with an explicit bulkhead. */
    public ResiliencePipeline adapterPipeline(
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policy,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            CircuitFailureClassifier classifier,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        return ResiliencePipeline.fromAdapterPolicy(
                this, identity, policy, circuitBreaker, bulkhead, classifier, contextOpen, executionRegistrar);
    }

    /**
     * Framework-only bridge for an adapter context without a circuit-breaker concern.
     *
     * @param identity structured adapter identity
     * @param policy resolved timeout/retry policy
     * @param contextOpen context lifecycle predicate
     * @param executionRegistrar active execution registrar returning a removal handle
     * @return executable adapter pipeline
     */
    public ResiliencePipeline adapterPipeline(
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policy,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        return ResiliencePipeline.fromAdapterPolicy(
                this, identity, policy, null, null, contextOpen, executionRegistrar);
    }

    /** Framework-only bridge for an adapter context with a bulkhead but no breaker. */
    public ResiliencePipeline adapterPipeline(
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policy,
            Bulkhead bulkhead,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        return ResiliencePipeline.fromAdapterPolicy(
                this, identity, policy, null, bulkhead, null, contextOpen, executionRegistrar);
    }

    /**
     * Framework-only bridge for creating an adapter-owned breaker from structured identity.
     *
     * @param identity structured adapter state identity
     * @param configuration breaker configuration
     * @return runtime-owned breaker
     */
    public CircuitBreaker adapterCircuitBreaker(AdapterOperationIdentity identity, CircuitBreakerConfig configuration) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(configuration, "configuration");
        ensureOpenForConstruction();
        return CircuitBreaker.forAdapterIdentity(this, identity, configuration);
    }

    /**
     * Starts construction of a pipeline for an application operation identity.
     *
     * @param operationName raw construction-time operation identity
     * @return a pipeline builder
     * @throws NullPointerException if {@code operationName} is {@code null}
     * @throws IllegalArgumentException if the identity is empty, malformed, or too large
     * @throws IllegalStateException if this runtime has already been closed
     */
    public ResiliencePipeline.Builder pipeline(String operationName) {
        String operationKey = ResilienceIdentity.applicationOperationKey(operationName);
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IllegalStateException("Resilience runtime is closed");
            }
        }
        return new ResiliencePipeline.Builder(this, operationKey);
    }

    /**
     * Closes this runtime and fences all public executions that are still active.
     *
     * <p>The operation is idempotent. Every caller receives the exact same terminal future, and
     * shutdown never waits for an uncancelable supplier future.
     *
     * @return the shared shutdown future
     */
    public Future<Void> close() {
        Promise<Void> existing = closePromise.get();
        if (existing != null) {
            return existing.future();
        }

        Promise<Void> shutdown = Promise.promise();
        Set<RuntimeExecution> executions;
        synchronized (lifecycleMonitor) {
            existing = closePromise.get();
            if (existing != null) {
                return existing.future();
            }
            closed = true;
            closePromise.set(shutdown);
            executions = Set.copyOf(activeExecutions);
        }

        if (executions.isEmpty()) {
            shutdown.tryComplete();
        } else {
            executions.forEach(RuntimeExecution::requestClose);
        }
        return shutdown.future();
    }

    <T> Future<T> executeTimeout(String operationKey, TimeoutConfig configuration, Supplier<Future<T>> operation) {
        return executeTimeout(operationKey, configuration, operation, null);
    }

    <T> Future<T> executeTimeout(
            String operationKey,
            TimeoutConfig configuration,
            Supplier<Future<T>> operation,
            ResilienceExecutionObservation observation) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(operation, "operation");

        return executeCircuitBreakerPolicy(operationKey, configuration, null, operation, observation, () -> true, null);
    }

    <T> Future<T> executeRetry(
            String operationKey,
            RetryConfig retryConfiguration,
            TimeoutConfig timeoutConfiguration,
            Supplier<Future<T>> operation) {
        return executeRetry(operationKey, retryConfiguration, timeoutConfiguration, operation, null);
    }

    <T> Future<T> executeRetry(
            String operationKey,
            RetryConfig retryConfiguration,
            TimeoutConfig timeoutConfiguration,
            Supplier<Future<T>> operation,
            ResilienceExecutionObservation observation) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(retryConfiguration, "retryConfiguration");
        Objects.requireNonNull(operation, "operation");

        return executeCircuitBreakerPolicy(
                operationKey, timeoutConfiguration, retryConfiguration, operation, observation, () -> true, null);
    }

    <T> Future<T> executeCircuitBreakerPolicy(
            String operationKey,
            TimeoutConfig timeoutConfiguration,
            RetryConfig retryConfiguration,
            Supplier<Future<T>> operation,
            ResilienceExecutionObservation observation,
            BooleanSupplier contextOpen,
            Function<Runnable, Runnable> executionRegistrar) {
        Objects.requireNonNull(operationKey, "operationKey");
        if (timeoutConfiguration == null && retryConfiguration == null) {
            throw new IllegalArgumentException("timeout or retry configuration is required");
        }
        return CircuitBreakerPolicyExecution.execute(
                this,
                operationKey,
                timeoutConfiguration,
                retryConfiguration,
                operation,
                observation,
                contextOpen,
                executionRegistrar);
    }

    Vertx vertx() {
        return vertx;
    }

    void ensureOpenForConstruction() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IllegalStateException("Resilience runtime is closed");
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    void remove(RuntimeExecution execution) {
        activeExecutions.remove(execution);
        completeCloseIfIdle();
    }

    boolean register(RuntimeExecution execution) {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return false;
            }
            activeExecutions.add(Objects.requireNonNull(execution, "execution"));
            return true;
        }
    }

    <T> T startIfOpen(RuntimeExecution execution, Supplier<T> start) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(start, "start");
        synchronized (lifecycleMonitor) {
            if (closed || !activeExecutions.contains(execution)) {
                return null;
            }
            return start.get();
        }
    }

    /** Returns whether a breaker belongs to this runtime owner. */
    public boolean owns(CircuitBreaker circuitBreaker) {
        return circuitBreaker != null && circuitBreaker.resilience() == this;
    }

    long setTimer(long delayMs, Handler<Long> handler) {
        return timerScheduler.setTimer(delayMs, handler);
    }

    boolean cancelTimer(long timerId) {
        return timerScheduler.cancelTimer(timerId);
    }

    double randomDouble() {
        double value = randomSource.getAsDouble();
        if (!Double.isFinite(value) || value < 0.0d || value >= 1.0d) {
            throw new IllegalStateException("random source must return a value in [0, 1)");
        }
        return value;
    }

    long nextExecutionId() {
        synchronized (lifecycleMonitor) {
            return ++nextExecutionId;
        }
    }

    void emit(ResilienceEvent event) {
        Objects.requireNonNull(event, "event");
        for (ResilienceObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Throwable failure) {
                if (isFatal(failure)) {
                    throw (Error) failure;
                }
                LOGGER.warn(
                        "Resilience observer failed observerClass={} eventKind={} exceptionClass={}",
                        observer.getClass().getName(),
                        event.getClass().getSimpleName(),
                        failure.getClass().getName());
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    private void completeCloseIfIdle() {
        Promise<Void> shutdown = closePromise.get();
        if (shutdown == null || !closed || !activeExecutions.isEmpty()) {
            return;
        }
        shutdown.tryComplete();
    }

    Context executionContext() {
        Context currentContext = Vertx.currentContext();
        return currentContext == null ? fallbackContext : currentContext;
    }

    <T> Future<T> failedOnContext(Context context, String operationKey) {
        return failedOnContext(context, new ResilienceClosedException(operationKey));
    }

    <T> Future<T> failedOnContext(Context context, Throwable failure) {
        Promise<T> result = Promise.promise();
        context.runOnContext(ignored -> result.tryFail(Objects.requireNonNull(failure, "failure")));
        return result.future();
    }

    interface RuntimeExecution {
        void requestClose();
    }

    interface TimerScheduler {
        long setTimer(long delayMs, Handler<Long> handler);

        boolean cancelTimer(long timerId);
    }

    private static final class VertxTimerScheduler implements TimerScheduler {

        private final Vertx vertx;

        private VertxTimerScheduler(Vertx vertx) {
            this.vertx = vertx;
        }

        @Override
        public long setTimer(long delayMs, Handler<Long> handler) {
            return vertx.setTimer(delayMs, handler);
        }

        @Override
        public boolean cancelTimer(long timerId) {
            return vertx.cancelTimer(timerId);
        }
    }
}
