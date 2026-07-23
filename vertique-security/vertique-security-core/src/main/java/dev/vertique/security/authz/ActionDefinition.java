// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Objects;

/**
 * Declaration of a single framework <em>action</em> a subsystem contributes to the
 * {@link ActionRegistry}.
 *
 * <p>An action definition is the unit an {@link ActionContributor} supplies at startup: it names a
 * canonical {@link ActionRef} that policies may subsequently grant. The {@link ActionRef} itself
 * carries the frozen segment grammar, so a definition is simply a validated, non-{@code null}
 * wrapper that gives subsystems a stable extension point for richer metadata in the future.
 *
 * @param ref the canonical action this definition declares; must not be {@code null}
 */
public record ActionDefinition(ActionRef ref) {

    /**
     * Compact constructor — rejects a {@code null} action reference.
     *
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    public ActionDefinition {
        Objects.requireNonNull(ref, "ref");
    }
}
