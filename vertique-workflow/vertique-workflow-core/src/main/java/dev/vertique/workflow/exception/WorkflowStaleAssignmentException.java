// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.UUID;

/**
 * Thrown when a task command carries an {@code expectedAssignment} precondition and the task's
 * current assignment no longer equals it.
 *
 * <p>The engine compares the precondition of
 * {@link dev.vertique.workflow.ops.TaskCompletionCommand#expectedAssignment()} or
 * {@link dev.vertique.workflow.ops.TaskReassignmentCommand#expectedAssignment()} against the task
 * row it holds locked for the mutation. A mismatch means the task was reassigned after the caller
 * decided that the acting user was eligible, so applying the command would act on a stale
 * eligibility decision. The transaction is rolled back and nothing is mutated.
 *
 * <p>Extends {@link WorkflowConflictException} because this is a conflict-flavoured error: the
 * caller can recover by re-reading the task, re-evaluating eligibility against the current
 * assignment, and re-submitting.
 *
 * <p>The message deliberately names only the task: the expected and current assignments are
 * available through the accessors for in-process callers, so a caller that failed an eligibility
 * check does not learn who the task now belongs to from an error surfaced over an API.
 */
public class WorkflowStaleAssignmentException extends WorkflowConflictException {

    private final UUID taskId;
    private final TaskAssignment expectedAssignment;
    private final TaskAssignment actualAssignment;

    /**
     * Creates a new {@code WorkflowStaleAssignmentException}.
     *
     * @param taskId the stable UUID of the task whose assignment moved
     * @param expectedAssignment the assignment the caller expected the task to have
     * @param actualAssignment the assignment found on the locked task row
     */
    public WorkflowStaleAssignmentException(
            UUID taskId, TaskAssignment expectedAssignment, TaskAssignment actualAssignment) {
        super("Task '" + taskId + "' assignment changed since it was read");
        this.taskId = taskId;
        this.expectedAssignment = expectedAssignment;
        this.actualAssignment = actualAssignment;
    }

    /**
     * Returns the stable UUID of the task whose assignment moved.
     *
     * @return the task id
     */
    public UUID taskId() {
        return taskId;
    }

    /**
     * Returns the assignment the caller expected the task to have.
     *
     * @return the expected assignment
     */
    public TaskAssignment expectedAssignment() {
        return expectedAssignment;
    }

    /**
     * Returns the assignment found on the locked task row.
     *
     * @return the actual assignment
     */
    public TaskAssignment actualAssignment() {
        return actualAssignment;
    }
}
