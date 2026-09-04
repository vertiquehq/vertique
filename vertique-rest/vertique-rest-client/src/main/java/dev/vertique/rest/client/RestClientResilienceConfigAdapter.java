// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.resilience.BackoffOverride;
import dev.vertique.resilience.CircuitBreakerConfig;
import dev.vertique.resilience.CircuitBreakerOverride;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResiliencePolicyResolver;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.RetryBackoff;
import dev.vertique.resilience.RetryConfig;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.RetryPolicy;
import dev.vertique.resilience.TimeoutConfig;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.CircuitBreakerDeclaration;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.rest.client.config.RestClientCircuitBreakerConfig;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.config.RestClientRetryConfig;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Translates REST builder/configuration state into the common resilience policy model. */
final class RestClientResilienceConfigAdapter {

    private final ResiliencePolicyResolver resolver;
    private final long readTimeoutMs;
    private final RestClientRetryPolicy retryPolicy;
    private final dev.vertique.resilience.BackoffStrategy backoffStrategy;
    private final ResiliencePolicyRegistry registry;
    private final RetryPolicy restRetryFallbackPolicy;

    @Nullable
    private final RestClientConfig clientConfig;

    @Nullable
    private final CircuitBreakerOptions interfaceCircuitBreakerOptions;

    RestClientResilienceConfigAdapter(
            ResiliencePolicyResolver resolver,
            long readTimeoutMs,
            RestClientRetryPolicy retryPolicy,
            dev.vertique.resilience.BackoffStrategy backoffStrategy,
            @Nullable RestClientConfig clientConfig,
            @Nullable CircuitBreakerOptions interfaceCircuitBreakerOptions) {
        this(
                resolver,
                readTimeoutMs,
                retryPolicy,
                backoffStrategy,
                clientConfig,
                interfaceCircuitBreakerOptions,
                ResiliencePolicyRegistry.empty());
    }

    RestClientResilienceConfigAdapter(
            ResiliencePolicyResolver resolver,
            long readTimeoutMs,
            RestClientRetryPolicy retryPolicy,
            dev.vertique.resilience.BackoffStrategy backoffStrategy,
            @Nullable RestClientConfig clientConfig,
            @Nullable CircuitBreakerOptions interfaceCircuitBreakerOptions,
            ResiliencePolicyRegistry registry) {
        this.resolver = resolver;
        this.readTimeoutMs = readTimeoutMs;
        this.retryPolicy = retryPolicy;
        this.backoffStrategy = backoffStrategy;
        this.clientConfig = clientConfig;
        this.interfaceCircuitBreakerOptions = interfaceCircuitBreakerOptions;
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.restRetryFallbackPolicy = this::shouldRetry;
    }

    /** Resolves one method using operation/config, canonical annotations, and builder defaults. */
    ResolvedResiliencePolicy resolve(ClientMethodMeta meta, boolean usesInterfaceBreaker) {
        ResilienceAnnotations annotations = meta.resilienceAnnotations();
        if (usesInterfaceBreaker && annotations.circuitBreaker().isPresent()) {
            CircuitBreakerDeclaration declaration = annotations.circuitBreaker().orElseThrow();
            annotations = new ResilienceAnnotations(
                    annotations.timeout(),
                    Optional.empty(),
                    annotations.retry(),
                    annotations.bulkhead(),
                    annotations.policy());
            if (declaration.timeoutMs() > 0 && annotations.timeout().isEmpty()) {
                annotations = new ResilienceAnnotations(
                        Optional.of(new dev.vertique.resilience.annotation.TimeoutDeclaration(
                                declaration.timeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS)),
                        annotations.circuitBreaker(),
                        annotations.retry(),
                        annotations.bulkhead(),
                        annotations.policy());
            }
        }

        RestClientRetryConfig retryConfig = clientConfig == null ? null : clientConfig.retry();
        ResilienceDefaults defaults = new ResilienceDefaults(
                Optional.of(TimeoutConfig.ofMillis(readTimeoutMs)),
                retryConfig == null ? Optional.empty() : Optional.of(defaultRetry()),
                Optional.empty(),
                Optional.empty());
        ResiliencePolicyOverrides effectiveOverrides = registry.layer(annotations, overrides(retryConfig));
        if (retryIsActive(annotations, effectiveOverrides, defaults)) {
            effectiveOverrides = withFallbackPolicy(effectiveOverrides);
        }
        return resolver.resolve(annotations, effectiveOverrides, defaults);
    }

    /** Builds the single shared interface breaker configuration, or {@code null} when disabled. */
    @Nullable
    CircuitBreakerConfig interfaceCircuitBreakerConfig() {
        if (interfaceCircuitBreakerOptions == null) {
            return null;
        }
        return CircuitBreakerConfig.builder()
                .maxFailures(interfaceCircuitBreakerOptions.getMaxFailures())
                .resetTimeoutMs(interfaceCircuitBreakerOptions.getResetTimeout())
                .build();
    }

    private ResiliencePolicyOverrides overrides(@Nullable RestClientRetryConfig retryConfig) {
        Optional<TimeoutOverride> timeout = Optional.empty();
        Optional<RetryOverride> retry = Optional.empty();
        Optional<CircuitBreakerOverride> circuitBreaker = Optional.empty();
        if (clientConfig != null) {
            if (clientConfig.readTimeoutMs() != null) {
                timeout = Optional.of(
                        new TimeoutOverride(Optional.empty(), OptionalLong.of(clientConfig.readTimeoutMs())));
            } else if (clientConfig.circuitBreaker() != null
                    && clientConfig.circuitBreaker().timeoutMs() != null) {
                timeout = Optional.of(new TimeoutOverride(
                        Optional.empty(),
                        OptionalLong.of(clientConfig.circuitBreaker().timeoutMs())));
            }
            RestClientCircuitBreakerConfig cb = clientConfig.circuitBreaker();
            if (cb != null && (cb.maxFailures() != null || cb.resetTimeoutMs() != null)) {
                circuitBreaker = Optional.of(new CircuitBreakerOverride(
                        Optional.empty(), optionalInt(cb.maxFailures()), optionalLong(cb.resetTimeoutMs())));
            }
        }
        if (retryConfig != null && (retryConfig.maxRetries() != null || !isBlank(retryConfig.backoffStrategy()))) {
            Optional<BackoffOverride> backoff = Optional.empty();
            if (!isBlank(retryConfig.backoffStrategy())) {
                try {
                    @SuppressWarnings("unchecked")
                    Class<? extends dev.vertique.resilience.BackoffStrategy> type =
                            (Class<? extends dev.vertique.resilience.BackoffStrategy>)
                                    Class.forName(retryConfig.backoffStrategy());
                    backoff = Optional.of(new BackoffOverride(
                            Optional.of(type.getDeclaredConstructor().newInstance()),
                            OptionalLong.empty(),
                            Optional.empty(),
                            OptionalLong.empty(),
                            OptionalLong.empty()));
                } catch (ReflectiveOperationException | LinkageError failure) {
                    throw new RestClientConfigurationException(
                            "Failed to instantiate REST client backoff strategy", failure);
                }
            }
            retry = Optional.of(new RetryOverride(
                    Optional.empty(),
                    optionalInt(retryConfig.maxRetries()),
                    backoff,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(restRetryFallbackPolicy)));
        }
        return new ResiliencePolicyOverrides(timeout, retry, circuitBreaker, Optional.empty());
    }

    private boolean retryIsActive(
            ResilienceAnnotations annotations, ResiliencePolicyOverrides overrides, ResilienceDefaults defaults) {
        if (overrides
                .retry()
                .map(value -> value.enabled().filter(enabled -> !enabled).isPresent())
                .orElse(false)) {
            return false;
        }
        return annotations.retry().isPresent()
                || overrides.retry().isPresent()
                || defaults.retry().isPresent();
    }

    private ResiliencePolicyOverrides withFallbackPolicy(ResiliencePolicyOverrides overrides) {
        RetryOverride current = overrides
                .retry()
                .orElse(new RetryOverride(
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        if (current.fallbackPolicy()
                .filter(value -> value == restRetryFallbackPolicy)
                .isPresent()) {
            return overrides;
        }
        RetryOverride withFallback = new RetryOverride(
                current.enabled(),
                current.maxRetries(),
                current.backoff(),
                current.retryOn(),
                current.abortOn(),
                Optional.of(restRetryFallbackPolicy));
        return new ResiliencePolicyOverrides(
                overrides.timeout(), Optional.of(withFallback), overrides.circuitBreaker(), overrides.bulkhead());
    }

    private RetryConfig defaultRetry() {
        return RetryConfig.builder()
                .maxRetries(3)
                .backoff(RetryBackoff.custom(backoffStrategy))
                .fallbackPolicy(restRetryFallbackPolicy)
                .build();
    }

    /** Applies the REST exception mapping before consulting the builder-level retry policy. */
    private boolean shouldRetry(Throwable failure, int retryCount) {
        if (failure instanceof dev.vertique.resilience.exception.ResilienceTimeoutException) {
            return true;
        }
        Throwable mapped = failure;
        if (!(failure instanceof dev.vertique.rest.client.exception.RestClientException)
                && !(failure instanceof dev.vertique.rest.client.exception.RestClientUnavailableException)) {
            mapped = new DefaultRestClientExceptionMapper().translate(failure);
        }
        return retryPolicy.shouldRetry(mapped, retryCount);
    }

    private static OptionalInt optionalInt(@Nullable Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static OptionalLong optionalLong(@Nullable Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
