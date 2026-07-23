// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.validator;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * An immutable record describing a single validation violation found while inspecting a
 * {@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument}.
 *
 * <p>Violations are accumulated by {@link WorkflowDefinitionDocumentValidator} and returned as a
 * {@link WorkflowDefinitionViolations} aggregate. A non-empty aggregate causes
 * {@link WorkflowDefinitionLoadException} to be thrown so all problems are reported in a single
 * round-trip.
 *
 * @param definitionId the workflow definition id of the document that contains the violation;
 *     non-null, non-blank
 * @param definitionVersion the workflow definition version of the document; used together with
 *     {@code definitionId} to identify the document
 * @param stepId the id of the step that triggered this violation; {@code null} for document-level
 *     violations that are not associated with a specific step
 * @param routeIndex zero-based index of the {@code RouteEntry} within a
 *     {@link dev.vertique.workflow.definition.schema.DecisionStep} that triggered this violation;
 *     {@code null} for violations not associated with a specific route
 * @param code stable, upper-snake-case violation code (e.g., {@code "DEFINITION_ID_BLANK"}); must
 *     not be null or blank — used for programmatic classification and test assertions
 * @param message human-readable description of the violation including context (unknown ids, known
 *     alternatives, field path); must not be null or blank
 */
public record Violation(
        String definitionId,
        long definitionVersion,
        @Nullable String stepId,
        @Nullable Integer routeIndex,
        String code,
        String message) {

    /**
     * Compact constructor that validates non-null, non-blank invariants on the load-bearing fields.
     *
     * @throws NullPointerException if {@code definitionId}, {@code code}, or {@code message} is
     *     null
     * @throws IllegalArgumentException if {@code definitionId}, {@code code}, or {@code message}
     *     is blank
     */
    public Violation {
        Objects.requireNonNull(definitionId, "definitionId");
        if (definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
