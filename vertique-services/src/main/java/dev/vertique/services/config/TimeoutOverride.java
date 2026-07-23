// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Per-operation {@code timeout} policy override read from
 * {@code services.contracts.{namespace}.{name}.operations.{operation}.timeout}.
 *
 * <p>The single component is a nullable boxed value: {@code null} means "not overridden" — the
 * effective per-attempt timeout falls back to the {@code @Timeout} annotation (or the circuit
 * breaker timeout) per {@link dev.vertique.services.policy.PolicyChainBuilder}. No defaulting is
 * applied here. When present, the value is validated ({@code > 0}) so a malformed override fails fast
 * at startup rather than flowing a non-positive timeout into the dispatch pipeline.
 *
 * @param valueMs the per-attempt timeout in milliseconds ({@code > 0} when present), or {@code null}
 *     when not overridden
 */
public record TimeoutOverride(Long valueMs) {

    /**
     * Compact validator: when {@code valueMs} is present it must be {@code > 0}. An absent
     * ({@code null}) value is left untouched ("not overridden").
     *
     * @throws ConfigurationException if {@code valueMs} is present and not {@code > 0}
     */
    public TimeoutOverride {
        if (valueMs != null && valueMs <= 0) {
            throw new ConfigurationException(
                    "services.contracts.<ns>.<name>.operations.<op>.timeout.valueMs must be > 0, got " + valueMs);
        }
    }
}
