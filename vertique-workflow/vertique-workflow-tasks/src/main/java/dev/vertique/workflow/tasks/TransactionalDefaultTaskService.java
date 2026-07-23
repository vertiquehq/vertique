// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link TransactionalTaskService} backed by {@link TaskStore} and
 * {@link TransactionalTaskCallbacks}.
 *
 * <p>This class is package-private; it is exposed to callers through the
 * {@link TransactionalTaskService} interface bound in
 * {@link dev.vertique.workflow.tasks.di.WorkflowTasksModule}.
 *
 * <p>Responsibilities of this class:
 * <ul>
 *   <li>Argument null-checks at the service API boundary.</li>
 *   <li>Delegating list/get to {@link TaskStore} directly (no engine involvement needed).</li>
 *   <li>Delegating complete/reassign to {@link TransactionalTaskCallbacks} (the engine SPI does
 *       all the work: dedup, decision validation, payload coercion, persistence, history).</li>
 *   <li>Translating {@link TaskMutationResult} to {@link Future} outcomes per the service
 *       contract.</li>
 * </ul>
 *
 * <p>This class holds no reference to PostgreSQL internals; the module boundary
 * {@code workflow-tasks ⊄ workflow-postgresql} is preserved through the SPI.
 */
@Singleton
public final class TransactionalDefaultTaskService implements TransactionalTaskService<SqlClient> {

    private final TaskStore<SqlClient> taskStore;
    private final TransactionalTaskCallbacks<SqlClient> taskCallbacks;

    /**
     * Creates a new {@code TransactionalDefaultTaskService}.
     *
     * @param taskStore the task storage SPI; must not be null
     * @param taskCallbacks the engine callbacks SPI for completion and reassignment; must not be
     *     null
     */
    @Inject
    public TransactionalDefaultTaskService(
            TaskStore<SqlClient> taskStore, TransactionalTaskCallbacks<SqlClient> taskCallbacks) {
        this.taskStore = taskStore;
        this.taskCallbacks = taskCallbacks;
    }

    // --- List ---

    /**
     * {@inheritDoc}
     *
     * <p>Delegates directly to {@link TaskStore#findByFilter} — no engine involvement needed for
     * read-only queries.
     *
     * @param filter the task query filter; must not be null
     * @param cursor the keyset-pagination cursor; must not be null
     * @param tx the active transaction context; must not be null
     * @return a {@link Future} resolving to a paged result of matching task records
     */
    @Override
    public Future<PagedResult<TaskRecord>> list(TaskFilter filter, PageCursor cursor, SqlClient tx) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(tx, "tx");
        return taskStore.findByFilter(filter, cursor, tx);
    }

    // --- Get ---

    /**
     * {@inheritDoc}
     *
     * <p>Delegates directly to {@link TaskStore#findById} — no engine involvement needed for
     * read-only lookup.
     *
     * @param taskId the task id to look up; must not be null
     * @param tx the active transaction context; must not be null
     * @return a {@link Future} containing the task record, or empty if not found
     */
    @Override
    public Future<Optional<TaskRecord>> get(UUID taskId, SqlClient tx) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(tx, "tx");
        return taskStore.findById(taskId, tx);
    }

    // --- Complete ---

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link TransactionalTaskCallbacks#taskCompleted}. The SPI owns the entire
     * completion path: dedup, decision validation, payload coercion, persistence, and history.
     * The engine surfaces &quot;not waiting&quot; / &quot;already terminal&quot; as a failed
     * {@link Future} with {@link WorkflowTaskNotWaitingException} or
     * {@link dev.vertique.workflow.exception.WorkflowConflictException} <em>directly</em> — it does
     * not return {@link TaskMutationResult#STALE_NOOP} for completion. Translates the result:
     * <ul>
     *   <li>{@link TaskMutationResult#APPLIED} → succeeded {@link Future}.</li>
     *   <li>{@link TaskMutationResult#LOST_TO_RACE} → succeeded {@link Future} (idempotent retry;
     *       the caller need not distinguish this from {@code APPLIED}).</li>
     *   <li>{@link TaskMutationResult#STALE_NOOP} → contract violation; the SPI must not return
     *       this from {@code taskCompleted}. The service fails the future with
     *       {@link IllegalStateException} so a misbehaving impl is loud at runtime.</li>
     * </ul>
     *
     * @param cmd the completion command; must not be null
     * @param tx the active transaction context; must not be null
     * @return a {@link Future} that completes when the task completion has been persisted
     */
    @Override
    public Future<Void> complete(TaskCompletionCommand cmd, SqlClient tx) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(tx, "tx");
        return taskCallbacks.taskCompleted(cmd, tx).compose(result -> switch (result) {
            case APPLIED, LOST_TO_RACE -> Future.succeededFuture();
            case STALE_NOOP ->
                Future.failedFuture(new IllegalStateException(
                        "TransactionalTaskCallbacks.taskCompleted must not return STALE_NOOP for task '" + cmd.taskId()
                                + "'; the engine surfaces no-wait / not-OPEN paths as typed exceptions."));
        });
    }

    // --- Reassign ---

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link TransactionalTaskCallbacks#taskReassigned}. The SPI owns the entire
     * reassignment path: dedup, fingerprint validation, task update, and history. Translates
     * {@link TaskMutationResult} to a {@link Future} outcome:
     * <ul>
     *   <li>{@link TaskMutationResult#APPLIED} → succeeded {@link Future}.</li>
     *   <li>{@link TaskMutationResult#LOST_TO_RACE} → succeeded {@link Future} (idempotent retry;
     *       the caller need not distinguish this from {@code APPLIED}).</li>
     *   <li>{@link TaskMutationResult#STALE_NOOP} → failed {@link Future} with
     *       {@link IllegalStateException} — the SPI must not return this for reassignment; if it
     *       does, that is a contract violation.</li>
     * </ul>
     *
     * @param cmd the reassignment command; must not be null
     * @param tx the active transaction context; must not be null
     * @return a {@link Future} that completes when the reassignment has been persisted
     */
    @Override
    public Future<Void> reassign(TaskReassignmentCommand cmd, SqlClient tx) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(tx, "tx");
        return taskCallbacks.taskReassigned(cmd, tx).compose(result -> switch (result) {
            case APPLIED, LOST_TO_RACE -> Future.succeededFuture();
            case STALE_NOOP ->
                Future.failedFuture(new IllegalStateException(
                        "SPI contract violation: taskReassigned returned STALE_NOOP for task '" + cmd.taskId()
                                + "' — STALE_NOOP is not a valid reassignment result"));
        });
    }
}
