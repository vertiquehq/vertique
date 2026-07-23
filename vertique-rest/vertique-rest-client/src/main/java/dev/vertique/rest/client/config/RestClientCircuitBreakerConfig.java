// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Typed per-client circuit-breaker override read from {@code restClient.{name}.circuitBreaker}.
 *
 * <p>Every component is a nullable boxed value: an absent field means "not overridden" and falls
 * back to the builder-level or annotation-level circuit-breaker baseline. Present values are
 * validated at parse time so malformed config fails fast at startup rather than at first use:
 * {@code maxFailures >= 1}, {@code timeoutMs > 0}, {@code resetTimeoutMs > 0}, and
 * {@code maxRetries >= 0}.
 *
 * @param maxFailures the failure threshold before the breaker opens ({@code >= 1}), or {@code null}
 *     when not overridden
 * @param timeoutMs the per-call timeout in milliseconds ({@code > 0}), or {@code null} when not
 *     overridden
 * @param resetTimeoutMs the open→half-open reset timeout in milliseconds ({@code > 0}), or
 *     {@code null} when not overridden
 * @param maxRetries the number of automatic retries the breaker performs ({@code >= 0}), or
 *     {@code null} when not overridden
 */
public record RestClientCircuitBreakerConfig(
        Integer maxFailures, Long timeoutMs, Long resetTimeoutMs, Integer maxRetries) {

    /**
     * Compact validator enforcing the per-field bounds for present values. Absent ({@code null})
     * fields are left untouched.
     *
     * @throws ConfigurationException if any present value is out of bounds
     */
    public RestClientCircuitBreakerConfig {
        if (maxFailures != null && maxFailures < 1) {
            throw new ConfigurationException(
                    "restClient.<name>.circuitBreaker.maxFailures must be >= 1, got " + maxFailures);
        }
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new ConfigurationException(
                    "restClient.<name>.circuitBreaker.timeoutMs must be > 0, got " + timeoutMs);
        }
        if (resetTimeoutMs != null && resetTimeoutMs <= 0) {
            throw new ConfigurationException(
                    "restClient.<name>.circuitBreaker.resetTimeoutMs must be > 0, got " + resetTimeoutMs);
        }
        if (maxRetries != null && maxRetries < 0) {
            throw new ConfigurationException(
                    "restClient.<name>.circuitBreaker.maxRetries must be >= 0, got " + maxRetries);
        }
    }
}
