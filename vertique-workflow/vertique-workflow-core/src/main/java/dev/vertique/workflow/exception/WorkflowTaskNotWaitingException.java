// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import java.util.UUID;

/**
 * Thrown when a task-completion attempt discovers that the owning workflow instance is no longer
 * in the {@code WAITING} state with the expected task id in its wait slot.
 *
 * <p>This typically indicates that the workflow was cancelled or advanced by another path before
 * this completion arrived.
 */
public class WorkflowTaskNotWaitingException extends WorkflowConflictException {

    private final UUID taskId;

    /**
     * Creates a new {@code WorkflowTaskNotWaitingException} for the given task id.
     *
     * @param taskId the id of the task whose completion was rejected
     */
    public WorkflowTaskNotWaitingException(UUID taskId) {
        super("Workflow is no longer waiting on task '" + taskId + "'.");
        this.taskId = taskId;
    }

    /**
     * Returns the id of the task whose completion was rejected.
     *
     * @return the task id
     */
    public UUID taskId() {
        return taskId;
    }
}
