// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigBackedRolePolicyResolver}.
 *
 * <p>Verifies that the resolver maps roles to policy names from {@link AuthorizationConfig} and
 * exposes its raw mapping for the wiring layer to validate. The resolver itself no longer validates
 * referenced policy names — that check moved to {@link AuthzConfigModule} where the merged policy
 * catalogue is available (see {@code AuthzCombinedModulesTest} for the merged-catalogue validation).
 */
class ConfigBackedRolePolicyResolverTest {

    // --- resolution ---

    @Test
    @DisplayName("resolves policy names from AuthorizationConfig rolePolicies for a known role")
    void resolves_from_authorizationConfig_rolePolicies() {
        AuthorizationConfig config = new AuthorizationConfig(Map.of("admin", List.of("admin-policy")), List.of());
        ConfigBackedRolePolicyResolver resolver = new ConfigBackedRolePolicyResolver(config);

        Set<String> result = resolver.policiesForRoles(Set.of("admin"));
        assertEquals(Set.of("admin-policy"), result);
    }

    @Test
    @DisplayName("unknown role resolves to the empty set")
    void unknownRole_resolvesEmpty() {
        AuthorizationConfig config = new AuthorizationConfig(Map.of("admin", List.of("admin-policy")), List.of());
        ConfigBackedRolePolicyResolver resolver = new ConfigBackedRolePolicyResolver(config);

        assertEquals(Set.of(), resolver.policiesForRoles(Set.of("viewer")));
    }

    @Test
    @DisplayName("multiple roles resolve to the union of their policy names")
    void multipleRoles_resolveUnion() {
        AuthorizationConfig config = new AuthorizationConfig(
                Map.of("admin", List.of("admin-policy"), "editor", List.of("editor-policy")), List.of());
        ConfigBackedRolePolicyResolver resolver = new ConfigBackedRolePolicyResolver(config);

        assertEquals(Set.of("admin-policy", "editor-policy"), resolver.policiesForRoles(Set.of("admin", "editor")));
    }

    // --- raw mapping exposure (used by the wiring layer for merged-catalogue validation) ---

    @Test
    @DisplayName("rolePolicies() exposes the raw role-to-policy mapping for wiring-layer validation")
    void rolePolicies_exposesRawMapping() {
        AuthorizationConfig config = new AuthorizationConfig(Map.of("admin", List.of("admin-policy")), List.of());
        ConfigBackedRolePolicyResolver resolver = new ConfigBackedRolePolicyResolver(config);

        assertEquals(Map.of("admin", List.of("admin-policy")), resolver.rolePolicies());
    }
}
