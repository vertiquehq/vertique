// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * Sealed interface representing the reason a workflow instance is in {@link WorkflowStatus#WAITING}
 * status.
 *
 * <p>The three permitted variants cover the three kinds of waits the engine supports across cycles:
 * <ul>
 *   <li>{@link WaitForSignal} — waiting for a named external signal (cycle 1+)</li>
 *   <li>{@link WaitForTimer} — waiting for a standalone timer to fire (cycle 2+)</li>
 *   <li>{@link WaitForTask} — waiting for a human task to be completed (cycle 3 placeholder)</li>
 * </ul>
 */
public sealed interface WaitReason permits WaitReason.WaitForSignal, WaitReason.WaitForTimer, WaitReason.WaitForTask {

    /**
     * The workflow is waiting for a named external signal.
     *
     * <p>When a timeout branch is configured on the {@link dev.vertique.workflow.plan.WaitSignalNode},
     * {@code timeoutTimerId} and {@code timeoutAt} are populated with the timer id and fire instant
     * so the engine can cancel the timer when the signal arrives. Both fields are {@code null} when
     * no timeout branch is configured.
     *
     * @param signalName name of the signal this instance is waiting for
     * @param correlationKey optional key used to route signals to a specific instance when multiple
     *     instances are waiting on the same signal name; may be null
     * @param timeoutTimerId timer id of the associated timeout timer; null when no timeout is
     *     configured
     * @param timeoutAt the UTC instant at which the timeout timer will fire; null when no timeout
     *     is configured
     */
    record WaitForSignal(
            String signalName,
            @Nullable String correlationKey,
            @Nullable UUID timeoutTimerId,
            @Nullable Instant timeoutAt)
            implements WaitReason {}

    /**
     * The workflow is waiting for a standalone timer step to fire.
     *
     * @param stepId the {@link dev.vertique.workflow.plan.TimerNode} step id this instance is
     *     waiting at
     * @param timerId the stable UUID of the timer that was scheduled
     * @param fireAt the UTC instant at which the timer is scheduled to fire
     */
    record WaitForTimer(String stepId, UUID timerId, Instant fireAt) implements WaitReason {}

    /**
     * The workflow is waiting for a human task to be completed.
     *
     * <p>When a due-date is configured on the {@link dev.vertique.workflow.plan.HumanTaskNode},
     * {@code dueDateTimerId} and {@code dueAt} are populated so the engine can cancel the timer
     * when the task is completed, and the timer executor can expire the task when it fires.
     * Both fields are {@code null} when no due-date is configured.
     *
     * @param stepId the {@link dev.vertique.workflow.plan.HumanTaskNode} step id this instance is
     *     waiting at
     * @param taskId the stable UUID of the task that was created for this wait
     * @param dueDateTimerId the id of the due-date timer scheduled for this task; null when no
     *     due-date is configured
     * @param dueAt the UTC instant at which the task will expire; null when no due-date is
     *     configured
     */
    record WaitForTask(
            String stepId,
            UUID taskId,
            @Nullable UUID dueDateTimerId,
            @Nullable Instant dueAt) implements WaitReason {}
}
