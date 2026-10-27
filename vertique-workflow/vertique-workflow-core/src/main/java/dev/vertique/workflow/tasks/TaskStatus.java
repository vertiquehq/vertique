// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

/**
 * Lifecycle status of a human task row in {@code workflow_tasks}.
 *
 * <p>A task starts in {@link #OPEN}. Completing it first reserves it as {@link #COMPLETING}; the reservation ends when
 * the recorded completion is applied ({@link #COMPLETED}) or rejected (back to {@link #OPEN}). From {@link #OPEN} the
 * task can also transition to one of the other terminal states:
 * <ul>
 *   <li>{@link #COMPLETED} — a recorded decision was applied and the workflow was advanced</li>
 *   <li>{@link #CANCELLED} — the parent workflow instance was cancelled before completion</li>
 *   <li>{@link #EXPIRED} — the due-date timer fired before the task was completed</li>
 * </ul>
 *
 * <p>Terminal states are permanent — once a task is {@link #COMPLETED}, {@link #CANCELLED} or {@link #EXPIRED} it
 * cannot be re-opened. Reassignment is allowed only on {@link #OPEN} tasks.
 */
public enum TaskStatus {

    /** The task is awaiting a decision from an assigned actor. */
    OPEN,

    /** An actor submitted a decision; the workflow transition was applied. */
    COMPLETED,

    /** The parent workflow instance was cancelled before this task was completed. */
    CANCELLED,

    /** The due-date timer fired before the task was completed. */
    EXPIRED,

    /**
     * A decision has been recorded for the task and not yet applied: the task is reserved by that completion. It
     * refuses a second completion and a reassignment, and returns to {@link #OPEN} when the completion is rejected.
     */
    COMPLETING
}
