// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

/**
 * Lifecycle status of a human task row in {@code workflow_tasks}.
 *
 * <p>A task starts in {@link #OPEN} and can only transition to one of the three terminal states:
 * <ul>
 *   <li>{@link #COMPLETED} — an actor submitted a decision and the workflow was advanced</li>
 *   <li>{@link #CANCELLED} — the parent workflow instance was cancelled before completion</li>
 *   <li>{@link #EXPIRED} — the due-date timer fired before the task was completed</li>
 * </ul>
 *
 * <p>Terminal states are permanent — once a task leaves {@link #OPEN} it cannot be re-opened.
 * Reassignment is allowed only on {@link #OPEN} tasks.
 */
public enum TaskStatus {

    /** The task is awaiting a decision from an assigned actor. */
    OPEN,

    /** An actor submitted a decision; the workflow transition was applied. */
    COMPLETED,

    /** The parent workflow instance was cancelled before this task was completed. */
    CANCELLED,

    /** The due-date timer fired before the task was completed. */
    EXPIRED
}
