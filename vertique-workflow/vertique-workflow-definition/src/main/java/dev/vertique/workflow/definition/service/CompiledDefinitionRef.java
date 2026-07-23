// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.source.SourceMetadata;
import java.util.Objects;

/**
 * A safe, non-generic projection of a stored compiled definition, used as the return type for
 * {@link WorkflowDefinitionService} operations.
 *
 * <p>Unlike {@link dev.vertique.workflow.definition.pipeline.CompiledDefinition}, this record
 * carries no generic {@code WorkflowDefinition} type and is therefore safe to expose across
 * API boundaries.
 *
 * @param definitionId the workflow definition id; must not be {@code null} or blank
 * @param definitionVersion the definition version; must be positive
 * @param planHash the SHA-256 hex digest of the compiled plan; must not be {@code null} or blank
 * @param status the current activation status; must not be {@code null}
 * @param source provenance metadata for the definition; must not be {@code null}
 */
public record CompiledDefinitionRef(
        String definitionId, long definitionVersion, String planHash, ActivationStatus status, SourceMetadata source) {

    /**
     * Compact constructor: validates non-null and non-blank invariants.
     *
     * @throws NullPointerException if {@code definitionId}, {@code planHash}, {@code status},
     *     or {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code definitionId} or {@code planHash} is blank, or
     *     if {@code definitionVersion} is not positive
     */
    public CompiledDefinitionRef {
        Objects.requireNonNull(definitionId, "definitionId");
        if (definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definitionVersion must be positive");
        }
        Objects.requireNonNull(planHash, "planHash");
        if (planHash.isBlank()) {
            throw new IllegalArgumentException("planHash must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
    }
}
