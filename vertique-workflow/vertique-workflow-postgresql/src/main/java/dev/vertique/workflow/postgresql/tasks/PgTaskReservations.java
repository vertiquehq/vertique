// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.tasks;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.engine.spi.TaskReservations;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link TaskReservations} over {@code workflow_tasks.status} and
 * {@code workflow_tasks.reservation_command_id}.
 *
 * <p>Each member is one conditional {@code UPDATE} in the caller's transaction, so the compare and the set cannot be
 * separated by another writer. The statements do not touch the instance row or any history.
 */
@Singleton
public final class PgTaskReservations extends PgSqlRepository implements TaskReservations<SqlClient> {

    private static final String INSTANCE_NAME = "workflow";

    private final String sqlMarkCompleting;
    private final String sqlReopen;

    /**
     * Creates the reservations over tables that are not schema-qualified.
     *
     * @param pool            the connection pool
     * @param exceptionMapper translates SQL errors into typed domain exceptions
     */
    @Inject
    public PgTaskReservations(Pool pool, PgDbExceptionMapper exceptionMapper) {
        this(pool, exceptionMapper, DbSchemas.empty());
    }

    /**
     * Creates the reservations over the schema mapped to the {@code workflow} instance, when {@code schemas} holds one.
     *
     * @param pool            the connection pool
     * @param exceptionMapper translates SQL errors into typed domain exceptions
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     */
    public PgTaskReservations(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        super(pool, exceptionMapper);
        String tasks = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_tasks");
        this.sqlMarkCompleting = "UPDATE " + tasks
                + " SET status = 'COMPLETING', reservation_command_id = $2, updated_at = now()"
                + " WHERE task_id = $1 AND status = 'OPEN'";
        this.sqlReopen = "UPDATE " + tasks
                + " SET status = 'OPEN', reservation_command_id = NULL, updated_at = now()"
                + " WHERE task_id = $1 AND status = 'COMPLETING' AND reservation_command_id = $2";
    }

    @Override
    public Future<Boolean> markCompleting(String taskId, long commandId, SqlClient tx) {
        return update(sqlMarkCompleting, taskId, commandId, tx, "workflow_tasks markCompleting");
    }

    @Override
    public Future<Boolean> reopen(String taskId, long commandId, SqlClient tx) {
        return update(sqlReopen, taskId, commandId, tx, "workflow_tasks reopen");
    }

    private Future<Boolean> update(String sql, String taskId, long commandId, SqlClient tx, String operation) {
        return tx.preparedQuery(sql)
                .execute(Tuple.of(UUID.fromString(taskId), commandId))
                .map(rows -> rows.rowCount() == 1)
                .recover(failure -> Future.failedFuture(exceptionMapper.translate(failure, operation)));
    }
}
