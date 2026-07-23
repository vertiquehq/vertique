// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link TaskAssignmentResolverRegistry}: a stable id paired with a
 * {@code Function<S, TaskAssignment>} that resolves the task assignment from the current workflow
 * state.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code resolver} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param id stable identifier for this resolver; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param resolver function from workflow state to {@link TaskAssignment}; must not be null
 */
public record NamedTaskAssignmentResolver<S>(String id, Class<S> stateType, Function<S, TaskAssignment> resolver) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code resolver} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedTaskAssignmentResolver {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(resolver, "resolver");
    }
}
