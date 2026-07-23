// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable pairing of a permitted {@link ActionRef} with the {@link RequirementDescriptor}s that
 * gate it, as returned by {@link AuthorizationIntrospector#capabilities(dev.vertique.security.SecurityContext)}.
 *
 * <p>Membership follows the wrapped {@link AuthorizationIntrospector}'s allowed-action set exactly —
 * {@code capabilities} <strong>annotates</strong> each allowed action with its requirements rather
 * than filtering it out. {@link #requirements()} carries <strong>every</strong> requirement any
 * installed {@link AuthorizationNarrower} reports for this action — not merely the first — since two
 * independent narrowers (e.g. a delegation-scope narrower and an assurance-level narrower) may each
 * place their own, unrelated condition on the same action; reporting only one would silently drop the
 * other from a capability-introspection caller's view. An empty set means the action is unconstrained
 * by any installed {@link AuthorizationNarrower}.
 *
 * @param action       the permitted action; must not be {@code null}
 * @param requirements the requirements gating this action, one entry per narrower that reports one
 *                      for this actor/action; empty when unconstrained; must not be {@code null},
 *                      defensively copied into an immutable set
 */
public record ActionCapability(ActionRef action, Set<RequirementDescriptor> requirements) {

    /**
     * Compact constructor — validates {@code action} and defensively copies {@code requirements}.
     */
    public ActionCapability {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(requirements, "requirements");
        requirements = Set.copyOf(requirements);
    }
}
