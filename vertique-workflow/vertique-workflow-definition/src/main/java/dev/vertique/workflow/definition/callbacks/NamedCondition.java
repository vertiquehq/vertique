// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link NamedConditionRegistry}: a stable id paired with a
 * {@code Function<S, Boolean>} that evaluates a named condition against the current workflow
 * state.
 *
 * <p>Named conditions can be used by the validator and compiler to evaluate boolean predicates
 * by id, providing a safe, non-scripted alternative to inline expressions.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code condition} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param id stable identifier for this condition; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param condition function from workflow state to boolean evaluation result; must not be null
 */
public record NamedCondition<S>(String id, Class<S> stateType, Function<S, Boolean> condition) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code condition} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedCondition {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(condition, "condition");
    }
}
