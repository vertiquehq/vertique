// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import dev.vertique.workflow.exception.WorkflowException;
import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * Thrown when no {@link WorkflowMigrationHandler} is registered for the requested
 * {@code (definitionId, fromVersion, targetVersion)} triple.
 *
 * <p>The engine raises this exception from
 * {@link dev.vertique.workflow.ops.WorkflowOperations#migrate} when the
 * {@link WorkflowMigrationRegistry} returns {@link java.util.Optional#empty()} for the
 * instance's current pinned version and the requested target version.
 *
 * <p>PRD-WF-003 §7.4.
 */
public final class WorkflowMigrationHandlerMissingException extends WorkflowException {

    private final WorkflowInstanceId workflowId;
    private final String definitionId;
    private final long fromVersion;
    private final long targetVersion;

    /**
     * Creates a new {@code WorkflowMigrationHandlerMissingException}.
     *
     * @param workflowId identifier of the workflow instance that could not be migrated
     * @param definitionId the workflow definition id
     * @param fromVersion the version the instance is currently pinned to
     * @param targetVersion the target version requested for migration
     */
    public WorkflowMigrationHandlerMissingException(
            WorkflowInstanceId workflowId, String definitionId, long fromVersion, long targetVersion) {
        super("No migration handler registered for definition '" + definitionId + "' from version " + fromVersion
                + " to version " + targetVersion + " (workflowId=" + workflowId.value() + ")");
        this.workflowId = workflowId;
        this.definitionId = definitionId;
        this.fromVersion = fromVersion;
        this.targetVersion = targetVersion;
    }

    /**
     * Returns the identifier of the workflow instance that could not be migrated.
     *
     * @return the workflow instance id; never {@code null}
     */
    public WorkflowInstanceId workflowId() {
        return workflowId;
    }

    /**
     * Returns the workflow definition id.
     *
     * @return the definition id; never {@code null}
     */
    public String definitionId() {
        return definitionId;
    }

    /**
     * Returns the definition version the instance was pinned to at the time of the failed
     * migration request.
     *
     * @return the source version
     */
    public long fromVersion() {
        return fromVersion;
    }

    /**
     * Returns the target definition version that was requested.
     *
     * @return the target version
     */
    public long targetVersion() {
        return targetVersion;
    }
}
