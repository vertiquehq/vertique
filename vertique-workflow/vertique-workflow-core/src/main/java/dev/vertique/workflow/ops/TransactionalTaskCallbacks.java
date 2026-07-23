// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import io.vertx.core.Future;
import java.time.Instant;
import java.util.UUID;

/**
 * INTERNAL SPI — consumed only by {@code vertique-workflow-tasks}; never exposed to application
 * code directly.
 *
 * <p>Provides transactional callbacks that the task-service layer invokes when a human task is
 * completed or reassigned, and when a due-date timer fires for a task. Each method is called
 * inside an existing database transaction opened by the caller; implementations must not open
 * their own transactions.
 *
 * <p>This interface is generic over the transaction context type {@code TX} so that the
 * PostgreSQL implementation can use {@code SqlClient} without the core module depending on the
 * pg-client library. This mirrors the design of
 * {@link TransactionalTimerCallbacks}.
 *
 * <p>Application code must never call these methods directly. They are part of the internal wiring
 * between the task-service layer and the workflow engine.
 *
 * @param <TX> the transaction context type (e.g., {@code SqlClient} in the PostgreSQL stack)
 */
public interface TransactionalTaskCallbacks<TX> {

    /**
     * Called by the task service when an actor submits a task completion.
     *
     * <p>The engine validates the decision name and payload, applies the decision applicator to
     * the workflow state, updates the workflow instance, and appends a {@code TASK_COMPLETED}
     * history entry — all within the caller's transaction.
     *
     * <p>Result contract:
     * <ul>
     *   <li>{@link TaskMutationResult#APPLIED} — the decision was applied and the workflow
     *       advanced.</li>
     *   <li>{@link TaskMutationResult#LOST_TO_RACE} — the dedup row already existed for the same
     *       idempotency key with a matching fingerprint (true idempotent retry of the same
     *       command). The original completion already advanced the workflow; the service layer
     *       translates this to a successful {@code Future<Void>}.</li>
     * </ul>
     *
     * <p>{@link TaskMutationResult#STALE_NOOP} is <strong>never</strong> returned by this method.
     * No-wait / not-OPEN paths surface as failed {@code Future}s with typed exceptions:
     * {@code WorkflowTaskNotWaitingException} (instance not WAITING / not TASK / wait-key
     * mismatch), {@code WorkflowConflictException} (concurrent task-row transition or stale
     * optimistic version), or {@code WorkflowIdempotencyConflictException} (same idempotency key
     * with a different fingerprint). The {@code TaskService} layer treats {@link
     * TaskMutationResult#STALE_NOOP} from this method as an SPI contract violation and surfaces it
     * as {@code IllegalStateException}.
     *
     * @param cmd the completion command carrying the task id, decision, payload, idempotency key,
     *     and actor
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskCompleted(TaskCompletionCommand cmd, TX tx);

    /**
     * Called by the due-date timer executor when a task's due-date timer fires.
     *
     * <p>The engine transitions the task to {@link dev.vertique.workflow.tasks.TaskStatus#EXPIRED},
     * applies the {@code onDueMutator} to the workflow state, advances to the
     * {@code dueNextStepId}, and appends a {@code TASK_EXPIRED} history entry — all within the
     * caller's transaction.
     *
     * <p>Result contract:
     * <ul>
     *   <li>{@link TaskMutationResult#APPLIED} — the task was expired and the workflow advanced.</li>
     *   <li>{@link TaskMutationResult#STALE_NOOP} — the workflow has legitimately moved past this
     *       wait by the time the timer fires. The implementation returns this when any of the
     *       following holds: the task row no longer exists, is no longer
     *       {@link dev.vertique.workflow.tasks.TaskStatus#OPEN}, the task does not belong to the
     *       supplied {@code workflowId}, the workflow instance is no longer
     *       {@code WAITING/TASK} with a matching {@code wait_key=taskId}, or the task's stored
     *       {@code due_date_timer_id} no longer matches the instance's {@code wait_aux_id}.</li>
     * </ul>
     *
     * <p>This is the <em>only</em> method that may return {@link TaskMutationResult#STALE_NOOP}.
     *
     * @param workflowId the workflow instance this task belongs to
     * @param taskId the stable UUID of the task whose due-date timer fired
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskDueFired(WorkflowInstanceId workflowId, UUID taskId, TX tx);

    /**
     * Called by the task service when an actor requests a task reassignment.
     *
     * <p>The engine updates the task assignment, records the {@code reassignedBy} actor and
     * optional reason, and appends a {@code TASK_REASSIGNED} history entry — all within the
     * caller's transaction.
     *
     * <p>Result contract:
     * <ul>
     *   <li>{@link TaskMutationResult#APPLIED} — the assignment was updated and history written.</li>
     *   <li>{@link TaskMutationResult#LOST_TO_RACE} — the dedup row already existed for the same
     *       idempotency key with a matching fingerprint (true idempotent retry). The service layer
     *       translates this to a successful {@code Future<Void>}.</li>
     * </ul>
     *
     * <p>{@link TaskMutationResult#STALE_NOOP} is <strong>never</strong> returned by this method —
     * reassignment has no stale-no-wait path. A task that is no longer
     * {@link dev.vertique.workflow.tasks.TaskStatus#OPEN} surfaces as a failed {@code Future} with
     * {@code WorkflowConflictException} (carrying the observed terminal transition), and a
     * fingerprint mismatch on the dedup row surfaces as
     * {@code WorkflowIdempotencyConflictException}. The service treats {@code STALE_NOOP} from this
     * SPI as an SPI contract violation and fails the future with {@code IllegalStateException}.
     *
     * @param cmd the reassignment command carrying the task id, new assignment, idempotency key,
     *     actor, and optional reason
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskReassigned(TaskReassignmentCommand cmd, TX tx);

    /**
     * Invoked by the timer-fire executor when a {@link dev.vertique.workflow.timer.TimerPurpose#TASK_REMINDER}-purpose
     * timer fires.
     *
     * <p>Returns {@link io.vertx.core.Future#succeededFuture()} for both the "applied" path
     * (history entry appended + {@link dev.vertique.workflow.events.WorkflowEventType#TASK_REMINDER}
     * event emitted) and the "no-op" path (task already in a terminal status). The executor
     * unconditionally calls {@code timerStore.markFired} on a succeeded future. There is no
     * {@link TaskMutationResult#STALE_NOOP} path because a reminder firing on a closed task is a
     * normal outcome, not a timer inconsistency. Failed futures are propagated and trigger
     * delayed-job retry.
     *
     * <p>For {@link dev.vertique.workflow.plan.ReminderSpec.RecurringInterval} reminders the engine
     * uses {@code scheduledFireAt} as the anchor for the next interval — the next reminder is
     * scheduled at {@code scheduledFireAt + interval}, NOT {@code now + interval}, so executor
     * delays do not accumulate as drift across many fires.
     *
     * @param workflowId       the owning workflow instance id
     * @param taskId           the stable UUID of the task (durable reminder identity)
     * @param timerId          the UUID of the firing timer
     * @param scheduledFireAt  the firing timer's persisted {@code fire_at} instant; used as the
     *     anchor for the next recurring reminder so cadence stays steady across executor delays
     * @param tx               the active transaction context; all writes must use this context
     * @return a succeeded future on both the applied and no-op paths; a failed future on
     *     transient errors
     */
    Future<Void> taskReminderFired(
            WorkflowInstanceId workflowId, UUID taskId, UUID timerId, Instant scheduledFireAt, TX tx);
}
