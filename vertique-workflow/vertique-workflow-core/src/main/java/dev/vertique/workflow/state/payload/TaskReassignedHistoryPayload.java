// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.tasks.TaskAssignment;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TASK_REASSIGNED}
 * history entry.
 *
 * <p>Recorded when an actor reassigns a task from one user/role/queue to another.
 *
 * @param stepId the plan step id of the reassigned task
 * @param taskId the stable UUID of the reassigned task
 * @param oldAssignment the assignment before the reassignment
 * @param newAssignment the assignment after the reassignment
 * @param reassignedBy the actor who performed the reassignment
 * @param reason optional free-text audit note; null when not provided
 * @param reassignedAt the UTC instant at which the reassignment was applied
 * @param commandCorrelationId the correlation id bound to the ambient execution context at the
 *     point this entry was appended, or {@code null} when no correlation context was bound (PRD
 *     FR-WF-CTX-050/051). Additive field — history rows written before this field existed
 *     deserialize with {@code null}.
 */
public record TaskReassignedHistoryPayload(
        String stepId,
        UUID taskId,
        TaskAssignment oldAssignment,
        TaskAssignment newAssignment,
        WorkflowActor reassignedBy,
        @Nullable String reason,
        Instant reassignedAt,
        @Nullable String commandCorrelationId) {

    /**
     * Validates required fields. {@code reason} and {@code commandCorrelationId} are nullable and
     * not validated.
     *
     * @throws NullPointerException if any required field is null
     */
    public TaskReassignedHistoryPayload {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(oldAssignment, "oldAssignment");
        Objects.requireNonNull(newAssignment, "newAssignment");
        Objects.requireNonNull(reassignedBy, "reassignedBy");
        Objects.requireNonNull(reassignedAt, "reassignedAt");
    }
}
