// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import dev.vertique.resilience.annotation.BulkheadDeclaration;
import dev.vertique.resilience.annotation.CircuitBreakerDeclaration;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.RetryDeclaration;
import dev.vertique.resilience.annotation.TimeoutDeclaration;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure field-level resolver for framework resilience policy declarations. */
public final class ResiliencePolicyResolver {

    ResiliencePolicyResolver() {}

    /**
     * Resolves one complete policy using operation, annotation, and default layers.
     *
     * @param annotations canonical annotation metadata
     * @param operationOverrides partial operation-level overrides
     * @param defaults complete adapter defaults
     * @return the resolved immutable policy
     * @throws ResiliencePolicyException when an enabled concern cannot become complete
     */
    public ResolvedResiliencePolicy resolve(
            ResilienceAnnotations annotations,
            ResiliencePolicyOverrides operationOverrides,
            ResilienceDefaults defaults) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(operationOverrides, "operationOverrides");
        Objects.requireNonNull(defaults, "defaults");

        try {
            return new ResolvedResiliencePolicy(
                    resolveTimeout(annotations, operationOverrides, defaults),
                    resolveRetry(annotations, operationOverrides, defaults),
                    resolveCircuitBreaker(annotations, operationOverrides, defaults),
                    resolveBulkhead(annotations, operationOverrides, defaults));
        } catch (IllegalArgumentException invalidConfiguration) {
            throw invalid();
        }
    }

    private Optional<TimeoutConfig> resolveTimeout(
            ResilienceAnnotations annotations, ResiliencePolicyOverrides overrides, ResilienceDefaults defaults) {
        TimeoutOverride operation = overrides.timeout().orElse(null);
        if (isExplicitlyDisabled(operation)) {
            return Optional.empty();
        }

        TimeoutDeclaration annotation = annotations.timeout().orElse(null);
        CircuitBreakerDeclaration breakerAnnotation =
                annotations.circuitBreaker().orElse(null);
        boolean hasLegacyAnnotationTimeout = breakerAnnotation != null && breakerAnnotation.timeoutMs() > 0;
        boolean enabled = operation != null
                && (operation.enabled().orElse(false) || operation.timeoutMs().isPresent());
        enabled |= annotation != null
                || hasLegacyAnnotationTimeout
                || defaults.timeout().isPresent();
        if (!enabled) {
            return Optional.empty();
        }

        Long timeoutMs = operation == null || operation.timeoutMs().isEmpty()
                ? null
                : operation.timeoutMs().getAsLong();
        if (timeoutMs == null && annotation != null) {
            timeoutMs = annotationTimeoutMs(annotation);
        }
        if (timeoutMs == null && hasLegacyAnnotationTimeout) {
            timeoutMs = breakerAnnotation.timeoutMs();
        }
        if (timeoutMs == null) {
            timeoutMs = defaults.timeout().map(TimeoutConfig::timeoutMs).orElse(null);
        }
        if (timeoutMs == null) {
            throw incomplete();
        }
        return Optional.of(TimeoutConfig.ofMillis(timeoutMs));
    }

    private Optional<RetryConfig> resolveRetry(
            ResilienceAnnotations annotations, ResiliencePolicyOverrides overrides, ResilienceDefaults defaults) {
        RetryOverride operation = overrides.retry().orElse(null);
        if (isExplicitlyDisabled(operation)) {
            return Optional.empty();
        }

        RetryDeclaration annotation = annotations.retry().orElse(null);
        RetryConfig defaultRetry = defaults.retry().orElse(null);
        boolean operationDefinesConcern =
                operation != null && (operation.enabled().orElse(false) || hasRetrySiblingValue(operation));
        if (!operationDefinesConcern && annotation == null && defaultRetry == null) {
            return Optional.empty();
        }

        Integer maxRetries = null;
        if (operation != null && operation.maxRetries().isPresent()) {
            maxRetries = operation.maxRetries().getAsInt();
        } else if (annotation != null) {
            maxRetries = annotation.maxRetries();
        } else if (defaultRetry != null) {
            maxRetries = defaultRetry.maxRetries();
        }
        Set<Class<? extends Throwable>> retryOn =
                operation != null && operation.retryOn().isPresent()
                        ? operation.retryOn().orElseThrow()
                        : annotation != null
                                ? Set.copyOf(annotation.retryOn())
                                : defaultRetry != null ? defaultRetry.retryOn() : Set.of();
        Set<Class<? extends Throwable>> abortOn =
                operation != null && operation.abortOn().isPresent()
                        ? operation.abortOn().orElseThrow()
                        : annotation != null
                                ? Set.copyOf(annotation.abortOn())
                                : defaultRetry != null ? defaultRetry.abortOn() : Set.of();
        RetryPolicy fallbackPolicy =
                operation != null && operation.fallbackPolicy().isPresent()
                        ? operation.fallbackPolicy().orElseThrow()
                        : defaultRetry != null ? defaultRetry.fallbackPolicy() : (failure, retryCount) -> true;

        RetryBackoff backoff =
                resolveBackoff(operation == null ? null : operation.backoff().orElse(null), annotation, defaultRetry);
        if (maxRetries == null || backoff == null) {
            throw incomplete();
        }
        return Optional.of(RetryConfig.from(maxRetries, backoff, retryOn, abortOn, fallbackPolicy));
    }

    private Optional<CircuitBreakerConfig> resolveCircuitBreaker(
            ResilienceAnnotations annotations, ResiliencePolicyOverrides overrides, ResilienceDefaults defaults) {
        CircuitBreakerOverride operation = overrides.circuitBreaker().orElse(null);
        if (isExplicitlyDisabled(operation)) {
            return Optional.empty();
        }

        CircuitBreakerDeclaration annotation = annotations.circuitBreaker().orElse(null);
        CircuitBreakerConfig defaultConfig = defaults.circuitBreaker().orElse(null);
        boolean operationDefinesConcern = operation != null
                && (operation.enabled().orElse(false)
                        || operation.maxFailures().isPresent()
                        || operation.resetTimeoutMs().isPresent());
        if (!operationDefinesConcern && annotation == null && defaultConfig == null) {
            return Optional.empty();
        }

        Integer maxFailures = null;
        if (operation != null && operation.maxFailures().isPresent()) {
            maxFailures = operation.maxFailures().getAsInt();
        } else if (annotation != null) {
            maxFailures = annotation.maxFailures();
        } else if (defaultConfig != null) {
            maxFailures = defaultConfig.maxFailures();
        }
        Long resetTimeoutMs = null;
        if (operation != null && operation.resetTimeoutMs().isPresent()) {
            resetTimeoutMs = operation.resetTimeoutMs().getAsLong();
        } else if (annotation != null) {
            resetTimeoutMs = annotation.resetTimeoutMs();
        } else if (defaultConfig != null) {
            resetTimeoutMs = defaultConfig.resetTimeoutMs();
        }
        if (maxFailures == null || resetTimeoutMs == null) {
            throw incomplete();
        }
        return Optional.of(CircuitBreakerConfig.of(maxFailures, resetTimeoutMs));
    }

    private Optional<BulkheadConfig> resolveBulkhead(
            ResilienceAnnotations annotations, ResiliencePolicyOverrides overrides, ResilienceDefaults defaults) {
        BulkheadOverride operation = overrides.bulkhead().orElse(null);
        if (isExplicitlyDisabled(operation)) {
            return Optional.empty();
        }

        BulkheadDeclaration annotation = annotations.bulkhead().orElse(null);
        boolean enabled = operation != null
                && (operation.enabled().orElse(false) || operation.config().isPresent());
        enabled |= annotation != null || defaults.bulkhead().isPresent();
        if (!enabled) {
            return Optional.empty();
        }
        BulkheadConfig config = operation != null && operation.config().isPresent()
                ? operation.config().orElseThrow()
                : annotation != null
                        ? annotationConfig(annotation)
                        : defaults.bulkhead().orElse(null);
        if (config == null) {
            throw incomplete();
        }
        return Optional.of(config);
    }

    private static BulkheadConfig annotationConfig(BulkheadDeclaration annotation) {
        return switch (annotation.mode()) {
            case REJECT -> {
                if (annotation.maxQueueSize() != 0 || annotation.queueTimeoutMs() != 0) {
                    throw new IllegalArgumentException("reject bulkhead annotations cannot configure a queue");
                }
                yield BulkheadConfig.reject(annotation.maxConcurrentCalls());
            }
            case QUEUE ->
                BulkheadConfig.queue(
                        annotation.maxConcurrentCalls(),
                        annotation.maxQueueSize(),
                        java.time.Duration.ofMillis(annotation.queueTimeoutMs()));
        };
    }

    private static RetryBackoff resolveBackoff(
            BackoffOverride operation, RetryDeclaration annotation, RetryConfig defaults) {
        if (operation != null) {
            if (operation.custom().isPresent()) {
                return RetryBackoff.custom(operation.custom().orElseThrow());
            }
            if (operationHasScalar(operation)) {
                if (operation.initialDelayMs().isPresent()
                        && operation.multiplier().isPresent()
                        && operation.maxDelayMs().isPresent()) {
                    return RetryBackoff.exponential(
                            operation.initialDelayMs().orElseThrow(),
                            operation.multiplier().orElseThrow(),
                            operation.maxDelayMs().orElseThrow(),
                            operation.maxJitterMs().orElse(1_000L));
                }
                RetryBackoff.Exponential defaultCompatible =
                        compatibleExponential(defaults == null ? null : defaults.backoff());
                RetryBackoff.Exponential compatible =
                        annotation == null ? defaultCompatible : annotationExponential(annotation);
                if (compatible == null) {
                    throw incomplete();
                }
                return RetryBackoff.exponential(
                        operation.initialDelayMs().orElse(compatible.initialDelayMs()),
                        operation.multiplier().orElse(compatible.multiplier()),
                        operation.maxDelayMs().orElse(compatible.maxDelayMs()),
                        operation
                                .maxJitterMs()
                                .orElse(
                                        defaultCompatible == null
                                                ? compatible.maxJitterMs()
                                                : defaultCompatible.maxJitterMs()));
            }
        }

        if (annotation != null) {
            if (annotation.backoffClass() != BackoffStrategy.Default.class) {
                return RetryBackoff.custom(instantiateBackoff(annotation.backoffClass()));
            }
            return annotationExponential(annotation);
        }
        return defaults == null ? null : defaults.backoff();
    }

    private static RetryBackoff.Exponential annotationExponential(RetryDeclaration annotation) {
        return RetryBackoff.exponential(
                annotation.delayMs(), annotation.backoffMultiplier(), annotation.maxDelayMs(), 1_000L);
    }

    private static RetryBackoff.Exponential compatibleExponential(RetryBackoff backoff) {
        if (backoff == null) {
            return null;
        }
        if (backoff instanceof RetryBackoff.Exponential exponential) {
            return exponential;
        }
        if (backoff instanceof RetryBackoff.Fixed fixed) {
            return RetryBackoff.exponential(fixed.delayMs(), 1.0d, fixed.delayMs(), 0L);
        }
        return null;
    }

    private static BackoffStrategy instantiateBackoff(Class<? extends BackoffStrategy> type) {
        try {
            Constructor<? extends BackoffStrategy> constructor = type.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        } catch (InvocationTargetException
                | InstantiationException
                | IllegalAccessException
                | NoSuchMethodException
                | SecurityException failure) {
            throw invalid();
        }
    }

    private static boolean hasRetrySiblingValue(RetryOverride value) {
        return value.maxRetries().isPresent()
                || value.backoff().isPresent()
                || value.retryOn().isPresent()
                || value.abortOn().isPresent()
                || value.fallbackPolicy().isPresent();
    }

    private static boolean operationHasScalar(BackoffOverride value) {
        return value.initialDelayMs().isPresent()
                || value.multiplier().isPresent()
                || value.maxDelayMs().isPresent()
                || value.maxJitterMs().isPresent();
    }

    private static Long annotationTimeoutMs(TimeoutDeclaration annotation) {
        Objects.requireNonNull(annotation.unit(), "unit");
        return annotation.unit().toMillis(annotation.value());
    }

    private static boolean isExplicitlyDisabled(TimeoutOverride value) {
        return value != null && isExplicitlyDisabled(value.enabled());
    }

    private static boolean isExplicitlyDisabled(RetryOverride value) {
        return value != null && isExplicitlyDisabled(value.enabled());
    }

    private static boolean isExplicitlyDisabled(CircuitBreakerOverride value) {
        return value != null && isExplicitlyDisabled(value.enabled());
    }

    private static boolean isExplicitlyDisabled(BulkheadOverride value) {
        return value != null && isExplicitlyDisabled(value.enabled());
    }

    private static boolean isExplicitlyDisabled(Optional<Boolean> enabled) {
        return enabled.isPresent() && !enabled.orElseThrow();
    }

    private static ResiliencePolicyException incomplete() {
        return new ResiliencePolicyException(ResiliencePolicyFailureReason.INCOMPLETE_CONFIGURATION);
    }

    private static ResiliencePolicyException invalid() {
        return new ResiliencePolicyException(ResiliencePolicyFailureReason.INVALID_CONFIGURATION);
    }
}
