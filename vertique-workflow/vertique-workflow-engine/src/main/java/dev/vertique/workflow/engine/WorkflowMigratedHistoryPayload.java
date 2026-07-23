// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * History payload for {@link dev.vertique.workflow.state.WorkflowEntryType#WORKFLOW_MIGRATED}
 * entries written by the engine after a successful
 * {@link dev.vertique.workflow.ops.WorkflowOperations#migrate} call.
 *
 * <p>Records the version transition, plan hashes for the source and target definitions, the step
 * at which the instance was positioned before and after migration, the fully-qualified class name
 * of the handler that performed the state transformation, and the wall-clock instant at which the
 * migration was applied.
 *
 * <p>All fields are non-null. Blank strings are rejected at construction time so that downstream
 * query tools can rely on non-empty values in every column.
 *
 * @param sourceVersion the definition version the instance was pinned to before migration
 * @param sourcePlanHash plan hash of the source definition version; non-blank
 * @param targetVersion the definition version the instance was re-pinned to after migration
 * @param targetPlanHash plan hash of the target definition version; non-blank
 * @param sourceStepId step id at which the instance was paused in the source plan; non-blank
 * @param targetStepId step id at which the engine will resume in the target plan; non-blank
 * @param handlerClassName fully-qualified class name of the
 *     {@link dev.vertique.workflow.migration.WorkflowMigrationHandler} that executed the migration;
 *     non-blank
 * @param migratedAt wall-clock instant at which the migration was applied; non-null
 * @param commandCorrelationId the correlation id bound to the ambient execution context at the
 *     point this entry was appended, or {@code null} when no correlation context was bound (PRD
 *     FR-WF-CTX-050/051). Additive field — history rows written before this field existed
 *     deserialize with {@code null}.
 */
public record WorkflowMigratedHistoryPayload(
        long sourceVersion,
        String sourcePlanHash,
        long targetVersion,
        String targetPlanHash,
        String sourceStepId,
        String targetStepId,
        String handlerClassName,
        Instant migratedAt,
        @Nullable String commandCorrelationId) {

    /**
     * Compact constructor — validates that all non-primitive fields are non-null and that all
     * {@code String} fields are non-blank.
     *
     * @param sourceVersion source definition version
     * @param sourcePlanHash plan hash of the source definition; must not be {@code null} or blank
     * @param targetVersion target definition version
     * @param targetPlanHash plan hash of the target definition; must not be {@code null} or blank
     * @param sourceStepId step id in the source plan; must not be {@code null} or blank
     * @param targetStepId step id in the target plan; must not be {@code null} or blank
     * @param handlerClassName FQN of the migration handler class; must not be {@code null} or blank
     * @param migratedAt migration timestamp; must not be {@code null}
     * @param commandCorrelationId bound correlation id at append time; nullable and not validated
     * @throws NullPointerException if any reference field is {@code null}
     * @throws IllegalArgumentException if any {@code String} field is blank
     */
    public WorkflowMigratedHistoryPayload {
        Objects.requireNonNull(sourcePlanHash, "sourcePlanHash");
        Objects.requireNonNull(targetPlanHash, "targetPlanHash");
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        Objects.requireNonNull(targetStepId, "targetStepId");
        Objects.requireNonNull(handlerClassName, "handlerClassName");
        Objects.requireNonNull(migratedAt, "migratedAt");
        if (sourcePlanHash.isBlank()) {
            throw new IllegalArgumentException("sourcePlanHash must not be blank");
        }
        if (targetPlanHash.isBlank()) {
            throw new IllegalArgumentException("targetPlanHash must not be blank");
        }
        if (sourceStepId.isBlank()) {
            throw new IllegalArgumentException("sourceStepId must not be blank");
        }
        if (targetStepId.isBlank()) {
            throw new IllegalArgumentException("targetStepId must not be blank");
        }
        if (handlerClassName.isBlank()) {
            throw new IllegalArgumentException("handlerClassName must not be blank");
        }
    }
}
