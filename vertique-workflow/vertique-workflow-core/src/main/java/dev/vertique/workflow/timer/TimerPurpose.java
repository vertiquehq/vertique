// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import java.util.UUID;

/**
 * Discriminator for the purpose of a timer row in {@code workflow_timers}.
 *
 * <p>Drives executor dispatch in {@code WorkflowTimerFireExecutor} and reminder cancel-cascade
 * lookups when a task transitions to a terminal status. The value is stored in the
 * {@code purpose} column of {@code workflow_timers} as a string.
 */
public enum TimerPurpose {

    /**
     * Standalone {@link dev.vertique.workflow.plan.TimerNode}; the engine resumes a workflow
     * at the configured next step when the timer fires.
     */
    STANDALONE,

    /**
     * Timeout branch on a {@link dev.vertique.workflow.plan.WaitSignalNode}; the engine takes
     * the timeout branch when the timer fires before the expected signal arrives.
     */
    SIGNAL_TIMEOUT,

    /**
     * Due-date timer for a {@link dev.vertique.workflow.plan.HumanTaskNode}; the engine expires
     * the task when the timer fires.
     */
    TASK_DUE,

    /**
     * Reminder timer for a {@link dev.vertique.workflow.plan.HumanTaskNode}; firing emits a
     * {@link dev.vertique.workflow.events.WorkflowEventType#TASK_REMINDER} event with no
     * workflow state change.
     */
    TASK_REMINDER;

    /**
     * Validates the {@code (purpose, taskId)} pairing rule shared by {@link TimerRecord} and
     * {@link TimerIntentPayload}: task-scoped purposes ({@link #TASK_DUE}, {@link #TASK_REMINDER})
     * require a non-null {@code taskId}; non-task purposes ({@link #STANDALONE},
     * {@link #SIGNAL_TIMEOUT}) require a null {@code taskId}. The CHECK constraint
     * {@code ck_workflow_timers_task_id_purpose} enforces the same invariant at the database layer.
     *
     * @param purpose the timer purpose; must not be null
     * @param taskId  the owning task id, or null for non-task purposes
     * @throws IllegalArgumentException if the pairing is invalid
     */
    static void validateTaskIdPairing(TimerPurpose purpose, UUID taskId) {
        boolean taskScoped = purpose == TASK_DUE || purpose == TASK_REMINDER;
        if (taskScoped && taskId == null) {
            throw new IllegalArgumentException("taskId is required for purpose " + purpose);
        }
        if (!taskScoped && taskId != null) {
            throw new IllegalArgumentException("taskId must be null for purpose " + purpose);
        }
    }
}
