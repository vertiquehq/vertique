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
 * <p>{@code FIRED} and {@code CANCELLED} are final. {@code FAILED} is final for the firing path: nothing
 * fires a failed timer again by itself. One operation reverses it: when {@code retry} returns an
 * instance that failed at a wait to {@code WAITING}, each timer of that instance whose last command
 * failed goes from {@code FAILED} back to {@code SCHEDULED}, linked to a new command that keeps the
 * original fire time.
 */
public enum TimerStatus {

    /** Timer has been scheduled and is waiting for its fire time to arrive. */
    SCHEDULED,

    /** Timer fired successfully and the workflow was resumed. */
    FIRED,

    /** Timer was explicitly cancelled (e.g., signal arrived before the deadline). */
    CANCELLED,

    /** The timer-fire attempt failed and is not tried again unless the instance is retried. */
    FAILED
}
