// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

/**
 * Result of a {@link TaskStore} status-update operation, indicating whether the write was applied
 * or was lost to a concurrent terminal transition.
 *
 * <p>Mirrors the shape of {@link dev.vertique.workflow.timer.TimerStatusTransition} for cycle-2
 * timer operations. The store compares-and-swaps the task status; because multiple concurrent
 * actors may attempt to transition the same task, the store returns one of these constants so the
 * caller can respond without throwing an exception.
 *
 * <p>Callers treat {@link #APPLIED} as success and treat any {@code LOST_TO_*} result as an
 * indication that the task already reached a terminal state.
 */
public enum TaskTransition {

    /** The status update was applied — the task row was updated to the new status. */
    APPLIED,

    /**
     * The update was not applied because the task was already in the
     * {@link TaskStatus#COMPLETED} state. Another actor completed the task before this caller
     * attempted the transition.
     */
    LOST_TO_COMPLETED,

    /**
     * The update was not applied because the task was already in the
     * {@link TaskStatus#CANCELLED} state. The parent workflow was cancelled before this actor
     * attempted the transition.
     */
    LOST_TO_CANCELLED,

    /**
     * The update was not applied because the task was already in the {@link TaskStatus#EXPIRED}
     * state. The due-date timer fired before this actor attempted the transition.
     */
    LOST_TO_EXPIRED
}
