// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link StateMutatorRegistry}: a stable id paired with a
 * {@code Function<S, S>} that applies an in-place mutation to the current workflow state.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code mutator} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param id stable identifier for this mutator; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param mutator function from state to mutated state; must not be null
 */
public record NamedStateMutator<S>(String id, Class<S> stateType, Function<S, S> mutator) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code mutator} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedStateMutator {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(mutator, "mutator");
    }
}
