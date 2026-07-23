// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import dev.vertique.workflow.actor.WorkflowActor;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TASK_COMPLETED} history
 * entry.
 *
 * <p>Recorded when an actor submits a decision and the workflow transition is applied.
 *
 * <p>{@code reviewedSubjectVersion} captures the value the caller supplied in
 * {@link dev.vertique.workflow.ops.TaskCompletionCommand#reviewedSubjectVersion()} at completion
 * time. When the completing task has {@code requireVersionStability=true} and the engine enforced
 * version matching, this field equals the snapshot taken at task creation. When stability was not
 * enforced, this field carries whatever the caller supplied (which may be {@code null}).
 *
 * @param stepId the plan step id of the completed task
 * @param taskId the stable UUID of the completed task
 * @param decisionName the name of the decision that was applied
 * @param completedBy the actor who submitted the decision
 * @param completedAt the UTC instant at which the task was completed
 * @param reviewedSubjectVersion the subject-object version the reviewer supplied at completion
 *     time; {@code null} when the caller did not supply a version or when stability was not enforced
 * @param commandCorrelationId the correlation id bound to the ambient execution context at the
 *     point this entry was appended, or {@code null} when no correlation context was bound (PRD
 *     FR-WF-CTX-050/051). Additive field — history rows written before this field existed
 *     deserialize with {@code null}.
 */
public record TaskCompletedHistoryPayload(
        String stepId,
        UUID taskId,
        String decisionName,
        WorkflowActor completedBy,
        Instant completedAt,
        @Nullable String reviewedSubjectVersion,
        @Nullable String commandCorrelationId) {

    /**
     * Validates required fields. {@code reviewedSubjectVersion} and {@code commandCorrelationId}
     * are nullable and not validated.
     *
     * @throws NullPointerException if {@code stepId}, {@code taskId}, {@code decisionName},
     *     {@code completedBy}, or {@code completedAt} is null
     */
    public TaskCompletedHistoryPayload {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(decisionName, "decisionName");
        Objects.requireNonNull(completedBy, "completedBy");
        Objects.requireNonNull(completedAt, "completedAt");
        // reviewedSubjectVersion and commandCorrelationId are nullable — no validation required
    }
}
