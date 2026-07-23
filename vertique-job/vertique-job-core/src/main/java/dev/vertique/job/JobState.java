// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle state of a {@link JobExecution}.
 *
 * <p>Transitions are validated by {@link #canTransitionTo(JobState)}. Only the following
 * transitions are permitted:
 * <ul>
 *   <li>{@code ENQUEUED} → {@code PROCESSING}</li>
 *   <li>{@code PROCESSING} → {@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED},
 *       {@code ABANDONED}, {@code DEAD_LETTER} (exhausted failure / timeout)</li>
 *   <li>{@code FAILED} → {@code ENQUEUED} (retry), {@code DEAD_LETTER}</li>
 *   <li>{@code ABANDONED} → {@code ENQUEUED} (retry), {@code DEAD_LETTER},
 *       {@code SUCCEEDED}, {@code FAILED} (late handler completion race fix)</li>
 * </ul>
 *
 * <p>Future-scheduled executions use {@code ENQUEUED} state with a future {@code scheduled_at}
 * value. Workers filter by {@code scheduled_at <= NOW()} so jobs are only claimed when their
 * time arrives — no separate {@code SCHEDULED} state or transition timer is needed.
 */
public enum JobState {

    /** Job has been placed on the work queue and is awaiting a worker. May have a future
     * {@code scheduled_at} if the job was submitted for deferred execution. */
    ENQUEUED(EnumSet.of(State.PROCESSING)),

    /** Job is actively being executed by a worker. */
    PROCESSING(EnumSet.of(State.SUCCEEDED, State.FAILED, State.CANCELLED, State.ABANDONED, State.DEAD_LETTER)),

    /** Job completed successfully. */
    SUCCEEDED(EnumSet.noneOf(State.class)),

    /** Job encountered an error during execution and may be eligible for retry. */
    FAILED(EnumSet.of(State.ENQUEUED, State.DEAD_LETTER)),

    /** Job was explicitly cancelled before or during execution. */
    CANCELLED(EnumSet.noneOf(State.class)),

    /**
     * Job was not completed within the heartbeat timeout and has been reclaimed by the coordinator.
     * Can transition to {@code ENQUEUED} for retry, {@code DEAD_LETTER} if max retries exceeded,
     * or {@code SUCCEEDED}/{@code FAILED} when a late handler completion races the coordinator
     * and arrives after the node-crash mark is applied (race condition fix).
     */
    ABANDONED(EnumSet.of(State.ENQUEUED, State.DEAD_LETTER, State.SUCCEEDED, State.FAILED)),

    /** Job has exhausted all retry attempts and is permanently parked. */
    DEAD_LETTER(EnumSet.noneOf(State.class));

    // --- Private state enum for allowed targets ---

    /** Internal enum of target states used to avoid self-reference during constant initialisation. */
    private enum State {
        ENQUEUED,
        PROCESSING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        ABANDONED,
        DEAD_LETTER
    }

    private final Set<State> allowedTargets;

    JobState(Set<State> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    /**
     * Returns {@code true} if this state can transition to the given target state.
     *
     * @param target the proposed next state
     * @return {@code true} if the transition is permitted, {@code false} otherwise
     */
    public boolean canTransitionTo(JobState target) {
        return allowedTargets.contains(State.valueOf(target.name()));
    }
}
