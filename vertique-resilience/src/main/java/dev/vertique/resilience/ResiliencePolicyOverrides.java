// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;

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
}
