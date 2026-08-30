// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.resilience;

import dev.vertique.resilience.BackoffOverride;
import dev.vertique.resilience.CircuitBreakerOverride;
import dev.vertique.resilience.DurationBound;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyResolver;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceRegistrationViolation;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServiceOperationConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceRegistrationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Translates the typed Services configuration into the common resilience policy model. */
@Singleton
public final class ServiceResilienceConfigAdapter {

    private static final ResilienceDefaults DEFAULTS = ResilienceDefaults.none();
    private static final long DEFAULT_SEND_TIMEOUT_MS = 30_000L;
    private static final long SEND_TIMEOUT_BUFFER_MS = 1_000L;

    private final ResiliencePolicyResolver resolver;
    private final Long globalSendTimeoutMs;
    private final Map<ServicesConfig.ServiceKey, ServiceConfig> serviceConfigIndex;

    @Inject
    public ServiceResilienceConfigAdapter(
            dev.vertique.resilience.Resilience resilience,
            ServicesConfig servicesConfig,
            Map<ServicesConfig.ServiceKey, ServiceConfig> serviceConfigIndex) {
        this.resolver = resilience.policyResolver();
        this.globalSendTimeoutMs = servicesConfig.sendTimeoutMs();
        this.serviceConfigIndex = serviceConfigIndex;
    }

    /** Resolves one operation using Services' annotation-gated activation profile. */
    public ResolvedResiliencePolicy resolve(ServiceMethodMeta meta) {
        if (!meta.resilienceAnnotations().hasAny()) {
            return resolver.resolve(ResilienceAnnotations.NONE, ResiliencePolicyOverrides.none(), DEFAULTS);
        }
        return resolver.resolve(meta.resilienceAnnotations(), overrides(meta), DEFAULTS);
    }

    /** Returns the explicit transport timeout selected by operation, service, then global config. */
    public Long explicitSendTimeoutMs(ServiceMethodMeta meta) {
        ServiceConfig service = serviceConfig(meta);
        ServiceOperationConfig operation = findOperation(service, meta.operation());
        if (operation != null && operation.sendTimeoutMs() != null) {
            return operation.sendTimeoutMs();
        }
        if (service != null && service.sendTimeoutMs() != null) {
            return service.sendTimeoutMs();
        }
        return globalSendTimeoutMs;
    }

    /** Returns the Services operation override, or {@code null} when no block is configured. */
    public ServiceOperationConfig operationConfig(ServiceMethodMeta meta) {
        return findOperation(serviceConfig(meta), meta.operation());
    }

    /**
     * Validates every registered operation before deployment and client use.
     *
     * <p>Only annotated operations enter common policy resolution. This preserves the historical
     * Services rule that configuration alone cannot activate resilience.
     */
    public void validate(Collection<ServiceContractRegistry.ContractEntry<?>> entries) {
        List<ServiceRegistrationViolation> violations = new ArrayList<>();
        Throwable firstCause = null;
        for (ServiceContractRegistry.ContractEntry<?> entry : entries) {
            for (ServiceMethodMeta meta : entry.operations().values()) {
                if (!meta.resilienceAnnotations().hasAny()) {
                    continue;
                }
                try {
                    ResolvedResiliencePolicy policy = resolve(meta);
                    if (budgetRequiresExplicitSendTimeout(policy) && explicitSendTimeoutMs(meta) == null) {
                        if (firstCause == null) {
                            firstCause = new ResiliencePolicyException(
                                    ResiliencePolicyFailureReason.INCOMPLETE_CONFIGURATION);
                        }
                        violations.add(ServiceRegistrationViolation.ofMethod(
                                entry.contract(),
                                meta.method().name(),
                                "resilience execution requires an explicit sendTimeoutMs"));
                    }
                } catch (RuntimeException failure) {
                    if (firstCause == null) {
                        firstCause = failure;
                    }
                    violations.add(ServiceRegistrationViolation.ofMethod(
                            entry.contract(), meta.method().name(), "resilience policy configuration is invalid"));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ServiceRegistrationException(violations, firstCause);
        }
    }

    /** Returns the derived request timeout for a resolved policy, or the framework default. */
    public long derivedSendTimeoutMs(ServiceMethodMeta meta, ResolvedResiliencePolicy policy) {
        if (!meta.resilienceAnnotations().hasAny()) {
            return DEFAULT_SEND_TIMEOUT_MS;
        }
        if (policy.timeout().isEmpty() && policy.retry().isEmpty()) {
            return DEFAULT_SEND_TIMEOUT_MS;
        }
        DurationBound active = policy.executionBudget().activeExecution();
        if (!(active instanceof DurationBound.Known known) || known.saturated()) {
            throw new IllegalStateException("resilience execution requires an explicit sendTimeoutMs");
        }
        if (known.valueMs() > Long.MAX_VALUE - SEND_TIMEOUT_BUFFER_MS) {
            throw new IllegalStateException("resilience execution budget exceeds transport timeout range");
        }
        return known.valueMs() + SEND_TIMEOUT_BUFFER_MS;
    }

    /** Returns whether the resolved active budget cannot safely derive a transport timeout. */
    public boolean requiresExplicitSendTimeout(ResolvedResiliencePolicy policy) {
        return budgetRequiresExplicitSendTimeout(policy);
    }

    private static boolean budgetRequiresExplicitSendTimeout(ResolvedResiliencePolicy policy) {
        if (policy.timeout().isEmpty() && policy.retry().isEmpty()) {
            return false;
        }
        DurationBound active = policy.executionBudget().activeExecution();
        return active instanceof DurationBound.Unknown
                || active instanceof DurationBound.Unbounded
                || active instanceof DurationBound.Known known
                        && (known.saturated() || known.valueMs() > Long.MAX_VALUE - SEND_TIMEOUT_BUFFER_MS);
    }

    private ResiliencePolicyOverrides overrides(ServiceMethodMeta meta) {
        ServiceOperationConfig operation = operationConfig(meta);
        if (operation == null) {
            return ResiliencePolicyOverrides.none();
        }

        Optional<TimeoutOverride> timeout = normalizedTimeout(operation);
        Optional<RetryOverride> retry = Optional.ofNullable(toRetryOverride(operation.retry()));
        Optional<CircuitBreakerOverride> breaker =
                Optional.ofNullable(toCircuitBreakerOverride(operation.circuitBreaker()));
        return new ResiliencePolicyOverrides(timeout, retry, breaker, Optional.empty());
    }

    private static Optional<TimeoutOverride> normalizedTimeout(ServiceOperationConfig operation) {
        Long value = operation.timeout() == null ? null : operation.timeout().valueMs();
        if (value == null && operation.circuitBreaker() != null) {
            value = operation.circuitBreaker().timeoutMs();
        }
        return value == null
                ? Optional.empty()
                : Optional.of(new TimeoutOverride(Optional.empty(), OptionalLong.of(value)));
    }

    private static RetryOverride toRetryOverride(dev.vertique.services.config.RetryOverride value) {
        if (value == null) {
            return null;
        }
        Optional<BackoffOverride> backoff = Optional.empty();
        if (value.delayMs() != null || value.backoffMultiplier() != null || value.maxDelayMs() != null) {
            backoff = Optional.of(new BackoffOverride(
                    Optional.empty(),
                    optionalLong(value.delayMs()),
                    Optional.ofNullable(value.backoffMultiplier()),
                    optionalLong(value.maxDelayMs()),
                    OptionalLong.empty()));
        }
        return new RetryOverride(
                Optional.empty(),
                optionalInt(value.maxRetries()),
                backoff,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static CircuitBreakerOverride toCircuitBreakerOverride(
            dev.vertique.services.config.CircuitBreakerOverride value) {
        if (value == null) {
            return null;
        }
        return new CircuitBreakerOverride(
                Optional.empty(), optionalInt(value.maxFailures()), optionalLong(value.resetTimeoutMs()));
    }

    private ServiceConfig serviceConfig(ServiceMethodMeta meta) {
        return serviceConfigIndex.get(new ServicesConfig.ServiceKey(meta.namespace(), meta.name()));
    }

    private static ServiceOperationConfig findOperation(ServiceConfig service, String operation) {
        if (service == null) {
            return null;
        }
        return service.operations().stream()
                .filter(candidate -> candidate.operation().equals(operation))
                .findFirst()
                .orElse(null);
    }

    private static OptionalInt optionalInt(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
