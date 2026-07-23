// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state.payload;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TASK_REMINDER_FIRED}
 * history entry.
 *
 * <p>Recorded when a {@link dev.vertique.workflow.timer.TimerPurpose#TASK_REMINDER} timer fires
 * for an open task. The entry is appended to {@code workflow_history} and a
 * {@link dev.vertique.workflow.events.WorkflowEventType#TASK_REMINDER} event is emitted via the
 * outbox. No workflow state change occurs.
 *
 * @param stepId        the plan step id of the {@code HumanTaskNode} that owns the task
 * @param taskId        the stable UUID of the task for which the reminder fired
 * @param timerId       the UUID of the reminder timer that fired
 * @param reminderIndex one-based ordinal of this reminder within the task's reminder schedule;
 *     {@code 1} for the first reminder, {@code 2} for the second, etc. The same convention is
 *     surfaced in the {@code reminderIndex} attribute of the corresponding {@code TASK_REMINDER}
 *     event so external consumers see consistent values.
 * @param scheduledAt   the UTC instant at which this reminder timer was originally scheduled
 */
public record TaskReminderFiredHistoryPayload(
        String stepId, UUID taskId, UUID timerId, int reminderIndex, Instant scheduledAt) {

    /**
     * Validates required fields.
     *
     * @throws NullPointerException if {@code stepId}, {@code taskId}, {@code timerId}, or
     *     {@code scheduledAt} is null
     */
    public TaskReminderFiredHistoryPayload {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(timerId, "timerId");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
    }
}
