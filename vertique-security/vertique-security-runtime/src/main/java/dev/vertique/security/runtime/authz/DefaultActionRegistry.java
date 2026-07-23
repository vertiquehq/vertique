// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default in-memory {@link ActionRegistry} that aggregates and validates the actions contributed by
 * every {@link ActionContributor} at construction time.
 *
 * <p>The constructor consults each contributor exactly once and folds the union of their
 * {@link ActionDefinition}s into an immutable map keyed by {@link ActionRef#value()}. Validation is
 * <strong>fail-fast</strong>: a {@code null} contributor, a {@code null} definition, or a definition
 * whose action violates the {@link ActionRef} grammar aborts construction (the latter two surface as
 * the {@link NullPointerException}/{@link IllegalArgumentException} the value types raise), and two
 * contributors declaring the same canonical action throw an {@link IllegalStateException} whose
 * message names <em>both</em> contributing classes. Once constructed the registry is effectively
 * immutable and safe to share.
 */
public final class DefaultActionRegistry implements ActionRegistry {

    private final Map<String, ActionDefinition> definitionsByValue;

    /**
     * Aggregates and validates the actions of the given contributors.
     *
     * @param contributors the contributors whose actions form the registry; must not be {@code null}
     *     and must not contain {@code null} entries
     * @throws NullPointerException     if {@code contributors}, any contributor, any returned
     *                                  collection, or any contained {@link ActionDefinition} is
     *                                  {@code null}
     * @throws IllegalArgumentException if any contributed action violates the {@link ActionRef}
     *                                  grammar
     * @throws IllegalStateException    if two contributors declare the same canonical action
     */
    public DefaultActionRegistry(Set<ActionContributor> contributors) {
        Objects.requireNonNull(contributors, "contributors");
        Map<String, ActionDefinition> byValue = new LinkedHashMap<>();
        // Tracks which contributor class declared each action value, so a duplicate can name both.
        Map<String, String> sourceByValue = new LinkedHashMap<>();
        for (ActionContributor contributor : contributors) {
            Objects.requireNonNull(contributor, "contributor");
            String source = contributor.getClass().getName();
            Collection<ActionDefinition> actions =
                    Objects.requireNonNull(contributor.actions(), () -> "actions() of " + source + " returned null");
            for (ActionDefinition definition : actions) {
                Objects.requireNonNull(definition, () -> "actions() of " + source + " contained a null definition");
                String value = definition.ref().value();
                String existingSource = sourceByValue.putIfAbsent(value, source);
                if (existingSource != null) {
                    throw new IllegalStateException("duplicate action \"" + value + "\" contributed by both "
                            + existingSource + " and " + source);
                }
                byValue.put(value, definition);
            }
        }
        this.definitionsByValue = Map.copyOf(byValue);
    }

    @Override
    public Collection<ActionDefinition> actions() {
        return definitionsByValue.values();
    }

    @Override
    public Optional<ActionDefinition> find(ActionRef action) {
        Objects.requireNonNull(action, "action");
        return Optional.ofNullable(definitionsByValue.get(action.value()));
    }

    @Override
    public boolean contains(ActionRef action) {
        Objects.requireNonNull(action, "action");
        return definitionsByValue.containsKey(action.value());
    }
}
