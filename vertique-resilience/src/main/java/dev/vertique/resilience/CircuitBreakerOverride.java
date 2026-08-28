// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Partial operation-level circuit-breaker configuration. */
public record CircuitBreakerOverride(Optional<Boolean> enabled, OptionalInt maxFailures, OptionalLong resetTimeoutMs) {
    public CircuitBreakerOverride {
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(maxFailures, "maxFailures");
        Objects.requireNonNull(resetTimeoutMs, "resetTimeoutMs");
        if (maxFailures.isPresent() && maxFailures.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
        if (resetTimeoutMs.isPresent() && resetTimeoutMs.getAsLong() <= 0) {
            throw new IllegalArgumentException("resetTimeoutMs must be positive");
        }
        if (enabled.orElse(null) != null
                && !enabled.orElseThrow()
                && (maxFailures.isPresent() || resetTimeoutMs.isPresent())) {
            throw new IllegalArgumentException("disabled circuit-breaker override cannot contain values");
        }
    }
}
