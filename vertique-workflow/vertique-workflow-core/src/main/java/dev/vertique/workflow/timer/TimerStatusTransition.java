// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

/**
 * Result of a {@link TimerStore} status-update operation, indicating whether the write was
 * applied or was lost to a concurrent transition.
 *
 * <p>The {@link TimerStore} compares-and-swaps the timer status. Because multiple concurrent
 * actors may attempt to transition the same timer (e.g., a fire attempt races with an explicit
 * cancellation), the store returns one of the four constants to let the caller decide how to
 * respond without throwing an exception.
 *
 * <p>Callers treat {@link #APPLIED} as success and treat any {@code LOST_TO_*} result as a
 * no-op (the timer has already moved to a terminal state and no further action is needed).
 */
public enum TimerStatusTransition {

    /** The status update was applied — the timer row was updated to the new status. */
    APPLIED,

    /**
     * The update was not applied because the timer was already in the {@link TimerStatus#FIRED}
     * state. Another actor beat this caller to the fire transition.
     */
    LOST_TO_FIRED,

    /**
     * The update was not applied because the timer was already in the
     * {@link TimerStatus#CANCELLED} state. The timer was explicitly cancelled before this actor
     * attempted the transition.
     */
    LOST_TO_CANCELLED,

    /**
     * The update was not applied because the timer was already in the {@link TimerStatus#FAILED}
     * state. A prior fire attempt already marked the timer as failed.
     */
    LOST_TO_FAILED
}
