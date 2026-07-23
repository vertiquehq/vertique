// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Per-operation {@code retry} policy override read from
 * {@code services.contracts.{namespace}.{name}.operations.{operation}.retry}.
 *
 * <p>Every component is a nullable boxed value: {@code null} means "not overridden" — the effective
 * value falls back to the {@code @Retry} annotation per
 * {@link dev.vertique.services.policy.PolicyChainBuilder}. No defaulting is applied here. Present
 * values are validated at parse time so a malformed override fails fast at startup rather than
 * flowing into the resilience pipeline. The bounds match the {@code @Retry} annotation semantics:
 * {@code maxRetries >= 0}, {@code delayMs >= 0}, {@code maxDelayMs >= 0}, and
 * {@code backoffMultiplier >= 1.0} (a multiplier of {@code 1.0} is a fixed delay; a value below
 * {@code 1.0} would shrink the delay each attempt, contradicting backoff semantics).
 *
 * @param maxRetries the maximum number of retry attempts ({@code >= 0} when present), or {@code null}
 *     when not overridden
 * @param delayMs the initial backoff delay in milliseconds ({@code >= 0} when present), or
 *     {@code null} when not overridden
 * @param backoffMultiplier the exponential backoff multiplier ({@code >= 1.0} when present), or
 *     {@code null} when not overridden
 * @param maxDelayMs the maximum backoff delay in milliseconds ({@code >= 0} when present), or
 *     {@code null} when not overridden
 */
public record RetryOverride(Integer maxRetries, Long delayMs, Double backoffMultiplier, Long maxDelayMs) {

    /**
     * Compact validator enforcing the per-field bounds for present values. Absent ({@code null})
     * fields are left untouched ("not overridden").
     *
     * @throws ConfigurationException if {@code maxRetries < 0}, {@code delayMs < 0},
     *     {@code backoffMultiplier < 1.0}, or {@code maxDelayMs < 0} for any present value
     */
    public RetryOverride {
        if (maxRetries != null && maxRetries < 0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.retry.maxRetries must be >= 0, got " + maxRetries);
        }
        if (delayMs != null && delayMs < 0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.retry.delayMs must be >= 0, got " + delayMs);
        }
        if (backoffMultiplier != null && backoffMultiplier < 1.0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.retry.backoffMultiplier must be >= 1.0, got "
                            + backoffMultiplier);
        }
        if (maxDelayMs != null && maxDelayMs < 0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.retry.maxDelayMs must be >= 0, got " + maxDelayMs);
        }
    }
}
