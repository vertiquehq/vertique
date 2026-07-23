// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

import java.util.UUID;

/**
 * Result returned by {@link WorkflowSideEffectRecorder#record(WorkflowSideEffectIntent, Object)}
 * after a side-effect intent has been durably recorded.
 *
 * <p>Most recorders (e.g., the outbox-based SERVICE recorder) produce no actionable output beyond
 * confirming that the intent was persisted — they return {@link #empty()}. Recorders that schedule
 * durable timers return {@link #ofTimer(UUID)} so the caller can correlate the timer id with the
 * workflow instance.
 *
 * <p>The two permitted variants are:
 * <ul>
 *   <li>{@link Empty} — the recorder wrote a durable record but produced no correlated resource id
 *       (used by SERVICE and KAFKA recorders).</li>
 *   <li>{@link Timer} — the recorder scheduled a durable timer and returns its stable id
 *       (used by the WORKFLOW_TIMER recorder in cycle 2).</li>
 * </ul>
 */
public sealed interface RecorderResult permits RecorderResult.Empty, RecorderResult.Timer {

    /**
     * Shared singleton for the {@link Empty} variant. Use via {@link #empty()} rather than
     * constructing a new {@code Empty()} each time.
     */
    RecorderResult EMPTY = new Empty();

    // --- Permitted variants ---

    /**
     * Indicates that the recorder completed successfully but produced no correlated resource id.
     *
     * <p>This is the result for SERVICE and KAFKA intents where the workflow engine needs no
     * additional information beyond confirmation that the record was written.
     */
    record Empty() implements RecorderResult {}

    /**
     * Indicates that the recorder scheduled a durable timer and provides its stable id.
     *
     * <p>The engine uses this id to populate {@link dev.vertique.workflow.state.WorkflowInstance}
     * wait state ({@code waitAuxId}) so that timer-fire callbacks can be correlated back to
     * the correct workflow instance and step.
     *
     * @param timerId the stable UUID assigned to the scheduled timer; never null
     */
    record Timer(UUID timerId) implements RecorderResult {}

    // --- Static factories ---

    /**
     * Returns the shared {@link Empty} singleton.
     *
     * @return the {@link Empty} result constant
     */
    static RecorderResult empty() {
        return EMPTY;
    }

    /**
     * Returns a {@link Timer} result wrapping the given timer id.
     *
     * @param timerId the stable UUID assigned to the scheduled timer; must not be null
     * @return a new {@link Timer} result
     */
    static RecorderResult ofTimer(UUID timerId) {
        return new Timer(timerId);
    }
}
