// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Immutable operation-level policy overrides. */
public record ResiliencePolicyOverrides(
        Optional<TimeoutOverride> timeout,
        Optional<RetryOverride> retry,
        Optional<CircuitBreakerOverride> circuitBreaker,
        Optional<BulkheadOverride> bulkhead) {

    public ResiliencePolicyOverrides {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        Objects.requireNonNull(bulkhead, "bulkhead");
    }

    public static ResiliencePolicyOverrides none() {
        return new ResiliencePolicyOverrides(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Layers this higher-precedence override over a lower-precedence override.
     *
     * <p>Every optional leaf present on this instance wins and an absent leaf is taken from
     * {@code lower}. A disabled higher concern wins as a whole, and a higher custom backoff
     * strategy is not combined with scalar fields from a lower backoff.
     *
     * @param lower the lower-precedence override
     * @return the combined partial override
     * @throws NullPointerException if {@code lower} is {@code null}
     */
    public ResiliencePolicyOverrides over(ResiliencePolicyOverrides lower) {
        Objects.requireNonNull(lower, "lower");
        return new ResiliencePolicyOverrides(
                mergeTimeout(timeout, lower.timeout),
                mergeRetry(retry, lower.retry),
                mergeCircuitBreaker(circuitBreaker, lower.circuitBreaker),
                mergeBulkhead(bulkhead, lower.bulkhead));
    }

    private static Optional<TimeoutOverride> mergeTimeout(
            Optional<TimeoutOverride> higher, Optional<TimeoutOverride> lower) {
        if (higher.isEmpty()) {
            return lower;
        }
        TimeoutOverride high = higher.orElseThrow();
        if (isDisabled(high.enabled()) || lower.isEmpty()) {
            return higher;
        }
        TimeoutOverride low = lower.orElseThrow();
        return Optional.of(new TimeoutOverride(
                mergeEnabled(high.enabled(), low.enabled(), high.timeoutMs().isPresent()),
                prefer(high.timeoutMs(), low.timeoutMs())));
    }

    private static Optional<RetryOverride> mergeRetry(Optional<RetryOverride> higher, Optional<RetryOverride> lower) {
        if (higher.isEmpty()) {
            return lower;
        }
        RetryOverride high = higher.orElseThrow();
        if (isDisabled(high.enabled()) || lower.isEmpty()) {
            return higher;
        }
        RetryOverride low = lower.orElseThrow();
        return Optional.of(new RetryOverride(
                mergeEnabled(high.enabled(), low.enabled(), hasRetrySiblingValue(high)),
                prefer(high.maxRetries(), low.maxRetries()),
                mergeBackoff(high.backoff(), low.backoff()),
                prefer(high.retryOn(), low.retryOn()),
                prefer(high.abortOn(), low.abortOn()),
                prefer(high.fallbackPolicy(), low.fallbackPolicy())));
    }

    private static Optional<CircuitBreakerOverride> mergeCircuitBreaker(
            Optional<CircuitBreakerOverride> higher, Optional<CircuitBreakerOverride> lower) {
        if (higher.isEmpty()) {
            return lower;
        }
        CircuitBreakerOverride high = higher.orElseThrow();
        if (isDisabled(high.enabled()) || lower.isEmpty()) {
            return higher;
        }
        CircuitBreakerOverride low = lower.orElseThrow();
        return Optional.of(new CircuitBreakerOverride(
                mergeEnabled(
                        high.enabled(),
                        low.enabled(),
                        high.maxFailures().isPresent() || high.resetTimeoutMs().isPresent()),
                prefer(high.maxFailures(), low.maxFailures()),
                prefer(high.resetTimeoutMs(), low.resetTimeoutMs())));
    }

    private static Optional<BulkheadOverride> mergeBulkhead(
            Optional<BulkheadOverride> higher, Optional<BulkheadOverride> lower) {
        if (higher.isEmpty()) {
            return lower;
        }
        BulkheadOverride high = higher.orElseThrow();
        if (isDisabled(high.enabled()) || lower.isEmpty()) {
            return higher;
        }
        BulkheadOverride low = lower.orElseThrow();
        return Optional.of(new BulkheadOverride(
                mergeEnabled(high.enabled(), low.enabled(), high.config().isPresent()),
                prefer(high.config(), low.config())));
    }

    private static Optional<BackoffOverride> mergeBackoff(
            Optional<BackoffOverride> higher, Optional<BackoffOverride> lower) {
        if (higher.isEmpty()) {
            return lower;
        }
        BackoffOverride high = higher.orElseThrow();
        if (lower.isEmpty()
                || high.custom().isPresent()
                || (operationHasScalar(high) && lower.orElseThrow().custom().isPresent())) {
            return higher;
        }
        BackoffOverride low = lower.orElseThrow();
        return Optional.of(new BackoffOverride(
                prefer(high.custom(), low.custom()),
                prefer(high.initialDelayMs(), low.initialDelayMs()),
                prefer(high.multiplier(), low.multiplier()),
                prefer(high.maxDelayMs(), low.maxDelayMs()),
                prefer(high.maxJitterMs(), low.maxJitterMs())));
    }

    private static boolean operationHasScalar(BackoffOverride value) {
        return value.initialDelayMs().isPresent()
                || value.multiplier().isPresent()
                || value.maxDelayMs().isPresent()
                || value.maxJitterMs().isPresent();
    }

    private static boolean hasRetrySiblingValue(RetryOverride value) {
        return value.maxRetries().isPresent()
                || value.backoff().isPresent()
                || value.retryOn().isPresent()
                || value.abortOn().isPresent()
                || value.fallbackPolicy().isPresent();
    }

    private static boolean isDisabled(Optional<Boolean> enabled) {
        return enabled.isPresent() && !enabled.orElseThrow();
    }

    private static Optional<Boolean> mergeEnabled(
            Optional<Boolean> higher, Optional<Boolean> lower, boolean higherHasSiblingValue) {
        if (higher.isPresent()) {
            return higher;
        }
        return higherHasSiblingValue && isDisabled(lower) ? Optional.empty() : lower;
    }

    private static <T> Optional<T> prefer(Optional<T> higher, Optional<T> lower) {
        return higher.isPresent() ? higher : lower;
    }

    private static OptionalInt prefer(OptionalInt higher, OptionalInt lower) {
        return higher.isPresent() ? higher : lower;
    }

    private static OptionalLong prefer(OptionalLong higher, OptionalLong lower) {
        return higher.isPresent() ? higher : lower;
    }
}
