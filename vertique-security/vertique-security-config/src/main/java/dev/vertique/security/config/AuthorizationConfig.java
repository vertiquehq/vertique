// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Root configuration record for the authorization subsystem.
 *
 * <p>Deserialized from the {@code "authorization"} section of the application config via
 * {@link dev.vertique.core.config.ConfigParser} in {@link AuthzConfigModule}.
 *
 * <p>The two key sections are:
 * <ul>
 *   <li>{@code rolePolicies} — a map from role name to the list of policy names that role grants.
 *       This is a true user-defined dictionary (R9 compliant): the keys are operator-defined role
 *       names and the values are open lists of policy identifiers.</li>
 *   <li>{@code policies} — the list of {@link PolicyDefinitionConfig}s that define what each
 *       named policy allows.</li>
 * </ul>
 *
 * <p>When the section is absent the config defaults to no role mappings and no inline policies
 * (the empty defaults). Applications that need programmatic policies should contribute them via
 * the {@link dev.vertique.security.authz.PolicyDefinitionSource} SPI in {@code vertique-core}.
 *
 * @param rolePolicies a map from role name to the list of policy names that role grants; never
 *     {@code null}; defaults to an empty map when absent in config
 * @param policies     the inline policy definitions; never {@code null}; defaults to an empty list
 *     when absent in config
 */
public record AuthorizationConfig(Map<String, List<String>> rolePolicies, List<PolicyDefinitionConfig> policies) {

    /**
     * Compact constructor: defensively copies both collections.
     *
     * @throws NullPointerException if {@code rolePolicies} or {@code policies} is {@code null}
     */
    public AuthorizationConfig {
        Objects.requireNonNull(rolePolicies, "rolePolicies");
        Objects.requireNonNull(policies, "policies");
        // Deep-copy the map so each value list is also immutable.
        Map<String, List<String>> copy = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : rolePolicies.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        rolePolicies = Map.copyOf(copy);
        policies = List.copyOf(policies);
    }

    /**
     * Jackson-friendly factory that fills in defaults for omitted JSON properties.
     *
     * @param rolePolicies the role-to-policy map; defaults to empty when {@code null}
     * @param policies     the inline policies; defaults to empty when {@code null}
     * @return the deserialized authorization config
     */
    @JsonCreator
    public static AuthorizationConfig fromJson(
            @JsonProperty("rolePolicies") @Nullable Map<String, List<String>> rolePolicies,
            @JsonProperty("policies") @Nullable List<PolicyDefinitionConfig> policies) {
        return new AuthorizationConfig(
                rolePolicies != null ? rolePolicies : Map.of(), policies != null ? policies : List.of());
    }

    /**
     * Returns the default authorization config with no role mappings and no inline policies.
     *
     * @return the default config; never {@code null}
     */
    public static AuthorizationConfig defaults() {
        return new AuthorizationConfig(Map.of(), List.of());
    }
}
