// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Collection;
import java.util.Optional;

/**
 * Framework-wide registry of the {@link ActionDefinition}s contributed by all
 * {@link ActionContributor}s.
 *
 * <p>The registry is built once at startup from the union of every contributor's actions and is
 * the authoritative catalogue of actions a policy may grant. Implementations validate the catalogue
 * at construction time and reject duplicates (keyed on {@link ActionRef#value()}) and grammar
 * violations, so by the time the registry is queried every contained action is well-formed and
 * unique.
 */
public interface ActionRegistry {

    /**
     * Returns all registered action definitions.
     *
     * @return an immutable view of every contributed {@link ActionDefinition}; never {@code null}
     */
    Collection<ActionDefinition> actions();

    /**
     * Looks up the definition for a given action.
     *
     * @param action the action reference to resolve; must not be {@code null}
     * @return the matching {@link ActionDefinition}, or {@link Optional#empty()} if the action is
     *     not registered
     * @throws NullPointerException if {@code action} is {@code null}
     */
    Optional<ActionDefinition> find(ActionRef action);

    /**
     * Reports whether an action is registered.
     *
     * @param action the action reference to test; must not be {@code null}
     * @return {@code true} if the action is registered, {@code false} otherwise
     * @throws NullPointerException if {@code action} is {@code null}
     */
    boolean contains(ActionRef action);
}
