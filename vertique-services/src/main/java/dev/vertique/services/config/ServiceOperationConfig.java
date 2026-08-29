// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Typed per-operation configuration read from {@code services.contracts.{namespace}.{name}.operations.{operation}}.
 *
 * <p>The {@code operation} identity is injected from the keyed-object key during boundary parsing
 * (the parent {@link ServiceConfig#operations()} list is annotated
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("operation")}). All policy-override components are
 * nullable typed records whose own fields are nullable boxed values: an absent nested object
 * deserializes to {@code null}, and an absent field within a present object is {@code null} too —
 * "not overridden" in both cases. No defaulting is applied; the effective resilience values are
 * computed by {@link dev.vertique.services.resilience.ServiceResilienceConfigAdapter} from the annotations and these
 * overrides. When present, {@code sendTimeoutMs} is validated ({@code > 0}) so a malformed override
 * fails fast at startup; the nested policy overrides validate their own bounds.
 *
 * @param operation the operation id (injected from the keyed-object key)
 * @param sendTimeoutMs the event bus send timeout override in milliseconds ({@code > 0} when
 *     present), or {@code null} when not overridden
 * @param timeout the {@code timeout} policy override, or {@code null} when not overridden
 * @param circuitBreaker the {@code circuitBreaker} policy override, or {@code null} when not
 *     overridden
 * @param retry the {@code retry} policy override, or {@code null} when not overridden
 */
public record ServiceOperationConfig(
        String operation,
        Long sendTimeoutMs,
        TimeoutOverride timeout,
        CircuitBreakerOverride circuitBreaker,
        RetryOverride retry) {

    /**
     * Compact validator: when {@code sendTimeoutMs} is present it must be {@code > 0}. An absent
     * ({@code null}) value is left untouched ("not overridden"). The nested policy override records
     * validate their own bounds in their own constructors.
     *
     * @throws ConfigurationException if {@code sendTimeoutMs} is present and not {@code > 0}
     */
    public ServiceOperationConfig {
        if (sendTimeoutMs != null && sendTimeoutMs <= 0) {
            throw new ConfigurationException("services.contracts.<ns>.<name>.operations." + operation
                    + ".sendTimeoutMs must be > 0, got " + sendTimeoutMs);
        }
    }
}
