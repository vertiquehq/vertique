// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowStatus;
import java.util.Objects;

/**
 * Thrown by {@code WorkflowOperations.result} when the instance has no result to return: it has not completed, or it
 * completed and its definition version declares no output.
 *
 * <p>The instance is in the wrong state for the call, so this is a conflict, like
 * {@link WorkflowTaskNotWaitingException}. The message names the instance, the reason and the status, never a value.
 */
public class WorkflowResultUnavailableException extends WorkflowConflictException {

    /** Why the result is unavailable. */
    public enum Reason {
        /** The instance has not reached {@link WorkflowStatus#COMPLETED}; its status is carried. */
        NOT_COMPLETED,
        /** The instance completed, and its definition version declares no output. */
        NO_OUTPUT
    }

    private final WorkflowInstanceId instanceId;
    private final Reason reason;
    private final WorkflowStatus status;

    /**
     * Creates the exception for an instance that has no result.
     *
     * @param instanceId the id of the instance that was read
     * @param reason why the result is unavailable
     * @param status the status of the instance when it was read
     */
    public WorkflowResultUnavailableException(WorkflowInstanceId instanceId, Reason reason, WorkflowStatus status) {
        super("Workflow instance '"
                + Objects.requireNonNull(instanceId, "instanceId").value() + "' has no result: "
                + Objects.requireNonNull(reason, "reason") + " (status "
                + Objects.requireNonNull(status, "status") + ").");
        this.instanceId = instanceId;
        this.reason = reason;
        this.status = status;
    }

    /**
     * Returns the id of the instance that was read.
     *
     * @return the instance id
     */
    public WorkflowInstanceId instanceId() {
        return instanceId;
    }

    /**
     * Returns why the result is unavailable.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the status of the instance when it was read.
     *
     * @return the status
     */
    public WorkflowStatus status() {
        return status;
    }
}
