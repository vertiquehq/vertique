// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.exception.BulkheadQueueTimeoutException;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.BulkheadAdmitted;
import dev.vertique.resilience.spi.event.BulkheadMode;
import dev.vertique.resilience.spi.event.BulkheadQueueTimedOut;
import dev.vertique.resilience.spi.event.BulkheadQueued;
import dev.vertique.resilience.spi.event.BulkheadRejected;
import dev.vertique.resilience.spi.event.CircuitCallRejected;
import dev.vertique.resilience.spi.event.CircuitState;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ExecutionStarted;
import dev.vertique.resilience.spi.event.PolicyEvaluationFailed;
import dev.vertique.resilience.spi.event.ResilienceConcern;
import dev.vertique.resilience.spi.event.ResilienceFailureCategory;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import dev.vertique.resilience.spi.event.RetryExhausted;
import dev.vertique.resilience.spi.event.RetryScheduled;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
import io.vertx.core.Future;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Package-private correlation and event-emission context for one logical execution. */
final class ResilienceExecutionObservation {

    private final Resilience resilience;
    private final String operationKey;
    private final long executionId;
    private final Set<ResilienceConcern> concerns;
    private final long startedNanos = System.nanoTime();
    private final AtomicInteger nextAttempt = new AtomicInteger();

    ResilienceExecutionObservation(Resilience resilience, String operationKey, Set<ResilienceConcern> concerns) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.operationKey = Objects.requireNonNull(operationKey, "operationKey");
        this.executionId = resilience.nextExecutionId();
        this.concerns = Set.copyOf(Objects.requireNonNull(concerns, "concerns"));
        resilience.emit(new ExecutionStarted(operationKey, executionId, this.concerns));
    }

    long executionId() {
        return executionId;
    }

    int attemptStarted() {
        int ordinal = nextAttempt.incrementAndGet();
        resilience.emit(new AttemptStarted(operationKey, executionId, ordinal));
        return ordinal;
    }

    <T> Future<T> executeAttempt(Supplier<Future<T>> operation) {
        int ordinal = attemptStarted();
        try {
            Future<T> supplied = Objects.requireNonNull(operation.get(), "operation returned null future");
            supplied.onComplete(outcome -> attemptCompleted(ordinal, outcome.succeeded() ? null : outcome.cause()));
            return supplied;
        } catch (Exception failure) {
            attemptCompleted(ordinal, failure);
            return Future.failedFuture(failure);
        } catch (Error fatal) {
            attemptCompleted(ordinal, fatal);
            throw fatal;
        }
    }

    void attemptCompleted(int ordinal, Throwable failure) {
        resilience.emit(new AttemptCompleted(operationKey, executionId, ordinal, outcome(failure), elapsedMs()));
    }

    void timeoutTriggered(int ordinal, long timeoutMs) {
        resilience.emit(new TimeoutTriggered(operationKey, executionId, ordinal, timeoutMs));
    }

    void retryScheduled(int failedOrdinal, int nextOrdinal, long delayMs, Throwable failure) {
        resilience.emit(new RetryScheduled(
                operationKey, executionId, failedOrdinal, nextOrdinal, delayMs, failureCategory(failure)));
    }

    void retryExhausted(int attemptsMade, Throwable failure) {
        resilience.emit(new RetryExhausted(operationKey, executionId, attemptsMade, failureCategory(failure)));
    }

    void policyEvaluationFailed(ResilienceConcern concern, PolicyCallbackKind callbackKind, Throwable failure) {
        resilience.emit(new PolicyEvaluationFailed(
                operationKey,
                executionId,
                concern,
                callbackKind,
                failure.getClass().getName()));
    }

    void bulkheadQueued(int queueDepth, int queueCapacity) {
        resilience.emit(new BulkheadQueued(operationKey, executionId, queueDepth, queueCapacity));
    }

    void bulkheadAdmitted(long queueWaitMs, int activeCount) {
        resilience.emit(new BulkheadAdmitted(operationKey, executionId, queueWaitMs, activeCount));
    }

    void bulkheadRejected(BulkheadMode mode, int activeCount, int queueDepth, int queueCapacity) {
        resilience.emit(new BulkheadRejected(operationKey, executionId, mode, activeCount, queueDepth, queueCapacity));
    }

    void bulkheadQueueTimedOut(long queueWaitMs, long configuredQueueTimeoutMs) {
        resilience.emit(new BulkheadQueueTimedOut(operationKey, executionId, queueWaitMs, configuredQueueTimeoutMs));
    }

    void circuitRejected(String stateKey, CircuitState currentState) {
        resilience.emit(new CircuitCallRejected(operationKey, executionId, stateKey, currentState));
    }

    void completed(Throwable failure) {
        resilience.emit(new ExecutionCompleted(operationKey, executionId, outcome(failure), elapsedMs()));
    }

    private long elapsedMs() {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static ResilienceOutcomeCategory outcome(Throwable failure) {
        if (failure == null) {
            return ResilienceOutcomeCategory.SUCCESS;
        }
        if (failure instanceof ResilienceTimeoutException) {
            return ResilienceOutcomeCategory.TIMEOUT;
        }
        if (failure instanceof CircuitOpenException) {
            return ResilienceOutcomeCategory.CIRCUIT_OPEN;
        }
        if (failure instanceof BulkheadRejectedException) {
            return ResilienceOutcomeCategory.BULKHEAD_REJECTED;
        }
        if (failure instanceof BulkheadQueueTimeoutException) {
            return ResilienceOutcomeCategory.BULKHEAD_QUEUE_TIMEOUT;
        }
        if (failure instanceof ResilienceClosedException) {
            return ResilienceOutcomeCategory.RUNTIME_CLOSED;
        }
        if (failure instanceof ResiliencePolicyException) {
            return ResilienceOutcomeCategory.POLICY_FAILURE;
        }
        return ResilienceOutcomeCategory.APPLICATION_FAILURE;
    }

    private static ResilienceFailureCategory failureCategory(Throwable failure) {
        if (failure instanceof ResilienceTimeoutException) {
            return ResilienceFailureCategory.TIMEOUT;
        }
        if (failure instanceof ResilienceClosedException) {
            return ResilienceFailureCategory.RUNTIME_CLOSED;
        }
        if (failure instanceof ResiliencePolicyException) {
            return ResilienceFailureCategory.POLICY_FAILURE;
        }
        return ResilienceFailureCategory.APPLICATION_FAILURE;
    }
}
