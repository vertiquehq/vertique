// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;

/** Immutable complete policy defaults supplied by a framework adapter. */
public record ResilienceDefaults(
        Optional<TimeoutConfig> timeout,
        Optional<RetryConfig> retry,
        Optional<CircuitBreakerConfig> circuitBreaker,
        Optional<BulkheadConfig> bulkhead) {

    public ResilienceDefaults {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        Objects.requireNonNull(bulkhead, "bulkhead");
    }

    public static ResilienceDefaults none() {
        return new ResilienceDefaults(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
