// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import dev.vertique.workflow.subject.WorkflowSubjectRef;
import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link SubjectResolverRegistry}: a stable id paired with a
 * {@code Function<S, WorkflowSubjectRef>} that resolves the workflow subject reference from the
 * current workflow state.
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
 * @param resolver function from workflow state to {@link WorkflowSubjectRef}; must not be null
 */
public record NamedSubjectResolver<S>(String id, Class<S> stateType, Function<S, WorkflowSubjectRef> resolver) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code resolver} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedSubjectResolver {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(resolver, "resolver");
    }
}
