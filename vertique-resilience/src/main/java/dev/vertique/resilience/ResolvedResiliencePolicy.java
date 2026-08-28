// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;

/**
 * Complete immutable policy selected for one operation.
 *
 * @param timeout selected per-attempt timeout
 * @param retry selected retry configuration
 * @param circuitBreaker selected circuit-breaker configuration
 * @param bulkhead selected bulkhead configuration
 */
public record ResolvedResiliencePolicy(
        Optional<TimeoutConfig> timeout,
        Optional<RetryConfig> retry,
        Optional<CircuitBreakerConfig> circuitBreaker,
        Optional<BulkheadConfig> bulkhead) {

    /** Validates that each optional container is present and non-null. */
    public ResolvedResiliencePolicy {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        Objects.requireNonNull(bulkhead, "bulkhead");
    }

    /**
     * Returns whether no resilience concern is enabled.
     *
     * @return {@code true} when every concern is absent
     */
    public boolean isEmpty() {
        return timeout.isEmpty() && retry.isEmpty() && circuitBreaker.isEmpty() && bulkhead.isEmpty();
    }

    /**
     * Calculates active and queue-wait budgets from the complete policy.
     *
     * @return the policy's conservative execution budget
     */
    public ExecutionBudget executionBudget() {
        DurationBound active = activeExecutionBudget();
        DurationBound queue =
                bulkhead.map(ResolvedResiliencePolicy::queueBudget).orElseGet(() -> new DurationBound.Known(0L, false));
        return new ExecutionBudget(active, queue);
    }

    private DurationBound activeExecutionBudget() {
        if (timeout.isEmpty()) {
            if (retry.isEmpty()) {
                return new DurationBound.Known(0L, false);
            }
            return new DurationBound.Unbounded();
        }

        long attempts = retry.map(value -> (long) value.maxRetries() + 1L).orElse(1L);
        long attemptBudget =
                ExecutionBudget.saturatingMultiply(timeout.orElseThrow().timeoutMs(), attempts);
        if (retry.isEmpty()) {
            return known(attemptBudget);
        }

        RetryBackoff backoff = retry.orElseThrow().backoff();
        if (backoff instanceof RetryBackoff.Custom) {
            return new DurationBound.Unknown(ExecutionBudgetUnknownReason.CUSTOM_BACKOFF);
        }

        long delayBudget = maximumDelayBudget(backoff, retry.orElseThrow().maxRetries());
        return known(ExecutionBudget.saturatingAdd(attemptBudget, delayBudget));
    }

    private static DurationBound queueBudget(BulkheadConfig configuration) {
        if (configuration instanceof BulkheadConfig.Queue queue) {
            return known(queue.queueTimeoutMs());
        }
        return new DurationBound.Known(0L, false);
    }

    private static long maximumDelayBudget(RetryBackoff backoff, int maxRetries) {
        if (backoff instanceof RetryBackoff.Fixed fixed) {
            return ExecutionBudget.saturatingMultiply(fixed.delayMs(), maxRetries);
        }

        RetryBackoff.Exponential exponential = (RetryBackoff.Exponential) backoff;
        long total = 0L;
        for (int retryCount = 0; retryCount < maxRetries; retryCount++) {
            long cappedBase = Retry.cappedExponentialDelay(exponential, retryCount);
            long jitterUpperBound = Math.min(cappedBase, exponential.maxJitterMs());
            total = ExecutionBudget.saturatingAdd(total, ExecutionBudget.saturatingAdd(cappedBase, jitterUpperBound));
        }
        return total;
    }

    private static DurationBound.Known known(long valueMs) {
        return new DurationBound.Known(valueMs, valueMs == Long.MAX_VALUE);
    }
}
