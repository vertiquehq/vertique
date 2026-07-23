// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.RolePolicyResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Config/YAML-backed {@link RolePolicyResolver} that reads role-to-policy mappings from
 * {@link AuthorizationConfig}.
 *
 * <p>This resolver is the config-aware counterpart to the in-memory programmatic default in
 * {@code vertique-core}. It reads its mappings from the {@code authorization.rolePolicies} section
 * of the application config.
 *
 * <p><strong>Where role→policy existence is validated.</strong> This resolver does <em>not</em>
 * validate referenced policy names itself, because it can only see its own config source — a role
 * mapping that legitimately references a policy contributed programmatically through another
 * {@link PolicyDefinitionSource} would be wrongly rejected. Instead the check is deferred to the
 * wiring layer ({@link AuthzConfigModule}) which holds the <em>merged</em>
 * {@code Set<PolicyDefinitionSource>} and validates each referenced policy name against the full
 * catalogue at startup (fail-fast). See {@link AuthzConfigModule} for the validation site.
 */
@Singleton
public final class ConfigBackedRolePolicyResolver implements RolePolicyResolver {

    /** Immutable role-to-policy mapping loaded from config. */
    private final Map<String, List<String>> rolePolicies;

    /**
     * Creates the resolver from the authorization configuration.
     *
     * <p>No policy-name validation happens here; it is performed against the merged policy catalogue
     * by {@link AuthzConfigModule} (see the class javadoc).
     *
     * @param config the authorization configuration holding the role-to-policy mappings; must not
     *     be {@code null}
     * @throws NullPointerException if {@code config} is {@code null}
     */
    @Inject
    public ConfigBackedRolePolicyResolver(AuthorizationConfig config) {
        Objects.requireNonNull(config, "config");
        this.rolePolicies = config.rolePolicies();
    }

    /**
     * Returns the role-to-policy mapping this resolver was built from.
     *
     * <p>Exposed so the wiring layer ({@link AuthzConfigModule}) can validate every referenced
     * policy name against the merged policy catalogue at startup. The returned map is immutable.
     *
     * @return the immutable role → policy-names mapping; never {@code null}
     */
    Map<String, List<String>> rolePolicies() {
        return rolePolicies;
    }

    /**
     * Returns the union of policy names for all the supplied roles.
     *
     * <p>Each role is looked up in the config mapping; unknown roles contribute nothing. The
     * returned set is unmodifiable and never {@code null}.
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
