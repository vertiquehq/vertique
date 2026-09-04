// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.config;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.BulkheadConfig;
import dev.vertique.resilience.BulkheadOverride;
import dev.vertique.resilience.CircuitBreakerOverride;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.Bulkhead;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Named resilience policy tier read from {@code resilience.policies.<name>}.
 *
 * <p>The nested records retain nullable values so this tier remains a raw partial override. Bounds
 * are validated here, where the policy name is available for the stable configuration path.
 *
 * @param name the policy identity, matching {@code [A-Za-z0-9._~-]{1,128}}
 * @param timeout the optional timeout concern
 * @param retry the optional retry concern
 * @param circuitBreaker the optional circuit-breaker concern
 * @param bulkhead the optional bulkhead concern
 */
public record ResiliencePolicyConfig(
        String name,
        TimeoutPolicyConfig timeout,
        RetryPolicyConfig retry,
        CircuitBreakerPolicyConfig circuitBreaker,
        BulkheadPolicyConfig bulkhead) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    /** Validates policy identity, concern presence, and all nested concern bounds. */
    public ResiliencePolicyConfig {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ConfigurationException("resilience.policies[].name must match [A-Za-z0-9._~-]{1,128}");
        }
        if (timeout == null && retry == null && circuitBreaker == null && bulkhead == null) {
            throw new ConfigurationException("resilience.policies." + name
                    + " must configure at least one of timeout, retry, circuitBreaker, bulkhead");
        }
        validateTimeout(name, timeout);
        validateRetry(name, retry);
        validateCircuitBreaker(name, circuitBreaker);
        validateBulkhead(name, bulkhead);
    }

    /**
     * Converts this tier to raw partial overrides without filling declaration defaults.
     *
     * @return raw partial overrides represented by this named tier
     */
    public ResiliencePolicyOverrides toOverrides() {
        Optional<TimeoutOverride> timeoutOverride = timeout == null
                ? Optional.empty()
                : Optional.of(new TimeoutOverride(Optional.empty(), OptionalLong.of(timeout.valueMs())));
        Optional<RetryOverride> retryOverride = retry == null ? Optional.empty() : Optional.of(retry.toOverrides());
        Optional<CircuitBreakerOverride> circuitBreakerOverride =
                circuitBreaker == null ? Optional.empty() : Optional.of(circuitBreaker.toOverrides());
        Optional<BulkheadOverride> bulkheadOverride = bulkhead == null
                ? Optional.empty()
                : Optional.of(new BulkheadOverride(Optional.empty(), Optional.of(toBulkheadConfig(bulkhead))));
        return new ResiliencePolicyOverrides(timeoutOverride, retryOverride, circuitBreakerOverride, bulkheadOverride);
    }

    private static void validateTimeout(String name, TimeoutPolicyConfig value) {
        if (value == null) {
            return;
        }
        if (value.valueMs() == null) {
            throw new ConfigurationException("resilience.policies." + name + ".timeout.valueMs is required");
        }
        if (value.valueMs() <= 0) {
            throw new ConfigurationException(
                    "resilience.policies." + name + ".timeout.valueMs must be > 0, got " + value.valueMs());
        }
    }

    private static void validateRetry(String name, RetryPolicyConfig value) {
        if (value == null) {
            return;
        }
        String path = "resilience.policies." + name + ".retry.";
        if (value.maxRetries() == null) {
            throw new ConfigurationException(path + "maxRetries is required");
        }
        if (value.maxRetries() < 0 || value.maxRetries() > 100) {
            throw new ConfigurationException(path + "maxRetries must be between 0 and 100, got " + value.maxRetries());
        }
        if (value.delayMs() != null && value.delayMs() < 0) {
            throw new ConfigurationException(path + "delayMs must be >= 0, got " + value.delayMs());
        }
        if (value.backoffMultiplier() != null
                && (!Double.isFinite(value.backoffMultiplier()) || value.backoffMultiplier() < 1.0d)) {
            throw new ConfigurationException(
                    path + "backoffMultiplier must be finite and >= 1.0, got " + value.backoffMultiplier());
        }
        if (value.maxDelayMs() != null && value.maxDelayMs() < 0) {
            throw new ConfigurationException(path + "maxDelayMs must be >= 0, got " + value.maxDelayMs());
        }
    }

    private static void validateCircuitBreaker(String name, CircuitBreakerPolicyConfig value) {
        if (value == null) {
            return;
        }
        String path = "resilience.policies." + name + ".circuitBreaker.";
        if (value.maxFailures() != null && value.maxFailures() < 1) {
            throw new ConfigurationException(path + "maxFailures must be >= 1, got " + value.maxFailures());
        }
        if (value.resetTimeoutMs() != null && value.resetTimeoutMs() <= 0) {
            throw new ConfigurationException(path + "resetTimeoutMs must be > 0, got " + value.resetTimeoutMs());
        }
    }

    private static void validateBulkhead(String name, BulkheadPolicyConfig value) {
        if (value == null) {
            return;
        }
        String path = "resilience.policies." + name + ".bulkhead.";
        if (value.maxConcurrentCalls() == null) {
            throw new ConfigurationException(path + "maxConcurrentCalls is required");
        }
        if (value.maxConcurrentCalls() <= 0) {
            throw new ConfigurationException(
                    path + "maxConcurrentCalls must be > 0, got " + value.maxConcurrentCalls());
        }
        if (value.mode() == Bulkhead.Mode.REJECT) {
            if (value.maxQueueSize() != null) {
                throw new ConfigurationException(path + "maxQueueSize must be absent when mode=REJECT");
            }
            if (value.queueTimeoutMs() != null) {
                throw new ConfigurationException(path + "queueTimeoutMs must be absent when mode=REJECT");
            }
            return;
        }
        if (value.maxQueueSize() == null) {
            throw new ConfigurationException(path + "maxQueueSize is required when mode=QUEUE");
        }
        if (value.maxQueueSize() < 1 || value.maxQueueSize() > 1_024) {
            throw new ConfigurationException(
                    path + "maxQueueSize must be between 1 and 1024, got " + value.maxQueueSize());
        }
        if (value.queueTimeoutMs() == null) {
            throw new ConfigurationException(path + "queueTimeoutMs is required when mode=QUEUE");
        }
        if (value.queueTimeoutMs() < 1 || value.queueTimeoutMs() > 60_000L) {
            throw new ConfigurationException(
                    path + "queueTimeoutMs must be between 1 and 60000, got " + value.queueTimeoutMs());
        }
    }

    private static BulkheadConfig toBulkheadConfig(BulkheadPolicyConfig value) {
        return value.mode() == Bulkhead.Mode.REJECT
                ? BulkheadConfig.reject(value.maxConcurrentCalls())
                : BulkheadConfig.queue(
                        value.maxConcurrentCalls(), value.maxQueueSize(), Duration.ofMillis(value.queueTimeoutMs()));
    }
}
