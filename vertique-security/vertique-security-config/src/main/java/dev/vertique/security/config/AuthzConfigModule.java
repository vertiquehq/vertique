// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.RolePolicyResolver;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dagger module providing config/YAML-backed authorization bindings for {@code vertique-config-core}.
 *
 * <p>Reads the {@code "authorization"} section of the application config and contributes, via Dagger
 * {@code @IntoSet} multibinding into the sets declared by
 * {@code SecurityAuthzModule}:
 * <ul>
 *   <li>{@link AuthorizationConfig} — the parsed, validated authorization configuration
 *       (role-to-policy mappings and inline policy definitions).</li>
 *   <li>{@link ConfigBackedPolicyDefinitionSource} — a {@link PolicyDefinitionSource} backed by
 *       the inline policies in config, contributed {@code @IntoSet}. Its action patterns are
 *       validated against the {@link dev.vertique.security.authz.ActionRegistry} polymorphically
 *       at startup (via {@code PolicyDefinitionSource.validateAgainst}, invoked by the core wiring).</li>
 *   <li>{@link ConfigBackedRolePolicyResolver} — a {@link RolePolicyResolver} backed by the role
 *       mappings in config, contributed {@code @IntoSet}. Its referenced policy names are validated
 *       against the <em>merged</em> {@code Set<PolicyDefinitionSource>} at startup (see
 *       {@link #configBackedRolePolicyResolver}), so a mapping that references a policy contributed
 *       programmatically through another source is accepted while an unknown policy still fails fast.</li>
 * </ul>
 *
 * <p>This module contributes only into the multibinding sets; it does <strong>not</strong> declare
 * the {@code Authorizer}/{@code AuthorizationIntrospector} bindings or the multibinding sets
 * themselves. An application using config-backed authorization includes <em>both</em> this module and
 * {@code SecurityAuthzModule} in its Dagger component: the core
 * module declares the sets and builds the engine; this module feeds config-derived entries into them.
 *
 * <p>Config path: {@code authorization} — for example:
 * <pre>{@code
 * authorization:
 *   rolePolicies:
 *     admin:
 *       - admin-policy
 *     viewer:
 *       - viewer-policy
 *   policies:
 *     - name: admin-policy
 *       statements:
 *         - effect: ALLOW
 *           actions:
 *             - cms.content.*
 *     - name: viewer-policy
 *       statements:
 *         - effect: ALLOW
 *           actions:
 *             - cms.content.read
 * }</pre>
 */
@Module
public abstract class AuthzConfigModule {

    /**
     * Provides the parsed {@link AuthorizationConfig} from the {@code "authorization"} section of
     * the application config. Falls back to {@link AuthorizationConfig#defaults()} when the section
     * is absent.
     *
     * @param config the full application config injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the parsed authorization configuration; never {@code null}
     */
    @Provides
    @Singleton
    static AuthorizationConfig authorizationConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "authorization"), AuthorizationConfig.class);
    }

    /**
     * Contributes the config-backed {@link PolicyDefinitionSource} into the multibinding set of
     * sources declared by {@code SecurityAuthzModule}.
     *
     * <p>The returned source is not validated against the action registry here; the core wiring layer
     * calls {@link PolicyDefinitionSource#validateAgainst(dev.vertique.security.authz.ActionRegistry)}
     * polymorphically for every contributed source once the registry is built (fail-fast).
     *
     * @param source the config-backed source
     * @return the config-backed policy definition source, added to the set; never {@code null}
     */
    @Provides
    @IntoSet
    @Singleton
    static PolicyDefinitionSource configBackedPolicyDefinitionSource(ConfigBackedPolicyDefinitionSource source) {
        return source;
    }

    /**
     * Contributes the config-backed {@link RolePolicyResolver} into the multibinding set of resolvers
     * declared by {@code SecurityAuthzModule}, after validating
     * every referenced policy name against the merged policy catalogue.
     *
     * <p><strong>Validation site (FR — merged-catalogue role mapping check).</strong> Role mappings
     * are validated here, not inside {@link ConfigBackedRolePolicyResolver}, because only the wiring
     * layer can see the <em>merged</em> {@code Set<PolicyDefinitionSource>}. The union of every
     * source's {@link PolicyDefinition#name()} forms the known-policy set; every policy name in
     * {@code authorization.rolePolicies} must be present, otherwise startup fails fast with an
     * {@link IllegalStateException} naming the offending role and policy. This admits a mapping that
     * references a programmatically-contributed policy while still rejecting an unknown one.
     *
     * @param resolver the config-backed resolver carrying the role→policy mapping; must not be
     *     {@code null}
     * @param sources  the merged set of all contributed policy definition sources; must not be
     *     {@code null}
     * @return the validated config-backed resolver, added to the set; never {@code null}
     * @throws IllegalStateException if any policy name referenced in the role mappings is absent from
     *     the merged catalogue
     */
    @Provides
    @IntoSet
    @Singleton
    static RolePolicyResolver configBackedRolePolicyResolver(
            ConfigBackedRolePolicyResolver resolver, Set<PolicyDefinitionSource> sources) {
        Set<String> knownPolicies = sources.stream()
                .flatMap(source -> source.policies().stream())
                .map(PolicyDefinition::name)
                .collect(Collectors.toUnmodifiableSet());
        for (Map.Entry<String, List<String>> entry : resolver.rolePolicies().entrySet()) {
            String role = entry.getKey();
            for (String policyName : entry.getValue()) {
                if (!knownPolicies.contains(policyName)) {
                    throw new IllegalStateException(
                            "authorization.rolePolicies[" + role + "] references unknown policy \""
                                    + policyName
                                    + "\"; ensure it is declared in authorization.policies or contributed via a"
                                    + " PolicyDefinitionSource");
                }
            }
        }
        return resolver;
    }
}
