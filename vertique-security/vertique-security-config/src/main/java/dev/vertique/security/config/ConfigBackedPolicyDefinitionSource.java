// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Config/YAML-backed {@link PolicyDefinitionSource} that reads policy definitions from
 * {@link AuthorizationConfig}.
 *
 * <p>This source is the config-aware counterpart to the in-memory programmatic default in
 * {@code vertique-core}. It parses the {@code authorization.policies} section of the application
 * config into {@link PolicyDefinition} objects at construction time.
 *
 * <p>Startup validation is inherited from
 * {@link PolicyDefinitionSource#validateAgainst(ActionRegistry)} and is fail-fast: every action
 * pattern referenced by the parsed policies is checked against the registry — exact patterns
 * must be registered; wildcard patterns must match at least one registered action. The wiring layer
 * invokes it once the registry is available; {@link #withRegistry(ActionRegistry)} is a fluent
 * convenience over the same check.
 */
@Singleton
public final class ConfigBackedPolicyDefinitionSource implements PolicyDefinitionSource {

    /** Immutable list of policy definitions parsed from config. */
    private final List<PolicyDefinition> policies;

    /**
     * Creates a source that parses policy definitions from the provided config.
     *
     * @param config the authorization configuration; must not be {@code null}
     * @throws NullPointerException if {@code config} is {@code null}
     */
    @Inject
    public ConfigBackedPolicyDefinitionSource(AuthorizationConfig config) {
        Objects.requireNonNull(config, "config");
        this.policies = config.policies().stream()
                .map(ConfigBackedPolicyDefinitionSource::toCoreDefinition)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns the policy definitions parsed from config.
     *
     * <p>Called once during startup. The returned collection is immutable.
     *
     * @return the contributed policy definitions; never {@code null}
     */
    @Override
    public Collection<PolicyDefinition> policies() {
        return policies;
    }

    /**
     * Validates every policy in this source against the given registry, then returns this source.
     *
     * <p>Fluent convenience over the inherited
     * {@link PolicyDefinitionSource#validateAgainst(ActionRegistry)} for direct (non-wired) use; the
     * validation logic lives on the SPI default so every source is validated by default.
     *
     * @param registry the authoritative action registry to validate against; must not be
     *     {@code null}
     * @return this source, validated
     * @throws NullPointerException  if {@code registry} is {@code null}
     * @throws IllegalStateException if any exact pattern is not registered, or any wildcard
     *     pattern matches no registered action
     */
    public ConfigBackedPolicyDefinitionSource withRegistry(ActionRegistry registry) {
        validateAgainst(registry);
        return this;
    }

    /**
     * Converts a {@link PolicyDefinitionConfig} to the core {@link PolicyDefinition} type.
     *
     * @param config the config record to convert
     * @return the corresponding core {@link PolicyDefinition}
     */
    private static PolicyDefinition toCoreDefinition(PolicyDefinitionConfig config) {
        List<PolicyStatement> statements = config.statements().stream()
                .map(ConfigBackedPolicyDefinitionSource::toCoreStatement)
                .toList();
        return new PolicyDefinition(config.name(), statements);
    }

    /**
     * Converts a {@link PolicyStatementConfig} to the core {@link PolicyStatement} type.
     *
     * @param config the config record to convert
     * @return the corresponding core {@link PolicyStatement}
     */
    private static PolicyStatement toCoreStatement(PolicyStatementConfig config) {
        java.util.Set<ActionPattern> patterns =
                config.actions().stream().map(ActionPattern::new).collect(Collectors.toUnmodifiableSet());
        return new PolicyStatement(config.effect(), patterns);
    }
}
