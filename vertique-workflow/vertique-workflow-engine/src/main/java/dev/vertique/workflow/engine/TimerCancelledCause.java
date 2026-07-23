// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Discriminator indicating why a timer was cancelled, stored in the
 * {@code TIMER_CANCELLED} history payload.
 */
enum TimerCancelledCause {

    /**
     * Timer was cancelled because the awaited signal arrived before the deadline.
     * The timeout branch was not taken; the workflow continues on the normal signal path.
     */
    SIGNAL_ARRIVED,

    /**
     * Timer was cancelled because the workflow itself was cancelled via
     * {@link dev.vertique.workflow.ops.WorkflowOperations#cancel(
     *     dev.vertique.workflow.ops.WorkflowInstanceId, String)}.
     */
    WORKFLOW_CANCELLED,

    /**
     * Timer was cancelled because the human-task it was protecting (as a due-date) was completed
     * before the deadline. The workflow continues on the normal completion path; the due-date
     * branch is not taken.
     */
    TASK_COMPLETED,

    /**
     * Timer was cancelled because its associated task reached a terminal status (completed,
     * cancelled, or expired) and the reminder cascade was triggered. Used exclusively for
     * {@link dev.vertique.workflow.timer.TimerPurpose#TASK_REMINDER} timers.
     */
    TASK_CLOSED
}
