// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link WorkflowCommandRepository} over {@code workflow_commands} and
 * {@code workflow_command_queues}.
 *
 * <p>Each read borrows one pooled connection for one statement and returns it: no transaction is opened on a
 * caller's connection and no lock is taken. Eligibility is judged by {@link CommandEligibilitySql} on the database
 * clock. The lock and queue-sequence members run on the caller's transaction handle: the lock is the instance's
 * {@link ExecutionLock execution lock}, and the sequence is allocated from the instance's queue row under it.
 */
@Singleton
public final class PgWorkflowCommandRepository implements WorkflowCommandRepository<SqlClient> {

    private final Pool pool;
    private final PgDbExceptionMapper exceptionMapper;
    private final PgCommandProbeQueries queries;
    private final ExecutionLock executionLock;
    private final String insertQueue;
    private final String advanceQueue;

    /**
     * Creates a repository whose tables are not schema-qualified, so they resolve through the connection's default
     * schema.
     *
     * @param pool            the connection pool the reads borrow from
     * @param exceptionMapper the mapper used to translate SQL errors into typed exceptions
     */
    public PgWorkflowCommandRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        this(pool, exceptionMapper, DbSchemas.empty());
    }

    /**
     * Creates a repository that qualifies every table with the schema mapped to the {@code workflow} instance.
     *
     * @param pool            the connection pool the reads borrow from
     * @param exceptionMapper the mapper used to translate SQL errors into typed exceptions
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment identifier
     */
    public PgWorkflowCommandRepository(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        this(pool, exceptionMapper, schemas, WorkflowRecordingConfig.defaults());
    }

    /**
     * Creates a repository that qualifies every table with the schema mapped to the {@code workflow} instance and
     * waits for an execution lock as long as the recording configuration allows.
     *
     * @param pool            the connection pool the reads borrow from
     * @param exceptionMapper the mapper used to translate SQL errors into typed exceptions
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param recording       the recording configuration, whose lock timeout bounds {@link #lockInstance}
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment identifier
     */
    public PgWorkflowCommandRepository(
            Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas, WorkflowRecordingConfig recording) {
        this.pool = pool;
        this.exceptionMapper = exceptionMapper;
        this.queries = new PgCommandProbeQueries(new CommandEligibilitySql(schemas));
        this.executionLock = new ExecutionLock(recording.lockTimeoutMs());
        String queues = PgTableNames.qualify(schemas, "workflow", "workflow_command_queues");
        this.insertQueue = "INSERT INTO " + queues
                + " (workflow_id, next_seq) VALUES ($1, 1) ON CONFLICT (workflow_id) DO NOTHING";
        this.advanceQueue = "UPDATE " + queues
                + " SET next_seq = next_seq + 1 WHERE workflow_id = $1 RETURNING next_seq - 1 AS allocated";
    }

    @Override
    public Future<Void> lockInstance(WorkflowInstanceId instance, SqlClient tx) {
        return executionLock
                .acquire(instance, tx)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow execution lock")));
    }

    @Override
    public Future<Boolean> tryLockInstance(WorkflowInstanceId instance, SqlClient tx) {
        return executionLock
                .tryAcquire(instance, tx)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow execution lock try")));
    }

    @Override
    public Future<Long> nextQueueSequence(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(insertQueue)
                .execute(Tuple.of(instance.value()))
                .compose(created -> tx.preparedQuery(advanceQueue).execute(Tuple.of(instance.value())))
                .map(rows -> rows.iterator().next().getLong("allocated"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_command_queues sequence")));
    }

    @Override
    public Future<Integer> countRunnable(@Nullable WorkflowInstanceId instance) {
        return pool.preparedQuery(queries.countRunnable)
                .execute(scope(instance))
                .map(rows -> Math.toIntExact(rows.iterator().next().getLong(0)))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands countRunnable")));
    }

    @Override
    public Future<List<PendingCommand>> findDeferred(@Nullable WorkflowInstanceId instance) {
        return read(queries.findDeferred, instance, "findDeferred");
    }

    @Override
    public Future<List<PendingCommand>> findParked(@Nullable WorkflowInstanceId instance) {
        return read(queries.findParked, instance, "findParked");
    }

    @Override
    public Future<List<PendingCommand>> findNotYetAvailable(@Nullable WorkflowInstanceId instance) {
        return read(queries.findNotYetAvailable, instance, "findNotYetAvailable");
    }

    private Future<List<PendingCommand>> read(String sql, @Nullable WorkflowInstanceId instance, String operation) {
        return pool.preparedQuery(sql)
                .execute(scope(instance))
                .map(PgWorkflowCommandRepository::pendingCommands)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands " + operation)));
    }

    private static Tuple scope(@Nullable WorkflowInstanceId instance) {
        Tuple tuple = Tuple.tuple();
        tuple.addValue(instance == null ? null : instance.value());
        return tuple;
    }

    private static List<PendingCommand> pendingCommands(RowSet<Row> rows) {
        List<PendingCommand> commands = new ArrayList<>(rows.size());
        for (Row row : rows) {
            commands.add(new PendingCommand(
                    new WorkflowInstanceId(row.getUUID("workflow_id")),
                    row.getLong("seq"),
                    row.getString("kind"),
                    row.getString("state"),
                    row.getOffsetDateTime("available_at").toInstant(),
                    row.getInteger("attempts"),
                    row.getString("last_error")));
        }
        return commands;
    }
}
