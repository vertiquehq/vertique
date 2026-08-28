// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.exception.ResilienceClosedException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
    private final Object lifecycleMonitor = new Object();
    private final Set<TimeoutExecution<?>> activeExecutions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final AtomicReference<Promise<Void>> closePromise = new AtomicReference<>();

    private Resilience(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.fallbackContext = vertx.getOrCreateContext();
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
        Set<TimeoutExecution<?>> executions;
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
            executions.forEach(TimeoutExecution::close);
        }
        return shutdown.future();
    }

    <T> Future<T> executeTimeout(String operationKey, TimeoutConfig configuration, Supplier<Future<T>> operation) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(operation, "operation");

        Context selectedContext = Vertx.currentContext();
        if (selectedContext == null) {
            selectedContext = fallbackContext;
        }

        TimeoutExecution<T> execution;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return failedOnContext(selectedContext, operationKey);
            }
            execution =
                    new TimeoutExecution<>(this, selectedContext, operationKey, configuration.timeoutMs(), operation);
            activeExecutions.add(execution);
        }
        selectedContext.runOnContext(ignored -> execution.start());
        return execution.future();
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

    void remove(TimeoutExecution<?> execution) {
        activeExecutions.remove(execution);
        completeCloseIfIdle();
    }

    private void completeCloseIfIdle() {
        Promise<Void> shutdown = closePromise.get();
        if (shutdown == null || !closed || !activeExecutions.isEmpty()) {
            return;
        }
        shutdown.tryComplete();
    }

    private <T> Future<T> failedOnContext(Context context, String operationKey) {
        Promise<T> result = Promise.promise();
        context.runOnContext(ignored -> result.tryFail(new ResilienceClosedException(operationKey)));
        return result.future();
    }
}
