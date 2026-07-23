// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link PayloadMapperRegistry}: a stable id paired with a
 * {@code Function<S, Object>} that maps workflow state to a service-call payload.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code mapper} — non-null</li>
 * </ul>
 *
 * @param <S> the workflow state type
 * @param id stable identifier for this mapper; must not be null or blank
 * @param stateType runtime token for the state type; must not be null
 * @param mapper function from workflow state to service-call payload; must not be null
 */
public record NamedPayloadMapper<S>(String id, Class<S> stateType, Function<S, Object> mapper) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if {@code id}, {@code stateType}, or {@code mapper} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedPayloadMapper {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(mapper, "mapper");
    }
}
