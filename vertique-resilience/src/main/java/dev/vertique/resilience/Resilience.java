// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.ResilienceAdapterSupport;
import dev.vertique.resilience.exception.ResilienceClosedException;
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
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Application-scoped owner of resilience components, execution contexts, and runtime lifecycle.
 *
 * <p>A runtime is deliberately created per application graph. It does not maintain a process-wide
 * registry, and retains derived operation identities instead of their raw input names.
 */
public final class Resilience {

    private final Vertx vertx;
    private final Context fallbackContext;
    private final TimerScheduler timerScheduler;
    private final DoubleSupplier randomSource;
    private final ResilienceAdapterSupport adapterSupport;
    private final ResiliencePolicyResolver policyResolver;
    private final Object lifecycleMonitor = new Object();
    private final Set<RuntimeExecution> activeExecutions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final AtomicReference<Promise<Void>> closePromise = new AtomicReference<>();

    private Resilience(Vertx vertx) {
        this(vertx, new VertxTimerScheduler(vertx), ThreadLocalRandom.current()::nextDouble);
    }

    Resilience(Vertx vertx, TimerScheduler timerScheduler, DoubleSupplier randomSource) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.timerScheduler = Objects.requireNonNull(timerScheduler, "timerScheduler");
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource");
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
            shutdown.complete();
        } else {
            executions.forEach(RuntimeExecution::close);
        }
        return shutdown.future();
    }

    <T> Future<T> executeTimeout(String operationKey, TimeoutConfig configuration, Supplier<Future<T>> operation) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(operation, "operation");

        Context selectedContext = executionContext();

        TimeoutExecution<T> execution =
                scheduleTimeout(selectedContext, operationKey, configuration.timeoutMs(), operation);
        if (execution == null) {
            return failedOnContext(selectedContext, operationKey);
        }
        return execution.future();
    }

    <T> Future<T> executeRetry(
            String operationKey,
            RetryConfig retryConfiguration,
            TimeoutConfig timeoutConfiguration,
            Supplier<Future<T>> operation) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(retryConfiguration, "retryConfiguration");
        Objects.requireNonNull(operation, "operation");

        Context selectedContext = executionContext();

        Retry.Execution<T> execution;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return failedOnContext(selectedContext, operationKey);
            }
            execution = new Retry.Execution<>(
                    this, selectedContext, operationKey, retryConfiguration, timeoutConfiguration, operation);
            activeExecutions.add(execution);
        }
        selectedContext.runOnContext(ignored -> execution.start());
        return execution.future();
    }

    <T> TimeoutExecution<T> scheduleTimeout(
            Context context, String operationKey, long timeoutMs, Supplier<Future<T>> operation) {
        TimeoutExecution<T> execution;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return null;
            }
            execution = new TimeoutExecution<>(this, context, operationKey, timeoutMs, operation);
            activeExecutions.add(execution);
        }
        context.runOnContext(ignored -> execution.start());
        return execution;
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

    void remove(RuntimeExecution execution) {
        activeExecutions.remove(execution);
        completeCloseIfIdle();
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

    private void completeCloseIfIdle() {
        Promise<Void> shutdown = closePromise.get();
        if (shutdown == null || !closed || !activeExecutions.isEmpty()) {
            return;
        }
        shutdown.tryComplete();
    }

    private Context executionContext() {
        Context currentContext = Vertx.currentContext();
        return currentContext == null ? fallbackContext : currentContext;
    }

    private <T> Future<T> failedOnContext(Context context, String operationKey) {
        Promise<T> result = Promise.promise();
        context.runOnContext(ignored -> result.tryFail(new ResilienceClosedException(operationKey)));
        return result.future();
    }

    interface RuntimeExecution {
        void close();
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
