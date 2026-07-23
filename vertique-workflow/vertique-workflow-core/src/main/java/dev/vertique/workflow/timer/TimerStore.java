// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI for durable persistence of workflow timers.
 *
 * <p>Implementations write to and read from the {@code workflow_timers} table within the caller's
 * transaction, ensuring timer state is always updated atomically with the workflow instance state
 * that depends on it.
 *
 * <p>Implementations must be transaction-scoped: every method that accepts a {@code TX} parameter
 * participates in the caller's transaction. Implementations must not open their own transactions.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL stack)
 */
public interface TimerStore<TX> {

    /**
     * Inserts a new timer row with {@link TimerStatus#SCHEDULED} status within the given
     * transaction.
     *
     * <p>The timer id in {@code record} must be unique. Implementations should propagate any
     * unique-constraint violation as a failed {@link Future}.
     *
     * @param record the timer to insert; must have {@code status == SCHEDULED}
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> insertScheduled(TimerRecord record, TX tx);

    /**
     * Returns the timer record for the given id without acquiring a row lock.
     *
     * <p>Used by the branch-timer callback path to read metadata (e.g., {@code branchTokenId})
     * from a timer that is already locked in the same transaction by the executor. A plain
     * {@code SELECT} on the same row in the same transaction is safe because Postgres returns the
     * current version of the row (no dirty read) and the lock is already held by the caller.
     *
     * @param timerId the timer id to look up
     * @param tx      the active transaction context
     * @return a {@link Future} resolving to the timer record, or {@link Optional#empty()} if not
     *     found
     */
    Future<Optional<TimerRecord>> findById(UUID timerId, TX tx);

    /**
     * Locks the timer row identified by {@code timerId} for update, returning the record if it
     * is in {@link TimerStatus#SCHEDULED} status and therefore eligible for firing.
     *
     * <p>Returns {@link Optional#empty()} when the timer does not exist or is already in a
     * terminal status ({@code FIRED}, {@code CANCELLED}, or {@code FAILED}). The lock prevents
     * concurrent fire attempts from applying the same timer twice.
     *
     * @param timerId the timer to lock
     * @param tx the active transaction context
     * @return a {@link Future} containing the locked timer record, or empty if the timer is not
     *     in {@code SCHEDULED} status
     */
    Future<Optional<TimerRecord>> lockForFiring(UUID timerId, TX tx);

    /**
     * Transitions the timer identified by {@code timerId} from {@link TimerStatus#SCHEDULED} to
     * {@link TimerStatus#FIRED} and sets {@code fired_at} to {@code firedAt}.
     *
     * <p>If the timer is already in a terminal status, returns the appropriate
     * {@link TimerStatusTransition#LOST_TO_FIRED}, {@link TimerStatusTransition#LOST_TO_CANCELLED},
     * or {@link TimerStatusTransition#LOST_TO_FAILED} constant instead of failing.
     *
     * @param timerId the timer to transition
     * @param firedAt the UTC timestamp to record as the fire time
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the transition outcome
     */
    Future<TimerStatusTransition> markFired(UUID timerId, Instant firedAt, TX tx);

    /**
     * Transitions the timer identified by {@code timerId} from {@link TimerStatus#SCHEDULED} to
     * {@link TimerStatus#CANCELLED} and sets {@code cancelled_at} to {@code cancelledAt}.
     *
     * <p>If the timer is already in a terminal status, returns the appropriate
     * {@link TimerStatusTransition} constant instead of failing.
     *
     * @param timerId the timer to cancel
     * @param cancelledAt the UTC timestamp to record as the cancellation time
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the transition outcome
     */
    Future<TimerStatusTransition> markCancelled(UUID timerId, Instant cancelledAt, TX tx);

    /**
     * Transitions the timer identified by {@code timerId} from {@link TimerStatus#SCHEDULED} to
     * {@link TimerStatus#FAILED} and records the failure reason.
     *
     * <p>If the timer is already in a terminal status, returns the appropriate
     * {@link TimerStatusTransition} constant instead of failing.
     *
     * @param timerId the timer to mark as failed
     * @param failedAt the UTC timestamp to record as the failure time
     * @param failureReason human-readable description of the failure cause; must not be null
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the transition outcome
     */
    Future<TimerStatusTransition> markFailed(UUID timerId, Instant failedAt, String failureReason, TX tx);

    /**
     * Finds timers that are in {@link TimerStatus#SCHEDULED} status and whose {@code fire_at} is
     * at or before {@code beforeFireAt}, ordered by {@code fire_at} ascending.
     *
     * <p>This method is used by the recovery/orphan-scan process to find timers whose delayed jobs
     * may have been lost (e.g., after a restart) and reschedule them. Results are returned in
     * ascending fire-time order so the earliest-overdue timers are processed first.
     *
     * @param beforeFireAt the cutoff instant; only timers with {@code fire_at <= beforeFireAt} are
     *     returned
     * @param limit the maximum number of records to return
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the list of recoverable scheduled timers, possibly empty
     */
    Future<List<TimerRecord>> findRecoverableScheduled(Instant beforeFireAt, int limit, TX tx);

    /**
     * Updates the {@code delayed_job_execution_id} of the timer identified by {@code timerId}.
     *
     * <p>Called by the recovery process after a timer's delayed job is rescheduled to record the
     * new execution id. The timer must exist; implementations should fail the {@link Future} if
     * the timer is not found.
     *
     * @param timerId the timer to update
     * @param delayedJobExecutionId the new delayed-job execution id to record
     * @param tx the active transaction context
     * @return a {@link Future} that completes when the column is updated
     */
    Future<Void> updateExecutionId(UUID timerId, UUID delayedJobExecutionId, TX tx);

    /**
     * Returns the timer ids of all {@link TimerStatus#SCHEDULED} reminder timers (purpose =
     * {@link TimerPurpose#TASK_REMINDER}) that belong to the given task.
     *
     * <p>Used by the engine's reminder cancel-cascade when a task transitions to a terminal
     * status ({@code COMPLETED}, {@code CANCELLED}, or {@code EXPIRED}). Reassignment does NOT
     * trigger the cascade; the new assignee inherits the existing reminder schedule.
     *
     * @param taskId the task id
     * @param tx     the active transaction context
     * @return a {@link Future} resolving to the list of scheduled reminder timer ids;
     *     empty when the task has no scheduled reminders
     */
    Future<List<UUID>> findScheduledRemindersForTask(UUID taskId, TX tx);

    /**
     * Returns all {@link TimerRecord}s in {@link TimerStatus#SCHEDULED} status that are owned by
     * the given branch token.
     *
     * <p>Used by the branch-owned wait cancellation cascade
     * ({@code cancelBranchOwnedWaits}) to enumerate and close timers before touching the branch
     * token row. Covers all timer purposes (standalone, signal-timeout, task-due, task-reminder)
     * that were created inside the branch.
     *
     * @param branchTokenId the branch token whose owned timers should be returned
     * @param tx            the active transaction context
     * @return a {@link Future} resolving to the list of scheduled timer records owned by the
     *     branch; empty when the branch has no scheduled timers
     */
    Future<List<TimerRecord>> findScheduledByBranchToken(UUID branchTokenId, TX tx);
}
