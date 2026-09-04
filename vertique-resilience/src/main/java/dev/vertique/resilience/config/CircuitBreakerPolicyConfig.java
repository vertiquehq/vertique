// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.config;

import dev.vertique.resilience.CircuitBreakerOverride;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Partial named-tier circuit-breaker configuration.
 *
 * @param maxFailures the optional failure threshold
 * @param resetTimeoutMs the optional half-open reset timeout in milliseconds
 */
public record CircuitBreakerPolicyConfig(Integer maxFailures, Long resetTimeoutMs) {

    /**
     * Converts this configuration to raw, partial operation overrides without filling defaults.
     *
     * @return the raw circuit-breaker override represented by this configuration
     */
    public CircuitBreakerOverride toOverrides() {
        return new CircuitBreakerOverride(Optional.empty(), optionalInt(maxFailures), optionalLong(resetTimeoutMs));
    }

    private static OptionalInt optionalInt(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
