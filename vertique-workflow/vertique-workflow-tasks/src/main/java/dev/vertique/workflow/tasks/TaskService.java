// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import io.vertx.core.Future;
import java.util.Optional;
import java.util.UUID;

/**
 * Developer-facing task API. Each method opens its own SQL transaction via
 * {@code pool.withTransaction(...)} and delegates to {@link TransactionalTaskService}.
 *
 * <p>Argument validation rules:
 * <ul>
 *   <li>{@code filter} and {@code cursor} must not be null for {@link #list}.</li>
 *   <li>{@code taskId} must not be null for {@link #get}.</li>
 *   <li>{@code cmd} must not be null for {@link #complete} and {@link #reassign}; the command's
 *       compact constructor enforces non-null / non-blank fields internally.</li>
 * </ul>
 *
 * <p>{@link #complete} validates the completion and <em>records</em> it: its future succeeds once the completion is
 * recorded, the task is {@code COMPLETING} and the completion is waiting for the workflow executor, which applies it
 * afterwards in its own transaction. The task becomes {@code COMPLETED} and the instance advances only then; a
 * completion that cannot be applied is rejected and the task is {@code OPEN} again, so the caller may complete it again
 * with a new idempotency key. Use the execution probe ({@code awaitIdle}) to wait for the outcome.
 *
 * <p>A request is refused, with nothing recorded, by a failed {@link Future}:
 * <ul>
 *   <li>{@code WorkflowInvalidPayloadException} — the payload is over the record-time size or depth bound, violates
 *       the JSON Schema of the decision stored with the task, or names a decision the task does not offer.</li>
 *   <li>{@link WorkflowTaskNotWaitingException} — the task is no longer {@code OPEN}: it is already being completed
 *       ({@code COMPLETING}) or has ended.</li>
 *   <li>{@code WorkflowStaleSubjectVersionException} — the task requires version stability and the reviewed subject
 *       version is missing or differs from the one the task was created at.</li>
 *   <li>{@code WorkflowStaleAssignmentException} — a supplied {@code expectedAssignment} no longer matches the locked
 *       task row.</li>
 *   <li>{@code WorkflowIdempotencyConflictException} — same idempotency key reused with a different fingerprint.</li>
 * </ul>
 * A committed duplicate (same key, same fingerprint) succeeds with its earlier result before any of these checks.
 *
 * <p>For {@link #reassign}, the same {@code APPLIED} / {@code LOST_TO_RACE} → success
 * translation applies; not-OPEN paths surface as {@code WorkflowConflictException} (a {@code COMPLETING} task as its
 * subclass {@link WorkflowTaskNotWaitingException}) and a stale {@code expectedAssignment} as
 * {@code WorkflowStaleAssignmentException}.
 *
 * <p>{@link TaskMutationResult#STALE_NOOP} is reserved for the engine-internal due-date firing
 * path and is never observed by callers of this service. If a misbehaving SPI were to return it
 * from {@code complete} or {@code reassign}, the service surfaces it as
 * {@link IllegalStateException} (contract violation).
 */
public interface TaskService {

    /**
     * Returns a page of tasks matching the given filter. Opens its own transaction.
     *
     * @param filter the task query filter; must not be null; all-null fields match all tasks
     * @param cursor the keyset-pagination cursor; must not be null
     * @return a {@link Future} resolving to a paged result of matching task records
     */
    Future<PagedResult<TaskRecord>> list(TaskFilter filter, PageCursor cursor);

    /**
     * Returns the task with the given id, or empty if not found. Opens its own transaction.
     *
     * @param taskId the task id to look up; must not be null
     * @return a {@link Future} containing the task record, or empty if not found
     */
    Future<Optional<TaskRecord>> get(UUID taskId);

    /**
     * Records the completion of a task with the decision and actor carried by the command. Idempotent on
     * {@code cmd.idempotencyKey()}. Opens its own transaction.
     *
     * <p>The future succeeds once the completion is recorded, not once it is applied: the task is {@code COMPLETING}
     * and the executor applies the decision afterwards. {@link TaskMutationResult#APPLIED} (recorded, or a committed
     * duplicate's earlier result) and {@link TaskMutationResult#LOST_TO_RACE} both produce a succeeded
     * {@link Future}. A request the task refuses surfaces as a failed {@link Future} with a typed exception
     * ({@code WorkflowInvalidPayloadException}, {@link WorkflowTaskNotWaitingException},
     * {@code WorkflowStaleSubjectVersionException}, {@code WorkflowStaleAssignmentException},
     * {@code WorkflowIdempotencyConflictException}). The engine never returns
     * {@link TaskMutationResult#STALE_NOOP} from completion.
     *
     * @param cmd the completion command; must not be null
     * @return a {@link Future} that completes when the task completion has been recorded
     */
    Future<Void> complete(TaskCompletionCommand cmd);

    /**
     * Reassigns a task to the assignment carried by the command. Idempotent on
     * {@code cmd.idempotencyKey()}. Opens its own transaction.
     *
     * <p>Both {@link TaskMutationResult#APPLIED} and {@link TaskMutationResult#LOST_TO_RACE}
     * produce a succeeded {@link Future}. {@link TaskMutationResult#STALE_NOOP} is a contract
     * violation by the SPI and produces a failed {@link Future} with {@link IllegalStateException}.
     *
     * @param cmd the reassignment command; must not be null
     * @return a {@link Future} that completes when the reassignment has been persisted
     */
    Future<Void> reassign(TaskReassignmentCommand cmd);
}
