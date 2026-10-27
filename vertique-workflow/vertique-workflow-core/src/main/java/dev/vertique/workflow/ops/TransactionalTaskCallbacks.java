// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import io.vertx.core.Future;

/**
 * INTERNAL SPI — consumed only by {@code vertique-workflow-tasks}; never exposed to application
 * code directly.
 *
 * <p>Provides transactional callbacks that the task-service layer invokes when a human task is
 * completed or reassigned. Each method is called
 * inside an existing database transaction opened by the caller; implementations must not open
 * their own transactions.
 *
 * <p>This interface is generic over the transaction context type {@code TX} so that the
 * PostgreSQL implementation can use {@code SqlClient} without the core module depending on the
 * pg-client library.
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
     * <p>The engine <em>records</em> the completion in the caller's transaction and returns; the workflow executor
     * applies it afterwards. Without taking a lock it first returns the earlier result of a committed duplicate, then
     * checks the payload's size and depth and, when the task stores a JSON Schema for the decision, the payload's
     * conformance to it. Under the instance's execution lock it checks that the task is still {@code OPEN}, that a
     * supplied {@code expectedAssignment} still matches, that the reviewed subject version matches when the task
     * requires version stability, and that the task offers the decision. It then claims the idempotency key, records a
     * {@code COMPLETION} command and moves the task from {@code OPEN} to {@code COMPLETING}, owned by that command. The
     * executor completes the task and advances the workflow, or rejects the command and reopens the task.
     *
     * <p>Result contract:
     * <ul>
     *   <li>{@link TaskMutationResult#APPLIED} — the completion is recorded and the task reserved, or the key is a
     *       committed duplicate (same key and fingerprint) and this is its earlier result.</li>
     *   <li>{@link TaskMutationResult#LOST_TO_RACE} — not returned by the engine for a completion; a service layer
     *       translates it, like {@code APPLIED}, to a successful {@code Future<Void>} if another implementation does.</li>
     * </ul>
     *
     * <p>{@link TaskMutationResult#STALE_NOOP} is <strong>never</strong> returned by this method.
     * Refusals surface as failed {@code Future}s with typed exceptions, and write nothing:
     * {@code WorkflowInvalidPayloadException} (bounds, schema or an unknown decision),
     * {@code WorkflowTaskNotWaitingException} (the task is not {@code OPEN}: it is being completed or has ended),
     * {@code WorkflowStaleAssignmentException} (a supplied {@code expectedAssignment} no longer matches the locked
     * row), {@code WorkflowStaleSubjectVersionException} (a missing or different reviewed version on a task that
     * requires stability), or {@code WorkflowIdempotencyConflictException} (same idempotency key with a different
     * fingerprint). The {@code TaskService} layer treats {@link TaskMutationResult#STALE_NOOP} from this method as an
     * SPI contract violation and surfaces it as {@code IllegalStateException}.
     *
     * @param cmd the completion command carrying the task id, decision, payload, idempotency key,
     *     and actor
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the mutation result once the completion is recorded
     */
    Future<TaskMutationResult> taskCompleted(TaskCompletionCommand cmd, TX tx);

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
     * {@code WorkflowConflictException} (carrying the observed terminal transition), a supplied
     * {@code expectedAssignment} that no longer matches the locked row surfaces as
     * {@code WorkflowStaleAssignmentException}, and a fingerprint mismatch on the dedup row surfaces as
     * {@code WorkflowIdempotencyConflictException}. The service treats {@code STALE_NOOP} from this
     * SPI as an SPI contract violation and fails the future with {@code IllegalStateException}.
     *
     * @param cmd the reassignment command carrying the task id, new assignment, idempotency key,
     *     actor, and optional reason
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskReassigned(TaskReassignmentCommand cmd, TX tx);
}
