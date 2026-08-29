// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

import dev.vertique.resilience.Bulkhead;
import dev.vertique.resilience.BulkheadConfig;
import dev.vertique.resilience.CircuitBreaker;
import dev.vertique.resilience.CircuitBreakerConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opaque per-adapter lifecycle and explicit state-sharing boundary. */
public final class ResilienceAdapterContext {

    private final Resilience resilience;
    private final Set<CircuitBreaker> breakers = ConcurrentHashMap.newKeySet();
    private final Set<Bulkhead> bulkheads = ConcurrentHashMap.newKeySet();
    private final Set<ExecutionRegistration> activeExecutionRegistrations = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Promise<Void> closePromise = Promise.promise();

    ResilienceAdapterContext(Resilience resilience) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
    }

    /** Constructs a context-owned pipeline for a resolved policy. */
    public ResiliencePipeline pipeline(AdapterOperationIdentity identity, ResolvedResiliencePolicy policy) {
        ensureOpen();
        Objects.requireNonNull(policy, "policy");
        if (policy.circuitBreaker().isEmpty() && policy.bulkhead().isEmpty()) {
            return resilience.adapterPipeline(identity, policy, () -> !closed.get(), this::registerExecution);
        }
        if (policy.circuitBreaker().isEmpty()) {
            Bulkhead bulkhead = bulkhead(identity, policy.bulkhead().orElseThrow());
            ResolvedResiliencePolicy withoutBulkhead = withoutBulkhead(policy);
            return resilience.adapterPipeline(
                    identity, withoutBulkhead, bulkhead, () -> !closed.get(), this::registerExecution);
        }
        return pipeline(identity, policy, failure -> true);
    }

    /** Constructs a pipeline with an adapter-owned final-failure classifier. */
    public ResiliencePipeline pipeline(
            AdapterOperationIdentity identity, ResolvedResiliencePolicy policy, CircuitFailureClassifier classifier) {
        ensureOpen();
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(classifier, "classifier");
        if (policy.circuitBreaker().isEmpty()) {
            throw new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION);
        }
        CircuitBreaker breaker =
                circuitBreaker(identity, policy.circuitBreaker().orElseThrow());
        Bulkhead bulkhead =
                policy.bulkhead().map(config -> bulkhead(identity, config)).orElse(null);
        ResolvedResiliencePolicy withoutBreaker = new ResolvedResiliencePolicy(
                policy.timeout(), policy.retry(), java.util.Optional.empty(), java.util.Optional.empty());
        return resilience.adapterPipeline(
                identity, withoutBreaker, breaker, bulkhead, classifier, () -> !closed.get(), this::registerExecution);
    }

    /** Constructs a pipeline around an explicitly shared breaker instance. */
    public ResiliencePipeline pipeline(
            AdapterOperationIdentity identity,
            ResolvedResiliencePolicy policyWithoutCircuitBreaker,
            CircuitBreaker sharedCircuitBreaker,
            CircuitFailureClassifier classifier) {
        ensureOpen();
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(policyWithoutCircuitBreaker, "policyWithoutCircuitBreaker");
        Objects.requireNonNull(sharedCircuitBreaker, "sharedCircuitBreaker");
        Objects.requireNonNull(classifier, "classifier");
        if (policyWithoutCircuitBreaker.circuitBreaker().isPresent()) {
            throw new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION);
        }
        if (!resilience.owns(sharedCircuitBreaker)) {
            throw new IllegalArgumentException("shared circuit breaker must belong to this runtime");
        }
        Bulkhead bulkhead = policyWithoutCircuitBreaker
                .bulkhead()
                .map(config -> bulkhead(identity, config))
                .orElse(null);
        ResolvedResiliencePolicy withoutBulkhead = withoutBulkhead(policyWithoutCircuitBreaker);
        return resilience.adapterPipeline(
                identity,
                withoutBulkhead,
                sharedCircuitBreaker,
                bulkhead,
                classifier,
                () -> !closed.get(),
                this::registerExecution);
    }

    /** Creates and tracks one context-owned breaker for a structured state identity. */
    public CircuitBreaker circuitBreaker(AdapterOperationIdentity stateIdentity, CircuitBreakerConfig config) {
        ensureOpen();
        CircuitBreaker breaker = resilience.adapterCircuitBreaker(
                Objects.requireNonNull(stateIdentity, "stateIdentity"), Objects.requireNonNull(config, "config"));
        breakers.add(breaker);
        return breaker;
    }

    private Bulkhead bulkhead(AdapterOperationIdentity stateIdentity, BulkheadConfig config) {
        ensureOpen();
        Bulkhead bulkhead = resilience.adapterBulkhead(
                Objects.requireNonNull(stateIdentity, "stateIdentity"), Objects.requireNonNull(config, "config"));
        bulkheads.add(bulkhead);
        return bulkhead;
    }

    private static ResolvedResiliencePolicy withoutBulkhead(ResolvedResiliencePolicy policy) {
        return new ResolvedResiliencePolicy(
                policy.timeout(), policy.retry(), policy.circuitBreaker(), java.util.Optional.empty());
    }

    /** Closes the context and all components it created. */
    public Future<Void> close() {
        Set<ExecutionRegistration> executionsToClose;
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return closePromise.future();
            }
            executionsToClose = Set.copyOf(activeExecutionRegistrations);
            activeExecutionRegistrations.clear();
        }
        executionsToClose.forEach(ExecutionRegistration::close);
        if (breakers.isEmpty() && bulkheads.isEmpty()) {
            closePromise.complete();
            return closePromise.future();
        }
        breakers.forEach(breaker -> breaker.close().onComplete(ignored -> completeWhenClosed()));
        bulkheads.forEach(bulkhead -> bulkhead.close().onComplete(ignored -> completeWhenClosed()));
        completeWhenClosed();
        return closePromise.future();
    }

    Runnable registerExecution(Runnable closeAction) {
        Objects.requireNonNull(closeAction, "closeAction");
        ExecutionRegistration registration = new ExecutionRegistration(closeAction);
        synchronized (lifecycleMonitor) {
            if (!closed.get()) {
                activeExecutionRegistrations.add(registration);
                return registration::remove;
            }
        }
        closeAction.run();
        return () -> {};
    }

    boolean isOpen() {
        return !closed.get();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("resilience adapter context is closed");
        }
    }

    private void completeWhenClosed() {
        if (closed.get()
                && breakers.stream().allMatch(breaker -> breaker.close().isComplete())
                && bulkheads.stream().allMatch(bulkhead -> bulkhead.close().isComplete())) {
            closePromise.tryComplete();
        }
    }

    private final class ExecutionRegistration {
        private final Runnable closeAction;
        private boolean removed;

        private ExecutionRegistration(Runnable closeAction) {
            this.closeAction = closeAction;
        }

        private void close() {
            try {
                closeAction.run();
            } finally {
                remove();
            }
        }

        private void remove() {
            synchronized (lifecycleMonitor) {
                if (!removed) {
                    removed = true;
                    activeExecutionRegistrations.remove(this);
                }
            }
        }
    }
}
