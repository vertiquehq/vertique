// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * Thrown when a workflow instance with the given id does not exist in the repository.
 */
public class WorkflowInstanceNotFoundException extends WorkflowNotFoundException {

    /**
     * Creates a new {@code WorkflowInstanceNotFoundException} for the given instance id.
     *
     * @param instanceId the id of the workflow instance that was not found
     */
    public WorkflowInstanceNotFoundException(WorkflowInstanceId instanceId) {
        super("Workflow instance '" + instanceId.value() + "' not found.");
    }
}
