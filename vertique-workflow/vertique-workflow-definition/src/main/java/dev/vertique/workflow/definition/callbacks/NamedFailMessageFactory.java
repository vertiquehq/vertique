// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link FailMessageFactoryRegistry}: a stable id paired with a
 * {@code Function<S, String>} that derives a human-readable failure message from the current
 * workflow state when a {@code FailNode} is executed.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code factory} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param id stable identifier for this factory; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param factory function from workflow state to failure message string; must not be null
 */
public record NamedFailMessageFactory<S>(String id, Class<S> stateType, Function<S, String> factory) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code factory} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedFailMessageFactory {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(factory, "factory");
    }
}
