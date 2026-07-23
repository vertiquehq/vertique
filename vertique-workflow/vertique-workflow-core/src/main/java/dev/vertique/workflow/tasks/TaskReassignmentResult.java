// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * Sealed sum type representing the outcome of a {@link TaskStore#reassign} call.
 *
 * <p>The storage layer performs a conditional {@code UPDATE … WHERE status='OPEN'} and returns
 * one of the two permits rather than throwing. Callers pattern-match on the result to decide
 * whether to proceed (append history, return success) or propagate a conflict error.
 *
 * <p>Two permits:
 * <ul>
 *   <li>{@link Applied} — the reassignment was applied; carries the before/after assignment and
 *       audit metadata</li>
 *   <li>{@link LostToTerminal} — the task was already in a terminal status; carries the
 *       {@link TaskTransition} indicating which terminal state was reached first</li>
 * </ul>
 */
public sealed interface TaskReassignmentResult
        permits TaskReassignmentResult.Applied, TaskReassignmentResult.LostToTerminal {

    /**
     * The reassignment was applied successfully.
     *
     * @param taskId the id of the task that was reassigned
     * @param workflowId the workflow instance this task belongs to
     * @param stepId the plan step id of the task
     * @param oldAssignment the assignment before the reassignment
     * @param newAssignment the assignment after the reassignment
     * @param reassignedBy the actor who performed the reassignment
     * @param reason optional free-text audit note; null when not provided
     * @param reassignedAt the UTC instant at which the reassignment was applied
     */
    record Applied(
            UUID taskId,
            WorkflowInstanceId workflowId,
            String stepId,
            TaskAssignment oldAssignment,
            TaskAssignment newAssignment,
            WorkflowActor reassignedBy,
            @Nullable String reason,
            Instant reassignedAt)
            implements TaskReassignmentResult {}

    /**
     * The reassignment was not applied because the task was already in a terminal state.
     *
     * @param transition the specific terminal state that was reached before the reassignment
     */
    record LostToTerminal(TaskTransition transition) implements TaskReassignmentResult {}
}
