// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Set;

/**
 * SPI for resolving policy names for a set of roles.
 *
 * <p>Implementations map role names (from {@link dev.vertique.security.authz.AuthorityKind#ROLE}
 * claims on the security context) to policy names ({@link PolicyDefinition#name()}) that the
 * authorization engine should evaluate for the requesting principal.
 *
 * <p>The framework provides an in-memory, programmatic default in {@code vertique-core}; a
 * config/YAML-backed implementation lives in a separate config-aware module so that
 * {@code vertique-core} stays free of any configuration dependency.
 *
 * <p>Multiple implementations may be registered via Dagger {@code @IntoSet} multibinding. The
 * authorization engine merges (union) the results from all registered resolvers.
 */
public interface RolePolicyResolver {

    /**
     * Returns the set of policy names that apply to the given roles.
     *
     * <p>The returned set is the union of all policy names mapped to any of the supplied roles.
     * When no mapping exists for a role the role is silently ignored (not an error). Implementations
     * must return an immutable or unmodifiable set.
     *
     * @param roles the roles to resolve; must not be {@code null}
     * @return the set of applicable policy names; never {@code null}; may be empty
     * @throws NullPointerException if {@code roles} is {@code null}
     */
    Set<String> policiesForRoles(Set<String> roles);
}
