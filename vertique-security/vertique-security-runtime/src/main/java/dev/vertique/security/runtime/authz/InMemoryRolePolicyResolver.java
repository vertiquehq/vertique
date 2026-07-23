// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.RolePolicyResolver;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory, programmatic default {@link RolePolicyResolver}.
 *
 * <p>This is the framework's dependency-clean default: it holds a fixed, immutable mapping from role
 * names to policy name lists supplied at construction time and carries <strong>no</strong> dependency
 * on configuration or YAML (a config/YAML-backed resolver lives in a separate config-aware module).
 *
 * <p>The constructor defensively copies the mapping. The {@link #policiesForRoles(Set)} method
 * returns the union of all policy names for the supplied roles; roles that have no mapping are
 * silently ignored.
 */
public final class InMemoryRolePolicyResolver implements RolePolicyResolver {

    /** Immutable role → policy-names mapping. Values are unmodifiable lists. */
    private final Map<String, List<String>> rolePolicies;

    /**
     * Creates a resolver with the given role-to-policy-names mapping.
     *
     * @param rolePolicies a mapping from role name to the list of policy names that role grants;
     *     must not be {@code null}; entries must not have {@code null} keys or {@code null} value
     *     lists
     * @throws NullPointerException if {@code rolePolicies} is {@code null}
     */
    public InMemoryRolePolicyResolver(Map<String, List<String>> rolePolicies) {
        Objects.requireNonNull(rolePolicies, "rolePolicies");
        // Defensive copy: copy the outer map and make each value list unmodifiable.
        this.rolePolicies = rolePolicies.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /**
     * Returns the union of policy names for all the supplied roles.
     *
     * <p>Each role is looked up in the mapping; unknown roles contribute nothing. The returned set
     * is unmodifiable and never {@code null}.
     *
     * @param roles the roles to resolve; must not be {@code null}
     * @return an unmodifiable set of policy names; never {@code null}; may be empty
     * @throws NullPointerException if {@code roles} is {@code null}
     */
    @Override
    public Set<String> policiesForRoles(Set<String> roles) {
        Objects.requireNonNull(roles, "roles");
        return roles.stream()
                .flatMap(role -> rolePolicies.getOrDefault(role, List.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
