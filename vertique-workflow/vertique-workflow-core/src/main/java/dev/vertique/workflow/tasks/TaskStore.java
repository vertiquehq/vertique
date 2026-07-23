// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.actor.WorkflowActor;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI for durable persistence of human task rows.
 *
 * <p>Implementations write to and read from the {@code workflow_tasks} table within the caller's
 * transaction, ensuring task state is always updated atomically with the workflow instance state
 * that depends on it.
 *
 * <p>Implementations must be transaction-scoped: every method that accepts a {@code TX} parameter
 * participates in the caller's transaction. Implementations must not open their own transactions.
 *
 * <p>This interface mirrors the shape of {@link dev.vertique.workflow.timer.TimerStore}. Mutating
 * methods that record an actor take a typed {@link WorkflowActor} parameter rather than a raw
 * {@code String} to preserve type safety through the entire persistence boundary.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL stack)
 */
public interface TaskStore<TX> {

    /**
     * Inserts a new task row with {@link TaskStatus#OPEN} status within the given transaction.
     *
     * <p>The task id in {@code record} must be unique. Implementations should propagate any
     * unique-constraint violation as a failed {@link Future}.
     *
     * @param record the task to insert; must have {@code status == OPEN}
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> insertOpen(TaskRecord record, TX tx);

    /**
     * Locks the task row identified by {@code taskId} for update, returning the record if the task
     * is in {@link TaskStatus#OPEN} status and therefore eligible for completion.
     *
     * <p>Returns {@link Optional#empty()} when the task does not exist or is already in a terminal
     * status ({@code COMPLETED}, {@code CANCELLED}, or {@code EXPIRED}). The lock prevents
     * concurrent completion attempts from applying the same task transition twice.
     *
     * @param taskId the task to lock
     * @param tx the active transaction context
     * @return a {@link Future} containing the locked task record, or empty if the task is not in
     *     {@code OPEN} status
     */
    Future<Optional<TaskRecord>> lockForCompletion(UUID taskId, TX tx);

    /**
     * Transitions the task identified by {@code taskId} from {@link TaskStatus#OPEN} to
     * {@link TaskStatus#COMPLETED} and records the decision and audit metadata.
     *
     * <p>If the task is already in a terminal status, returns the appropriate
     * {@link TaskTransition#LOST_TO_COMPLETED}, {@link TaskTransition#LOST_TO_CANCELLED}, or
     * {@link TaskTransition#LOST_TO_EXPIRED} constant instead of failing.
     *
     * @param taskId the task to transition
     * @param decisionName the name of the decision that was applied
     * @param payloadJson the JSON-serialized decision payload
     * @param completedAt the UTC timestamp to record as the completion time
     * @param completedBy the actor who completed the task
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the transition outcome
     */
    Future<TaskTransition> markCompleted(
            UUID taskId,
            String decisionName,
            String payloadJson,
            Instant completedAt,
            WorkflowActor completedBy,
            TX tx);

    /**
     * Transitions the task identified by {@code taskId} from {@link TaskStatus#OPEN} to
     * {@link TaskStatus#CANCELLED} and records the reason and audit metadata.
     *
     * <p>If the task is already in a terminal status, returns the appropriate
     * {@link TaskTransition} constant instead of failing.
     *
     * @param taskId the task to cancel
     * @param reason human-readable cancellation reason (e.g., {@code "WORKFLOW_CANCELLED"})
     * @param cancelledAt the UTC timestamp to record as the cancellation time
     * @param cancelledBy the actor who cancelled the task
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the transition outcome
     */
    Future<TaskTransition> markCancelled(
            UUID taskId, String reason, Instant cancelledAt, WorkflowActor cancelledBy, TX tx);

    /**
     * Transitions the task identified by {@code taskId} from {@link TaskStatus#OPEN} to
     * {@link TaskStatus#EXPIRED} and records the expiry time.
     *
     * <p>Called when the due-date timer fires before the task is completed. If the task is already
     * in a terminal status, returns the appropriate {@link TaskTransition} constant instead of
     * failing.
     *
     * @param taskId the task to expire
     * @param expiredAt the UTC timestamp to record as the expiry time
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the transition outcome
     */
    Future<TaskTransition> markExpired(UUID taskId, Instant expiredAt, TX tx);

    /**
     * Conditionally updates the assignment of the task identified by {@code taskId} to
     * {@code newAssignment}, provided the task is still in {@link TaskStatus#OPEN}.
     *
     * <p>Executes a single conditional {@code UPDATE workflow_tasks … WHERE task_id=$1 AND
     * status='OPEN'} and returns a {@link TaskReassignmentResult} indicating whether the update
     * was applied or the task had already reached a terminal state.
     *
     * @param taskId the task to reassign
     * @param newAssignment the new assignment to apply; must be a literal {@link TaskAssignment}
     * @param reassignedBy the actor performing the reassignment; required for audit
     * @param reason optional free-text audit note; null is acceptable
     * @param updatedAt the UTC timestamp to record as the update time
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the reassignment outcome
     */
    Future<TaskReassignmentResult> reassign(
            UUID taskId,
            TaskAssignment newAssignment,
            WorkflowActor reassignedBy,
            @Nullable String reason,
            Instant updatedAt,
            TX tx);

    /**
     * Queries tasks matching the given filter, returning a single page of results ordered by the
     * cursor's keyset specification.
     *
     * @param filter the query filter; all-null fields are treated as "no restriction"
     * @param cursor the page cursor specifying page size and optional keyset position
     * @param tx the active transaction context
     * @return a {@link Future} resolving to the paged result
     */
    Future<PagedResult<TaskRecord>> findByFilter(TaskFilter filter, PageCursor cursor, TX tx);

    /**
     * Retrieves the task row identified by {@code taskId} without acquiring a row lock.
     *
     * @param taskId the task id to look up
     * @param tx the active transaction context
     * @return a {@link Future} containing the task record, or empty if not found
     */
    Future<Optional<TaskRecord>> findById(UUID taskId, TX tx);

    /**
     * Returns all {@link TaskRecord}s in {@link TaskStatus#OPEN} status that are owned by the
     * given branch token.
     *
     * <p>Used by the branch-owned wait cancellation cascade ({@code cancelBranchOwnedWaits}) to
     * enumerate and close tasks after their owned timers have already been cancelled. Implements the
     * {@code tasks} step of the lock-order rule:
     * {@code workflow_timers → workflow_tasks → workflow_branch_tokens → workflow_instances}.
     *
     * @param branchTokenId the branch token whose owned open tasks should be returned
     * @param tx            the active transaction context
     * @return a {@link Future} resolving to the list of open task records owned by the branch;
     *     empty when the branch has no open tasks
     */
    Future<List<TaskRecord>> findOpenByBranchToken(UUID branchTokenId, TX tx);

    /**
     * Atomically increments the reminder-fire counter for the OPEN task identified by
     * {@code taskId} and returns the post-increment value. Implementations should issue a
     * {@code UPDATE … SET reminders_fired_count = reminders_fired_count + 1, updated_at = NOW()
     * WHERE task_id = $1 AND status = 'OPEN' RETURNING reminders_fired_count} so the increment is
     * race-safe against concurrent fires and naturally serialized per task by the row lock the
     * UPDATE acquires.
     *
     * <p>The returned value is the new count after the increment, suitable for use as the 1-based
     * {@code reminderIndex} on the {@code TASK_REMINDER_FIRED} history payload and the
     * {@code TASK_REMINDER} event's {@code reminderIndex} attribute.
     *
     * <p>Returns an empty {@link Optional} when the task no longer exists or is not OPEN. The
     * caller treats that as "task already closed" — fire the timer as a no-op without history or
     * event emission. This is the cycle-4 fix that replaces an unindexed JSONB-path COUNT over
     * {@code workflow_history}: counter maintenance is O(1) per fire, indexed by the task's PK,
     * and the reminder ordinal is now part of durable mutable task state rather than a derived
     * read of the immutable history ledger.
     *
     * @param taskId the task whose reminder counter to bump
     * @param tx     the active transaction
     * @return a {@link Future} containing the new (post-increment) reminder count, or empty when
     *     the task is missing or no longer OPEN
     */
    Future<Optional<Integer>> incrementRemindersFiredCount(UUID taskId, TX tx);
}
