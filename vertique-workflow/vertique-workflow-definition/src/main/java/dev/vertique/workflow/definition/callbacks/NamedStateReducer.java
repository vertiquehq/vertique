// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Named entry in the {@link StateReducerRegistry}: a stable id paired with a
 * {@code BiFunction<S, Object, S>} that folds an event into the current workflow state,
 * producing a new state.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code reducer} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param id stable identifier for this reducer; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param reducer function from (current state, event) to new state; must not be null
 */
public record NamedStateReducer<S>(String id, Class<S> stateType, BiFunction<S, Object, S> reducer) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code reducer} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedStateReducer {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(reducer, "reducer");
    }
}
