// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowHistoryEntrySummary;
import java.util.List;
import java.util.Objects;

/**
 * Context passed to {@link WorkflowMigrationHandler#migrate} describing the migration request.
 *
 * <p>All fields are non-null. The {@code recentHistory} list is an unmodifiable snapshot of
 * the most recent history entries for the instance — handlers may inspect it to make migration
 * decisions but cannot modify it.
 *
 * <p>{@link WorkflowHistoryEntrySummary} is a narrow projection over
 * {@link dev.vertique.workflow.state.WorkflowHistoryEntry} that omits engine-internal payload
 * typing. Handlers that need to reconstruct full payload content should do so from their own
 * domain state ({@code sourceState}) rather than from history payloads.
 *
 * @param workflowId identifier of the workflow instance being migrated
 * @param currentStepId step id at which the instance is currently paused; the step belongs to the
 *     source plan
 * @param sourceVersion definition version the instance is currently pinned to
 * @param targetVersion definition version the instance will be migrated to
 * @param sourcePlanHash plan hash of the source definition version at the time of migration
 * @param targetPlanHash plan hash of the target definition version at the time of migration
 * @param recentHistory unmodifiable list of recent history summaries for the instance; may be
 *     empty if no history is available
 */
public record MigrationContext(
        WorkflowInstanceId workflowId,
        String currentStepId,
        long sourceVersion,
        long targetVersion,
        String sourcePlanHash,
        String targetPlanHash,
        List<WorkflowHistoryEntrySummary> recentHistory) {

    /**
     * Compact constructor — validates all fields are non-null and makes {@code recentHistory}
     * immutable.
     *
     * @param workflowId workflow instance id; must not be {@code null}
     * @param currentStepId current step id in the source plan; must not be {@code null}
     * @param sourceVersion source definition version
     * @param targetVersion target definition version
     * @param sourcePlanHash plan hash of the source version; must not be {@code null}
     * @param targetPlanHash plan hash of the target version; must not be {@code null}
     * @param recentHistory recent history entries; must not be {@code null}; copied to an
     *     unmodifiable list
     * @throws NullPointerException if any reference field is {@code null}
     */
    public MigrationContext {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(currentStepId, "currentStepId");
        Objects.requireNonNull(sourcePlanHash, "sourcePlanHash");
        Objects.requireNonNull(targetPlanHash, "targetPlanHash");
        Objects.requireNonNull(recentHistory, "recentHistory");
        recentHistory = List.copyOf(recentHistory);
    }
}
