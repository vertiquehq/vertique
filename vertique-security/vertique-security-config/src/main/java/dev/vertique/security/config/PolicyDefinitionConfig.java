// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Configuration record for a single named authorization policy.
 *
 * <p>Each policy has a stable {@code name} used as the mapping target in
 * {@link AuthorizationConfig#rolePolicies()} and a list of {@link PolicyStatementConfig}s
 * describing what the policy allows.
 *
 * <p>Deserialized from the {@code "authorization.policies[]"} section of the application config
 * via {@link dev.vertique.core.config.ConfigParser}.
 *
 * @param name       the policy's stable identifier; must not be {@code null} or blank
 * @param statements the policy's statements; must not be {@code null}
 */
public record PolicyDefinitionConfig(String name, List<PolicyStatementConfig> statements) {

    /**
     * Compact constructor: validates non-null, non-blank name and defensively copies statements.
     *
     * @throws NullPointerException     if {@code name} or {@code statements} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public PolicyDefinitionConfig {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("authorization policy name must not be blank");
        }
        Objects.requireNonNull(statements, "statements");
        statements = List.copyOf(statements);
    }

    /**
     * Jackson-friendly factory that fills in defaults for omitted JSON properties.
     *
     * @param name       the policy name; required (null produces a blank-name failure in the compact
     *                   constructor when converting to empty string)
     * @param statements the policy statements; defaults to an empty list when {@code null}
     * @return the deserialized policy config
     */
    @JsonCreator
    public static PolicyDefinitionConfig fromJson(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("statements") @Nullable List<PolicyStatementConfig> statements) {
        return new PolicyDefinitionConfig(name != null ? name : "", statements != null ? statements : List.of());
    }
}
