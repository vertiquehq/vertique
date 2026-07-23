// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

/**
 * Result of a {@link TransactionalTaskCallbacks} mutation operation.
 *
 * <p>Per-method contract:
 * <ul>
 *   <li>{@code taskCompleted}: returns {@link #APPLIED} or {@link #LOST_TO_RACE}.
 *       Not-WAITING / not-TASK / wait-key mismatch / not-OPEN scenarios surface as failed
 *       {@link io.vertx.core.Future}s with typed exceptions
 *       ({@code WorkflowTaskNotWaitingException},
 *       {@code WorkflowConflictException},
 *       {@code WorkflowIdempotencyConflictException}), never as {@link #STALE_NOOP}.</li>
 *   <li>{@code taskReassigned}: returns {@link #APPLIED} or {@link #LOST_TO_RACE}.
 *       {@link #STALE_NOOP} is never returned (would be an SPI contract violation).</li>
 *   <li>{@code taskDueFired}: returns {@link #APPLIED} or {@link #STALE_NOOP}.
 *       {@code taskDueFired} is the <em>only</em> method that may return {@link #STALE_NOOP} —
 *       it does so when the workflow has legitimately moved past the due-date wait by the time
 *       the timer fires.</li>
 * </ul>
 *
 * <p>Public {@code TaskService} translation contract: {@link #APPLIED} and {@link #LOST_TO_RACE}
 * both translate to a successful {@code Future<Void>}; the public service never observes
 * {@link #STALE_NOOP} (only {@code taskDueFired} returns it, and that is invoked from inside the
 * timer-firing executor, not from the public service).
 */
public enum TaskMutationResult {

    /** The mutation was applied — the task row and workflow state were updated. */
    APPLIED,

    /**
     * The due-date timer fired but the task is no longer in a state that accepts expiration: the
     * workflow has legitimately moved past this wait (the task is no longer {@code OPEN}, the
     * instance is no longer {@code WAITING/TASK}, or the wait-slot keys do not match the firing
     * timer). Returned <em>only</em> by {@code taskDueFired}; <em>never</em> by
     * {@code taskCompleted} (which surfaces no-wait paths as typed exceptions) or
     * {@code taskReassigned} (which has no stale-no-wait path).
     */
    STALE_NOOP,

    /**
     * The dedup row already existed for the same idempotency key with a matching fingerprint —
     * a true idempotent retry of the same logical command. The original action already wrote the
     * task row and appended history; this retry is a no-op and the service layer translates it to
     * a successful {@code Future<Void>}. The dedup-row scope is per-task per-kind, so this never
     * collides across {@code task-complete} vs {@code task-reassign}.
     */
    LOST_TO_RACE
}
