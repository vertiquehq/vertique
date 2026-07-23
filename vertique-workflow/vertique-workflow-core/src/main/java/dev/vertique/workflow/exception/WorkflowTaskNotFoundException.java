// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import java.util.UUID;

/**
 * Thrown when the requested task does not exist in the repository.
 */
public class WorkflowTaskNotFoundException extends WorkflowNotFoundException {

    private final UUID taskId;

    /**
     * Creates a new {@code WorkflowTaskNotFoundException} for the given task id.
     *
     * @param taskId the id of the task that was not found
     */
    public WorkflowTaskNotFoundException(UUID taskId) {
        super("Workflow task '" + taskId + "' not found.");
        this.taskId = taskId;
    }

    /**
     * Returns the id of the task that was not found.
     *
     * @return the task id
     */
    public UUID taskId() {
        return taskId;
    }
}
