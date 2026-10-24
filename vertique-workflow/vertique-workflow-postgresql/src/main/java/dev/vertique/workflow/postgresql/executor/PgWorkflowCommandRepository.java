// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final CommandEligibilitySql eligibility;
    private final String insertStart;
    private final String peekHeads;
    private final String claimCommand;
    private final String fenceCommand;
    private final String completeCommand;
    private final String rejectCommand;
    private final String releaseCommand;

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
        this.eligibility = new CommandEligibilitySql(schemas);
        String commands = eligibility.commandsTable();
        String instances = PgTableNames.qualify(schemas, "workflow", "workflow_instances");
        this.insertStart = "WITH recorded AS (SELECT clock_timestamp() AS at) INSERT INTO " + commands
                + " (workflow_id, seq, kind, state, recorded_at, available_at, payload_json, dedup_key, target_json)"
                + " SELECT $1, $2, 'START', 'PENDING', recorded.at, recorded.at, $3::jsonb, $4, $5::jsonb FROM recorded"
                + " RETURNING id";
        this.peekHeads = "SELECT c.id, c.workflow_id, c.kind, i.definition_scope_key, i.definition_id,"
                + " i.definition_version, i.plan_hash FROM " + commands + " c JOIN " + instances
                + " i ON i.id = c.workflow_id WHERE " + eligibility.claimable("c")
                + " ORDER BY c.available_at, c.id LIMIT $1";
        this.claimCommand = "UPDATE " + commands + " c SET state = 'CLAIMED', attempts = c.attempts + 1,"
                + " claim_token = gen_random_uuid(),"
                + " lease_until = clock_timestamp() + ($2::int * interval '1 second'), claimed_by = $3"
                + " WHERE c.id = $1 AND " + eligibility.claimable("c") + " RETURNING c.claim_token, c.attempts";
        this.fenceCommand =
                "SELECT c.id, c.payload_json::text AS payload_json, c.target_json::text AS target_json, c.dedup_key FROM "
                        + commands
                        + " c WHERE c.id = $1 AND c.state = 'CLAIMED' AND c.claim_token = $2 AND "
                        + eligibility.atHead("c")
                        + " FOR UPDATE OF c";
        String settle = " SET state = $3, claim_token = NULL, lease_until = NULL, settled_at = now() WHERE id = $1"
                + " AND state = 'CLAIMED' AND claim_token = $2";
        this.completeCommand = "UPDATE " + commands + settle;
        this.rejectCommand = completeCommand;
        this.releaseCommand = "UPDATE " + commands + " SET state = 'PENDING', attempts = attempts - 1,"
                + " claim_token = NULL, lease_until = NULL WHERE id = $1 AND state = 'CLAIMED' AND claim_token = $2";
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
    public Future<Long> recordStart(
            WorkflowInstanceId instance,
            String idempotencyKey,
            @Nullable String payloadJson,
            @Nullable String targetJson,
            SqlClient tx) {
        return nextQueueSequence(instance, tx)
                .compose(seq -> tx.preparedQuery(insertStart)
                        .execute(Tuple.of(
                                instance.value(), seq, jsonValue(payloadJson), idempotencyKey, jsonValue(targetJson))))
                .map(rows -> rows.iterator().next().getLong("id"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands recordStart")));
    }

    /** A JSON text as the value the PostgreSQL client binds to a {@code jsonb} parameter, so it is stored as JSON. */
    private static @Nullable Object jsonValue(@Nullable String json) {
        return json == null ? null : Json.decodeValue(json);
    }

    @Override
    public Future<List<CommandHead>> peek(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return pool.preparedQuery(peekHeads)
                .execute(Tuple.of(limit))
                .map(rows -> {
                    List<CommandHead> heads = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        heads.add(new CommandHead(
                                row.getLong("id"),
                                new WorkflowInstanceId(row.getUUID("workflow_id")),
                                row.getString("kind"),
                                row.getString("definition_scope_key"),
                                row.getString("definition_id"),
                                row.getLong("definition_version"),
                                row.getString("plan_hash")));
                    }
                    return heads;
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands peek")));
    }

    @Override
    public Future<Optional<ClaimedCommand>> claim(CommandHead head, String claimedBy, int leaseSeconds) {
        return pool.withTransaction(conn -> executionLock
                        .tryAcquire(head.workflowId(), conn)
                        .compose(locked -> {
                            if (!locked) {
                                return Future.succeededFuture(Optional.<ClaimedCommand>empty());
                            }
                            return conn.preparedQuery(claimCommand)
                                    .execute(Tuple.of(head.commandId(), leaseSeconds, claimedBy))
                                    .map(rows -> {
                                        if (rows.size() == 0) {
                                            return Optional.<ClaimedCommand>empty();
                                        }
                                        Row row = rows.iterator().next();
                                        return Optional.of(new ClaimedCommand(
                                                head.commandId(),
                                                head.workflowId(),
                                                head.kind(),
                                                row.getUUID("claim_token"),
                                                row.getInteger("attempts")));
                                    });
                        }))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands claim")));
    }

    @Override
    public Future<Optional<SegmentCommand>> fence(ClaimedCommand claim, SqlClient tx) {
        return tx.preparedQuery(fenceCommand)
                .execute(Tuple.of(claim.commandId(), claim.token()))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.<SegmentCommand>empty();
                    }
                    Row row = rows.iterator().next();
                    return Optional.of(new SegmentCommand(
                            claim.commandId(),
                            claim.workflowId(),
                            claim.kind(),
                            row.getString("payload_json"),
                            row.getString("target_json"),
                            row.getString("dedup_key")));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands fence")));
    }

    @Override
    public Future<Integer> complete(long commandId, UUID token, SqlClient tx) {
        return settle(completeCommand, "COMPLETE", commandId, token, tx, "complete");
    }

    @Override
    public Future<Integer> reject(long commandId, UUID token, SqlClient tx) {
        return settle(rejectCommand, "REJECTED", commandId, token, tx, "reject");
    }

    private Future<Integer> settle(
            String sql, String state, long commandId, UUID token, SqlClient tx, String operation) {
        return tx.preparedQuery(sql)
                .execute(Tuple.of(commandId, token, state))
                .map(RowSet::rowCount)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands " + operation)));
    }

    @Override
    public Future<Boolean> release(ClaimedCommand claim) {
        return pool.preparedQuery(releaseCommand)
                .execute(Tuple.of(claim.commandId(), claim.token()))
                .map(rows -> rows.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands release")));
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
