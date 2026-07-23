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
 * <p>Failure modes for {@link #complete}:
 * <ul>
 *   <li>{@link TaskMutationResult#APPLIED} and {@link TaskMutationResult#LOST_TO_RACE} both
 *       produce a succeeded {@link Future} — {@code LOST_TO_RACE} indicates an idempotent retry
 *       of a completion that already succeeded; the caller need not distinguish it from
 *       {@code APPLIED}.</li>
 *   <li>{@link WorkflowTaskNotWaitingException} — the workflow is no longer waiting on this
 *       task (status / wait_type / wait_key mismatch).</li>
 *   <li>{@code WorkflowConflictException} — concurrent task transition or stale optimistic
 *       version.</li>
 *   <li>{@code WorkflowIdempotencyConflictException} — same idempotency key reused with a
 *       different fingerprint.</li>
 * </ul>
 *
 * <p>For {@link #reassign}, the same {@code APPLIED} / {@code LOST_TO_RACE} → success
 * translation applies; not-OPEN paths surface as {@code WorkflowConflictException}.
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
     * Completes a task with the decision and actor carried by the command. Idempotent on
     * {@code cmd.idempotencyKey()}. Opens its own transaction.
     *
     * <p>{@link TaskMutationResult#APPLIED} and {@link TaskMutationResult#LOST_TO_RACE} both
     * produce a succeeded {@link Future}. No-wait / not-OPEN paths surface as failed
     * {@link Future}s with typed exceptions ({@link WorkflowTaskNotWaitingException},
     * {@code WorkflowConflictException}, {@code WorkflowIdempotencyConflictException}). The
     * engine never returns {@link TaskMutationResult#STALE_NOOP} from completion.
     *
     * @param cmd the completion command; must not be null
     * @return a {@link Future} that completes when the task completion has been persisted
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
