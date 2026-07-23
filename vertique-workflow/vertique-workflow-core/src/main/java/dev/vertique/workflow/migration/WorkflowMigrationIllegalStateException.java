// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import dev.vertique.workflow.exception.WorkflowException;
import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * Thrown when a workflow instance is in a state that prevents migration.
 *
 * <p>The engine raises this exception from
 * {@link dev.vertique.workflow.ops.WorkflowOperations#migrate} when:
 * <ul>
 *   <li>The source instance is in a terminal status ({@code COMPLETED}, {@code FAILED},
 *       {@code CANCELLED}, {@code COMPENSATED}, or {@code EXPIRED}).</li>
 *   <li>The source instance has active fork-branch state that cannot be safely migrated.</li>
 * </ul>
 *
 * <p>PRD-WF-003 §7.4.
 */
public final class WorkflowMigrationIllegalStateException extends WorkflowException {

    private final WorkflowInstanceId workflowId;
    private final String reason;

    /**
     * Creates a new {@code WorkflowMigrationIllegalStateException}.
     *
     * @param workflowId identifier of the workflow instance that cannot be migrated
     * @param reason human-readable description of why migration is not allowed for this instance
     */
    public WorkflowMigrationIllegalStateException(WorkflowInstanceId workflowId, String reason) {
        super("Cannot migrate workflow instance " + workflowId.value() + ": " + reason);
        this.workflowId = workflowId;
        this.reason = reason;
    }

    /**
     * Returns the identifier of the workflow instance that cannot be migrated.
     *
     * @return the workflow instance id; never {@code null}
     */
    public WorkflowInstanceId workflowId() {
        return workflowId;
    }

    /**
     * Returns a human-readable explanation of why the migration is not allowed.
     *
     * @return the reason string; never {@code null}
     */
    public String reason() {
        return reason;
    }
}
