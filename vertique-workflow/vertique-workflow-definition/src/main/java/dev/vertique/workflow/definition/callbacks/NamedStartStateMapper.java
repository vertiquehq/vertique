// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import java.util.Objects;
import java.util.function.Function;

/**
 * Named entry in the {@link StartStateMapperRegistry}: a stable id paired with a
 * {@code Function<P, S>} that maps a workflow start payload to the initial workflow state.
 *
 * <p>This registry is distinct from {@link PayloadMapperRegistry} because the function shape
 * differs — it converts an incoming start payload ({@code P}) to a state object ({@code S}),
 * rather than converting state to a service-call payload.
 *
 * <p>The compact constructor enforces:
 * <ul>
 *   <li>{@code id} — non-null, non-blank</li>
 *   <li>{@code payloadType} — non-null</li>
 *   <li>{@code stateType} — non-null</li>
 *   <li>{@code mapper} — non-null</li>
 * </ul>
 *
 * @param <P> the start payload type
 * @param <S> the workflow state type
 * @param id stable identifier for this mapper; must not be null or blank
 * @param payloadType runtime token for the start payload type; must not be null
 * @param stateType runtime token for the workflow state type; must not be null
 * @param mapper function from start payload to initial workflow state; must not be null
 */
public record NamedStartStateMapper<P, S>(String id, Class<P> payloadType, Class<S> stateType, Function<P, S> mapper) {

    /**
     * Validates all components.
     *
     * @throws NullPointerException if any of {@code id}, {@code payloadType}, {@code stateType},
     *     or {@code mapper} is null
     * @throws IllegalArgumentException if {@code id} is blank
     */
    public NamedStartStateMapper {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(mapper, "mapper");
    }
}
