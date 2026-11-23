// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link OutputMapperRegistry}: a stable id paired with the state class it reads, the output class
 * it produces and the {@code Function<S, O>} that maps the final workflow state to the output.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType}, {@code outputType} and {@code mapper} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param <O> the output type
 * @param id stable identifier for this mapper; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param outputType runtime token for the output type; must not be null
 * @param mapper function from the final workflow state to the output; must not be null
 */
public record NamedOutputMapper<S, O>(String id, Class<S> stateType, Class<O> outputType, Function<S, O> mapper) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if a component is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedOutputMapper {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(mapper, "mapper");
    }
}
