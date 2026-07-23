// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.RolePolicyResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryRolePolicyResolver}.
 *
 * <p>Verifies the in-memory, programmatic default {@link RolePolicyResolver}: it maps known roles to
 * their policy names, returns an empty set for unknown roles, computes the union when multiple roles
 * are supplied, and rejects a {@code null} input with a {@link NullPointerException}.
 */
class InMemoryRolePolicyResolverTest {

    // --- single-role lookup ---

    @Test
    @DisplayName("policiesForRoles returns the mapped policies for a known role")
    void policiesForRoles_knownRole_returnsMappedPolicies() {
        InMemoryRolePolicyResolver resolver = new InMemoryRolePolicyResolver(Map.of("admin", List.of("admin-policy")));
        Set<String> result = resolver.policiesForRoles(Set.of("admin"));
        assertEquals(Set.of("admin-policy"), result);
    }

    @Test
    @DisplayName("policiesForRoles returns an empty set for an unknown role")
    void policiesForRoles_unknownRole_returnsEmpty() {
        InMemoryRolePolicyResolver resolver = new InMemoryRolePolicyResolver(Map.of());
        Set<String> result = resolver.policiesForRoles(Set.of("unknown"));
        assertTrue(result.isEmpty());
    }

    // --- multi-role union ---

    @Test
    @DisplayName("policiesForRoles returns the union of policies when multiple roles are supplied")
    void policiesForRoles_multipleRoles_returnsUnion() {
        InMemoryRolePolicyResolver resolver =
                new InMemoryRolePolicyResolver(Map.of("a", List.of("p1"), "b", List.of("p2")));
        Set<String> result = resolver.policiesForRoles(Set.of("a", "b"));
        assertEquals(Set.of("p1", "p2"), result);
    }

    // --- null guard ---

    @Test
    @DisplayName("policiesForRoles throws NullPointerException when roles is null")
    void policiesForRoles_null_throws() {
        InMemoryRolePolicyResolver resolver = new InMemoryRolePolicyResolver(Map.of());
        assertThrows(NullPointerException.class, () -> resolver.policiesForRoles(null));
    }
}
