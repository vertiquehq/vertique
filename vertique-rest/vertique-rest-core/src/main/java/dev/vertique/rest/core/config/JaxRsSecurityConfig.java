// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * Configuration for the JAX-RS explicit-security-policy opt-in.
 *
 * <p>Held by {@link JaxRsConfig#security()} and deserialized from the {@code "jaxrs.security"}
 * section of the application config. Records are Jackson-deserialisable; see
 * {@code RestCoreModule}'s package-private {@code jaxRsConfig} provider for the raw-key check
 * that runs before this record is parsed.
 *
 * @param requireExplicitPolicy when {@code true}, every JAX-RS operation with no explicit
 *                               security policy fails startup with a
 *                               {@code NO_EXPLICIT_SECURITY_POLICY} route violation instead of
 *                               only warning (default {@code false})
 */
public record JaxRsSecurityConfig(boolean requireExplicitPolicy) {

    /**
     * Jackson-friendly factory that fills in the default for an omitted JSON property.
     *
     * @param requireExplicitPolicy the opt-in flag; defaults to {@code false} when {@code null}
     * @return the deserialised config
     */
    @JsonCreator
    public static JaxRsSecurityConfig fromJson(
            @JsonProperty("requireExplicitPolicy") @Nullable Boolean requireExplicitPolicy) {
        return new JaxRsSecurityConfig(
                requireExplicitPolicy != null
                        ? requireExplicitPolicy
                        : defaults().requireExplicitPolicy());
    }

    /**
     * Default configuration: the opt-in is off, so an implicit operation only warns.
     *
     * @return the default config; never {@code null}
     */
    public static JaxRsSecurityConfig defaults() {
        return new JaxRsSecurityConfig(false);
    }
}
