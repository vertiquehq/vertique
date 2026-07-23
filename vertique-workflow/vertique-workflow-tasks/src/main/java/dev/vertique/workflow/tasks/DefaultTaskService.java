// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link TaskService} that opens a SQL transaction per call via
 * {@code pool.withTransaction(...)} and delegates to {@link TransactionalTaskService}.
 *
 * <p>This class is package-private; it is exposed to callers through the {@link TaskService}
 * interface bound in {@link dev.vertique.workflow.tasks.di.WorkflowTasksModule}.
 *
 * <p>All transaction-management logic lives here; mutation semantics, result translation, and SPI
 * delegation live in {@link TransactionalDefaultTaskService}.
 */
@Singleton
public final class DefaultTaskService implements TaskService {

    private final Pool pool;
    private final TransactionalTaskService<SqlClient> txService;

    /**
     * Creates a new {@code DefaultTaskService}.
     *
     * @param pool the Vert.x connection pool used to open transactions; must not be null
     * @param txService the tx-aware task service implementation; must not be null
     */
    @Inject
    public DefaultTaskService(Pool pool, TransactionalTaskService<SqlClient> txService) {
        this.pool = pool;
        this.txService = txService;
    }

    // --- List ---

    /**
     * {@inheritDoc}
     *
     * <p>Opens a transaction with {@code pool.withTransaction} and delegates to
     * {@link TransactionalTaskService#list}.
     *
     * @param filter the task query filter; must not be null
     * @param cursor the keyset-pagination cursor; must not be null
     * @return a {@link Future} resolving to a paged result of matching task records
     */
    @Override
    public Future<PagedResult<TaskRecord>> list(TaskFilter filter, PageCursor cursor) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(cursor, "cursor");
        return pool.withTransaction(tx -> txService.list(filter, cursor, tx));
    }

    // --- Get ---

    /**
     * {@inheritDoc}
     *
     * <p>Opens a transaction with {@code pool.withTransaction} and delegates to
     * {@link TransactionalTaskService#get}.
     *
     * @param taskId the task id to look up; must not be null
     * @return a {@link Future} containing the task record, or empty if not found
     */
    @Override
    public Future<Optional<TaskRecord>> get(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return pool.withTransaction(tx -> txService.get(taskId, tx));
    }

    // --- Complete ---

    /**
     * {@inheritDoc}
     *
     * <p>Opens a transaction with {@code pool.withTransaction} and delegates to
     * {@link TransactionalTaskService#complete}.
     *
     * @param cmd the completion command; must not be null
     * @return a {@link Future} that completes when the task completion has been persisted
     */
    @Override
    public Future<Void> complete(TaskCompletionCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        return pool.withTransaction(tx -> txService.complete(cmd, tx));
    }

    // --- Reassign ---

    /**
     * {@inheritDoc}
     *
     * <p>Opens a transaction with {@code pool.withTransaction} and delegates to
     * {@link TransactionalTaskService#reassign}.
     *
     * @param cmd the reassignment command; must not be null
     * @return a {@link Future} that completes when the reassignment has been persisted
     */
    @Override
    public Future<Void> reassign(TaskReassignmentCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        return pool.withTransaction(tx -> txService.reassign(cmd, tx));
    }
}
