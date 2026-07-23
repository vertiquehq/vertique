// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

/**
 * Lifecycle status of a {@link TimerRecord} stored in the {@code workflow_timers} table.
 *
 * <p>Valid transitions are:
 * <pre>
 *   SCHEDULED → FIRED
 *   SCHEDULED → CANCELLED
 *   SCHEDULED → FAILED
 * </pre>
 *
 * <p>All transitions from {@code SCHEDULED} are terminal — there is no transition back to
 * {@code SCHEDULED} once a timer leaves that state.
 */
public enum TimerStatus {

    /** Timer has been scheduled and is waiting for its fire time to arrive. */
    SCHEDULED,

    /** Timer fired successfully and the workflow was resumed. */
    FIRED,

    /** Timer was explicitly cancelled (e.g., signal arrived before the deadline). */
    CANCELLED,

    /** The timer-fire attempt failed and will not be retried. */
    FAILED
}
