// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Per-operation {@code circuitBreaker} policy override read from
 * {@code services.contracts.{namespace}.{name}.operations.{operation}.circuitBreaker}.
 *
 * <p>Every component is a nullable boxed value: {@code null} means "not overridden" — the effective
 * value falls back to the {@code @CircuitBreaker} annotation per
 * {@link dev.vertique.services.resilience.ServiceResilienceConfigAdapter}. No defaulting is applied here. Present
 * values are validated at parse time so malformed config fails fast at startup rather than at first
 * use: {@code maxFailures >= 1}, {@code timeoutMs > 0}, {@code resetTimeoutMs > 0} (matching the
 * {@code rest-client} {@code RestClientCircuitBreakerConfig} precedent).
 *
 * <p>There is deliberately no operation-level {@code maxRetries} on the circuit breaker; retry count
 * is owned by {@link RetryOverride}.
 *
 * @param maxFailures the failure threshold before the circuit trips ({@code >= 1} when present), or
 *     {@code null} when not overridden
 * @param timeoutMs the circuit breaker call timeout in milliseconds ({@code > 0} when present), or
 *     {@code null} when not overridden
 * @param resetTimeoutMs the half-open reset timeout in milliseconds ({@code > 0} when present), or
 *     {@code null} when not overridden
 */
public record CircuitBreakerOverride(Integer maxFailures, Long timeoutMs, Long resetTimeoutMs) {

    /**
     * Compact validator enforcing the per-field bounds for present values. Absent ({@code null})
     * fields are left untouched ("not overridden").
     *
     * @throws ConfigurationException if {@code maxFailures < 1}, {@code timeoutMs <= 0}, or
     *     {@code resetTimeoutMs <= 0} for any present value
     */
    public CircuitBreakerOverride {
        if (maxFailures != null && maxFailures < 1) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.circuitBreaker.maxFailures must be >= 1, got "
                            + maxFailures);
        }
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.circuitBreaker.timeoutMs must be > 0, got "
                            + timeoutMs);
        }
        if (resetTimeoutMs != null && resetTimeoutMs <= 0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.circuitBreaker.resetTimeoutMs must be > 0, got "
                            + resetTimeoutMs);
        }
    }
}
