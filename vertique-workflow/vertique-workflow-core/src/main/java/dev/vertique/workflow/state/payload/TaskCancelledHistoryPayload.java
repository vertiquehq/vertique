// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import dev.vertique.workflow.actor.WorkflowActor;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TASK_CANCELLED} history
 * entry.
 *
 * <p>Recorded when a task is cancelled as a side-effect of the parent workflow instance being
 * cancelled. In cycle 3 the {@code cause} is always {@code "WORKFLOW_CANCELLED"}.
 *
 * @param stepId the plan step id of the cancelled task
 * @param taskId the stable UUID of the cancelled task
 * @param cancelledAt the UTC instant at which the task was cancelled
 * @param cause human-readable reason for the cancellation (e.g., {@code "WORKFLOW_CANCELLED"})
 * @param cancelledBy the actor who triggered the cancellation
 * @param commandCorrelationId the correlation id bound to the ambient execution context at the
 *     point this entry was appended, or {@code null} when no correlation context was bound (PRD
 *     FR-WF-CTX-050/051). Additive field — history rows written before this field existed
 *     deserialize with {@code null}.
 */
public record TaskCancelledHistoryPayload(
        String stepId,
        UUID taskId,
        Instant cancelledAt,
        String cause,
        WorkflowActor cancelledBy,
        @Nullable String commandCorrelationId) {

    /**
     * Validates required fields. {@code commandCorrelationId} is nullable and not validated.
     *
     * @throws NullPointerException if any required field is null
     */
    public TaskCancelledHistoryPayload {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(cancelledAt, "cancelledAt");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(cancelledBy, "cancelledBy");
    }
}
