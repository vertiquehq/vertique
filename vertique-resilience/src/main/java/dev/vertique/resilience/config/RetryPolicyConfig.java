// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.config;

import dev.vertique.resilience.BackoffOverride;
import dev.vertique.resilience.RetryOverride;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Partial named-tier retry configuration.
 *
 * @param maxRetries the retry count, required when the retry concern is configured
 * @param delayMs the optional initial delay in milliseconds
 * @param backoffMultiplier the optional exponential multiplier
 * @param maxDelayMs the optional maximum delay in milliseconds
 */
public record RetryPolicyConfig(Integer maxRetries, Long delayMs, Double backoffMultiplier, Long maxDelayMs) {

    /**
     * Converts this configuration to raw, partial operation overrides without filling defaults.
     *
     * @return the raw retry override represented by this configuration
     */
    public RetryOverride toOverrides() {
        Optional<BackoffOverride> backoff = delayMs == null && backoffMultiplier == null && maxDelayMs == null
                ? Optional.empty()
                : Optional.of(new BackoffOverride(
                        Optional.empty(),
                        optionalLong(delayMs),
                        Optional.ofNullable(backoffMultiplier),
                        optionalLong(maxDelayMs),
                        OptionalLong.empty()));
        return new RetryOverride(
                Optional.empty(),
                optionalInt(maxRetries),
                backoff,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static OptionalInt optionalInt(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
