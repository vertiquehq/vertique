// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * In-memory, programmatic default {@link PolicyDefinitionSource}.
 *
 * <p>This is the framework's dependency-clean default: it holds a fixed, immutable list of
 * {@link PolicyDefinition}s supplied at construction time and carries <strong>no</strong> dependency
 * on configuration or YAML (a config/YAML-backed source lives in a separate config-aware module).
 * The constructor defensively copies the policies and rejects a {@code null} policy or a policy with a
 * blank name.
 *
 * <p>Startup validation of the policies against the {@link ActionRegistry} is inherited from
 * {@link PolicyDefinitionSource#validateAgainst(ActionRegistry)}, which the wiring layer calls once
 * the registry is built; {@link #withRegistry(ActionRegistry)} is a fluent convenience over the same
 * check. The check is <strong>fail-fast</strong>: every exact action a policy references must be
 * registered, and every wildcard pattern must match at least one registered action; otherwise startup
 * aborts with an {@link IllegalStateException}.
 */
public final class InMemoryPolicyDefinitionSource implements PolicyDefinitionSource {

    private final List<PolicyDefinition> policies;

    /**
     * Creates a source over the given policies.
     *
     * @param policies the policies this source contributes; must not be {@code null}, must not contain
     *     {@code null} entries, and no policy may have a blank name
     * @throws NullPointerException     if {@code policies} or any contained policy is {@code null}
     * @throws IllegalArgumentException if any policy has a blank name
     */
    public InMemoryPolicyDefinitionSource(Collection<PolicyDefinition> policies) {
        Objects.requireNonNull(policies, "policies");
        this.policies = List.copyOf(policies);
        for (PolicyDefinition policy : this.policies) {
            // PolicyDefinition's own compact constructor already rejects a blank name; this guard keeps
            // the invariant explicit and local to the source even if that ever changes.
            if (policy.name().isBlank()) {
                throw new IllegalArgumentException("policy name must not be blank");
            }
        }
    }

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
     * @param registry the authoritative action registry to validate against; must not be {@code null}
     * @return this source, validated
     * @throws NullPointerException  if {@code registry} is {@code null}
     * @throws IllegalStateException if any exact pattern is not registered, or any wildcard pattern
     *                               matches no registered action
     */
    public InMemoryPolicyDefinitionSource withRegistry(ActionRegistry registry) {
        validateAgainst(registry);
        return this;
    }
}
