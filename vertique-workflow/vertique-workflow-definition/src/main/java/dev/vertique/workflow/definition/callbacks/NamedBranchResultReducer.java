// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.plan.BranchResult;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Named entry in the {@link BranchResultReducerRegistry}: a stable id paired with a
 * {@code BiFunction<S, Map<String, BranchResult>, S>} that folds all branch outcomes into the
 * current workflow state, producing the merged state after a join node completes.
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
 * @param reducer function from (current state, branch-results map) to merged state; must not be
 *     null
 * @see BranchResult
 */
public record NamedBranchResultReducer<S>(
        String id, Class<S> stateType, BiFunction<S, Map<String, BranchResult>, S> reducer) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code reducer} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedBranchResultReducer {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(reducer, "reducer");
    }
}
