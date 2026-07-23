// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a workflow timer row from the {@code workflow_timers} table.
 *
 * <p>Each timer is associated with exactly one workflow instance and step. The timer is
 * scheduled when the engine enters a timer-wait or signal-with-timeout step. When the timer
 * fires, the {@code vertique-workflow-delayed} module invokes the engine's
 * {@link dev.vertique.workflow.ops.TransactionalTimerCallbacks} to resume the workflow.
 *
 * <p>The {@link #purpose()} and {@link #taskId()} fields are paired: task-scoped purposes
 * ({@link TimerPurpose#TASK_DUE}, {@link TimerPurpose#TASK_REMINDER}) require a non-null
 * {@code taskId}; workflow-scoped purposes ({@link TimerPurpose#STANDALONE},
 * {@link TimerPurpose#SIGNAL_TIMEOUT}) require {@code taskId == null}. The compact constructor
 * enforces this at construction time.
 *
 * <p>The {@link #metadata()} field carries durable propagation context in wire-format key/value
 * pairs (FR-CTX-178). It is set at timer-create time by
 * {@link dev.vertique.workflow.delayed.recorder.WorkflowTimerSideEffectRecorder} via
 * {@code DurableContextPropagator.mergeCaptured(..., DELAYED_JOB)} and is the recovery-state
 * copy of the durable metadata: if the underlying delayed-job execution is lost and the timer
 * is re-enqueued by the recovery sweep, this field is copied into the replacement
 * {@code job_executions.metadata} row so the durable context survives the re-enqueue hop.
 *
 * @param timerId              stable UUID identifying this timer; primary key in
 *     {@code workflow_timers}
 * @param workflowId           the workflow instance this timer belongs to
 * @param stepId               the plan step id that created this timer
 * @param fireAt               the UTC instant at which the timer is scheduled to fire
 * @param status               current lifecycle status of the timer
 * @param delayedJobExecutionId the delayed-job execution id used to schedule and (if needed)
 *     cancel the underlying delayed job; never null — the timer row is not inserted until the
 *     delayed job is successfully scheduled
 * @param scheduledAt          UTC timestamp when the timer row was inserted
 * @param firedAt              UTC timestamp when the timer was successfully fired; null unless
 *     {@code status == FIRED}
 * @param cancelledAt          UTC timestamp when the timer was cancelled; null unless
 *     {@code status == CANCELLED}
 * @param failedAt             UTC timestamp when the timer-fire attempt failed; null unless
 *     {@code status == FAILED}
 * @param failureReason        human-readable description of the failure cause; null unless
 *     {@code status == FAILED}
 * @param purpose              the timer's role — drives executor dispatch and cancel-cascade
 *     routing; never null
 * @param taskId               the task UUID for {@link TimerPurpose#TASK_DUE} and
 *     {@link TimerPurpose#TASK_REMINDER}; must be non-null for those purposes and null for
 *     {@link TimerPurpose#STANDALONE} and {@link TimerPurpose#SIGNAL_TIMEOUT}
 * @param branchTokenId the branch token id when this timer was created inside a fan-out branch;
 *     null for single-path (non-branch) timers
 * @param forkStepId the fork step id when this timer belongs to a branch; null for single-path
 *     timers
 * @param branchId the branch id within the fork group; null for single-path timers
 * @param metadata durable propagation context as a {@link DurableMetadata} namespaced document;
 *     never null — defaults to {@link DurableMetadata#empty()} when no ambient durable context
 *     was captured at timer-create time. The {@code {"context": {...}}} carrier shape is
 *     persisted in {@code workflow_timers.metadata JSONB} via {@link DurableMetadata#toCarrier()}
 *     and read back via {@link DurableMetadata#fromCarrier(io.vertx.core.json.JsonObject)}.
 */
public record TimerRecord(
        UUID timerId,
        WorkflowInstanceId workflowId,
        String stepId,
        Instant fireAt,
        TimerStatus status,
        UUID delayedJobExecutionId,
        Instant scheduledAt,
        @Nullable Instant firedAt,
        @Nullable Instant cancelledAt,
        @Nullable Instant failedAt,
        @Nullable String failureReason,
        TimerPurpose purpose,
        @Nullable UUID taskId,
        @Nullable UUID branchTokenId,
        @Nullable String forkStepId,
        @Nullable String branchId,
        DurableMetadata metadata) {

    /**
     * Validates {@code purpose} is non-null and that the {@code taskId}-purpose pairing rules
     * are satisfied. Null {@code metadata} is normalised to {@link DurableMetadata#empty()}.
     *
     * @throws NullPointerException     if {@code purpose} is null
     * @throws IllegalArgumentException if the taskId-purpose pairing is invalid
     */
    public TimerRecord {
        Objects.requireNonNull(purpose, "purpose");
        TimerPurpose.validateTaskIdPairing(purpose, taskId);
        metadata = metadata != null ? metadata : DurableMetadata.empty();
    }
}
