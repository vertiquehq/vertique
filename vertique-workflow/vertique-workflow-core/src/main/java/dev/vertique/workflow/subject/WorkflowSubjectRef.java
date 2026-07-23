// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.subject;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * An optional reference to the domain entity that a workflow instance is acting on.
 *
 * <p>The subject reference is used for observability and query filtering. It is stored on the
 * instance at start time and is immutable thereafter.
 *
 * <p>Validation rules enforced by the compact constructor:
 * <ul>
 *   <li>{@code type} must not be {@code null} or blank.</li>
 *   <li>{@code id} must not be {@code null} or blank.</li>
 *   <li>{@code version} may be {@code null}; if non-null it must not be blank.</li>
 * </ul>
 *
 * @param type application-defined entity type (e.g., {@code "Order"}, {@code "Customer"});
 *     must not be null or blank
 * @param id identifier of the entity within its type; must not be null or blank
 * @param version optional version of the entity at the time the workflow was started; may be
 *     null; if non-null must not be blank
 */
public record WorkflowSubjectRef(
        String type, String id, @Nullable String version) {

    /**
     * Compact constructor that validates all components.
     *
     * @throws NullPointerException if {@code type} or {@code id} is null
     * @throws IllegalArgumentException if {@code type} or {@code id} is blank, or if
     *     {@code version} is non-null and blank
     */
    public WorkflowSubjectRef {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (version != null && version.isBlank()) throw new IllegalArgumentException("version must not be blank");
    }
}
