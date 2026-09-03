// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Named resilience policy tiers and their caller-side precedence layering. */
public interface ResiliencePolicyRegistry {

    /**
     * Returns the named policy tier.
     *
     * @param name the configured policy name
     * @return the raw partial policy overrides
     * @throws ConfigurationException if {@code name} is not configured, with message
     *     {@code resilience.policies.<name> is not defined}
     */
    ResiliencePolicyOverrides require(String name);

    /**
     * Layers the selected named tier below transport overrides.
     *
     * <p>When a policy is selected, retry and circuit-breaker tier fields are completed from their
     * declaration defaults only when that concern has no declaration. A concern absent from the
     * tier is never added. When no policy is selected, the transport overrides are returned
     * unchanged.
     *
     * @param annotations resolved resilience annotations
     * @param transportOverrides higher-precedence transport overrides
     * @return the layered operation overrides
     * @throws ConfigurationException if the selected policy is not defined
     * @throws NullPointerException if an argument is {@code null}
     */
    default ResiliencePolicyOverrides layer(
            ResilienceAnnotations annotations, ResiliencePolicyOverrides transportOverrides) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(transportOverrides, "transportOverrides");
        Optional<String> policy = annotations.policy();
        if (policy.isEmpty()) {
            return transportOverrides;
        }
        ResiliencePolicyOverrides tier = require(policy.orElseThrow());
        ResiliencePolicyOverrides completedTier = completeTier(tier, annotations);
        return transportOverrides.over(completedTier);
    }

    /**
     * Returns a registry with no configured policy tiers.
     *
     * @return an empty registry
     */
    static ResiliencePolicyRegistry empty() {
        return of(List.of());
    }

    /**
     * Creates an immutable registry from named policy configurations.
     *
     * @param policies policy configurations to index by name
     * @return the immutable registry
     * @throws NullPointerException if {@code policies} or an element is {@code null}
     * @throws ConfigurationException if two configurations use the same name
     */
    static ResiliencePolicyRegistry of(Collection<ResiliencePolicyConfig> policies) {
        Objects.requireNonNull(policies, "policies");
        Map<String, ResiliencePolicyOverrides> byName = new LinkedHashMap<>();
        for (ResiliencePolicyConfig policy : policies) {
            Objects.requireNonNull(policy, "policy");
            if (byName.put(policy.name(), policy.toOverrides()) != null) {
                throw new ConfigurationException("resilience.policies." + policy.name() + " must be defined only once");
            }
        }
        Map<String, ResiliencePolicyOverrides> index = Map.copyOf(byName);
        return name -> {
            ResiliencePolicyOverrides overrides = index.get(name);
            if (overrides == null) {
                throw new ConfigurationException("resilience.policies." + name + " is not defined");
            }
            return overrides;
        };
    }

    private static ResiliencePolicyOverrides completeTier(
            ResiliencePolicyOverrides tier, ResilienceAnnotations annotations) {
        return new ResiliencePolicyOverrides(
                tier.timeout(),
                annotations.retry().isEmpty() ? completeRetry(tier.retry()) : tier.retry(),
                annotations.circuitBreaker().isEmpty()
                        ? completeCircuitBreaker(tier.circuitBreaker())
                        : tier.circuitBreaker(),
                tier.bulkhead());
    }

    private static Optional<RetryOverride> completeRetry(Optional<RetryOverride> configured) {
        if (configured.isEmpty()) {
            return configured;
        }
        RetryOverride retry = configured.orElseThrow();
        if (retry.enabled().filter(enabled -> !enabled).isPresent()
                || retry.backoff().filter(value -> value.custom().isPresent()).isPresent()) {
            return configured;
        }
        BackoffOverride backoff = retry.backoff().orElse(null);
        return Optional.of(new RetryOverride(
                retry.enabled(),
                retry.maxRetries(),
                Optional.of(completeBackoff(backoff)),
                retry.retryOn(),
                retry.abortOn(),
                retry.fallbackPolicy()));
    }

    private static BackoffOverride completeBackoff(BackoffOverride configured) {
        if (configured == null) {
            return new BackoffOverride(
                    Optional.empty(),
                    OptionalLong.of(500L),
                    Optional.of(2.0d),
                    OptionalLong.of(30_000L),
                    OptionalLong.empty());
        }
        return new BackoffOverride(
                configured.custom(),
                configured.initialDelayMs().isPresent() ? configured.initialDelayMs() : OptionalLong.of(500L),
                configured.multiplier().isPresent() ? configured.multiplier() : Optional.of(2.0d),
                configured.maxDelayMs().isPresent() ? configured.maxDelayMs() : OptionalLong.of(30_000L),
                configured.maxJitterMs());
    }

    private static Optional<CircuitBreakerOverride> completeCircuitBreaker(
            Optional<CircuitBreakerOverride> configured) {
        if (configured.isEmpty()) {
            return configured;
        }
        CircuitBreakerOverride breaker = configured.orElseThrow();
        if (breaker.enabled().filter(enabled -> !enabled).isPresent()) {
            return configured;
        }
        return Optional.of(new CircuitBreakerOverride(
                breaker.enabled(),
                breaker.maxFailures().isPresent() ? breaker.maxFailures() : OptionalInt.of(5),
                breaker.resetTimeoutMs().isPresent() ? breaker.resetTimeoutMs() : OptionalLong.of(10_000L)));
    }
}
