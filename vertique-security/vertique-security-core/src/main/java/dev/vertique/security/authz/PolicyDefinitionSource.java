// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Collection;
import java.util.Objects;

/**
 * SPI a module or application implements to contribute {@link PolicyDefinition}s to the authorization
 * engine.
 *
 * <p>Sources are aggregated via Dagger {@code @IntoSet} multibinding and consulted once at startup.
 * The framework ships an in-memory, programmatic default in {@code vertique-core}; a config/YAML-backed
 * source lives in a separate config-aware module so that {@code vertique-core} stays free of any
 * configuration dependency.
 *
 * <p>The policies a source contributes are validated against the {@link ActionRegistry} at startup:
 * every exact action a policy references must be registered, and every wildcard must match at least
 * one registered action. A source that fails this check aborts startup (fail-fast).
 */
public interface PolicyDefinitionSource {

    /**
     * Returns the policy definitions this source contributes.
     *
     * <p>Called once during startup. The returned collection should be effectively immutable for the
     * lifetime of the application and must not contain {@code null} entries.
     *
     * @return the contributed policy definitions; never {@code null}
     */
    Collection<PolicyDefinition> policies();

    /**
     * Validates this source's policies against the action registry at startup (fail-fast).
     *
     * <p>The wiring layer calls this polymorphically for <em>every</em> contributed source once the
     * {@link ActionRegistry} is built, so a source can verify that every action its policies
     * reference is known: an <strong>exact</strong> pattern must resolve to a registered action, and
     * a <strong>wildcard</strong> pattern must match at least one registered action.
     *
     * <p>The <strong>default implementation runs the full check for every source</strong> by
     * iterating {@link #policies()} and validating each statement's {@link ActionPattern}s against the
     * registry using only the public SPI surface — so a source that does not override this method
     * (for example a lambda or third-party {@code PolicyDefinitionSource}) is still validated rather
     * than silently skipped. A source that contributes no policies validates as a natural no-op
     * (nothing to check). Implementations should override only to <em>strengthen</em> the check, never
     * to weaken or skip it.
     *
     * <p>This lives on the SPI rather than on a concrete subtype so the wiring layer never needs to
     * {@code instanceof}-test a particular implementation to decide whether to validate it.
     *
     * @param registry the authoritative action registry to validate against; must not be
     *     {@code null}
     * @throws NullPointerException  if {@code registry} is {@code null}
     * @throws IllegalStateException if any exact pattern is not registered, or any wildcard pattern
     *     matches no registered action
     */
    default void validateAgainst(ActionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (PolicyDefinition policy : policies()) {
            for (PolicyStatement statement : policy.statements()) {
                for (ActionPattern pattern : statement.actions()) {
                    validatePattern(pattern, policy, registry);
                }
            }
        }
    }

    /**
     * Validates a single {@link ActionPattern} against the registry (fail-fast).
     *
     * <p>An <strong>exact</strong> pattern (no trailing {@code "*"}) must resolve to a registered
     * action via {@link ActionRegistry#contains(ActionRef)}; a <strong>wildcard</strong> pattern
     * (trailing {@code "*"}) must match at least one registered action via
     * {@link ActionPattern#matches(ActionRef)}. The first offending pattern aborts startup.
     *
     * @param pattern  the pattern to validate
     * @param policy   the owning policy, named in the failure message
     * @param registry the registry to validate against
     * @throws IllegalStateException if the exact pattern is not registered, or the wildcard matches
     *                               no registered action
     */
    private static void validatePattern(ActionPattern pattern, PolicyDefinition policy, ActionRegistry registry) {
        if (pattern.isWildcard()) {
            boolean matchesAny =
                    registry.actions().stream().map(ActionDefinition::ref).anyMatch(pattern::matches);
            if (!matchesAny) {
                throw new IllegalStateException("policy \"" + policy.name() + "\" references wildcard \""
                        + pattern.value() + "\" that matches no registered action");
            }
        } else {
            ActionRef ref = ActionRef.parse(pattern.value());
            if (!registry.contains(ref)) {
                throw new IllegalStateException(
                        "policy \"" + policy.name() + "\" references unregistered action \"" + pattern.value() + "\"");
            }
        }
    }
}
