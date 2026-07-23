// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a human task row from the {@code workflow_tasks} table.
 *
 * <p>Each task is associated with exactly one workflow instance and step. A task starts in
 * {@link TaskStatus#OPEN} when the engine enters a {@link dev.vertique.workflow.plan.HumanTaskNode}
 * step, and transitions to one of the three terminal states when the task is completed, cancelled,
 * or expires.
 *
 * @param taskId stable UUID identifying this task; primary key in {@code workflow_tasks}
 * @param workflowId the workflow instance this task belongs to
 * @param stepId the plan step id that created this task
 * @param assignment the current task assignment (user, role, or queue); reflects the latest
 *     reassignment if the task was reassigned
 * @param status current lifecycle status of the task
 * @param decisions immutable snapshot of the available decision options, as stored in
 *     {@code workflow_tasks.decisions_snapshot_json}; never null, may not be empty
 * @param dueAt the UTC instant at which this task will expire if not completed; null when no
 *     due-date was configured
 * @param dueDateTimerId the id of the due-date timer scheduled to fire at {@code dueAt}; null when
 *     no due-date was configured
 * @param completedAt UTC timestamp when the task was completed; null unless
 *     {@code status == COMPLETED}
 * @param cancelledAt UTC timestamp when the task was cancelled; null unless
 *     {@code status == CANCELLED}
 * @param expiredAt UTC timestamp when the task expired; null unless {@code status == EXPIRED}
 * @param updatedAt UTC timestamp of the last update to this task row
 * @param decisionName the name of the decision that was applied; null unless
 *     {@code status == COMPLETED}
 * @param decisionPayloadJson the JSON-serialized decision payload; null unless
 *     {@code status == COMPLETED}
 * @param completedBy the actor who completed the task; null unless {@code status == COMPLETED}
 * @param cancelledBy the actor who cancelled the task; null unless {@code status == CANCELLED}
 * @param reassignedBy the actor who last reassigned the task; null if the task was never
 *     reassigned
 * @param cancellationReason free-text reason for cancellation (e.g., {@code "WORKFLOW_CANCELLED"});
 *     null unless {@code status == CANCELLED}
 * @param reassignmentReason free-text reason for the last reassignment; null if the task was never
 *     reassigned or no reason was provided
 * @param subjectVersionAtCreation snapshot of the workflow subject's version at the time this task
 *     was created; null when the workflow instance had no versioned subject ref at task creation
 *     time; always written for every task regardless of
 *     {@link dev.vertique.workflow.plan.HumanTaskNode#requireVersionStability()}
 * @param branchTokenId the branch token id when this task was created inside a fan-out branch;
 *     null for single-path (non-branch) tasks
 * @param forkStepId the fork step id when this task belongs to a branch; null for single-path tasks
 * @param branchId the branch id within the fork group; null for single-path tasks
 */
public record TaskRecord(
        UUID taskId,
        WorkflowInstanceId workflowId,
        String stepId,
        TaskAssignment assignment,
        TaskStatus status,
        List<TaskDecisionDescriptor> decisions,
        @Nullable Instant dueAt,
        @Nullable UUID dueDateTimerId,
        @Nullable Instant completedAt,
        @Nullable Instant cancelledAt,
        @Nullable Instant expiredAt,
        Instant updatedAt,
        @Nullable String decisionName,
        @Nullable String decisionPayloadJson,
        @Nullable WorkflowActor completedBy,
        @Nullable WorkflowActor cancelledBy,
        @Nullable WorkflowActor reassignedBy,
        @Nullable String cancellationReason,
        @Nullable String reassignmentReason,
        @Nullable String subjectVersionAtCreation,
        @Nullable UUID branchTokenId,
        @Nullable String forkStepId,
        @Nullable String branchId) {}
