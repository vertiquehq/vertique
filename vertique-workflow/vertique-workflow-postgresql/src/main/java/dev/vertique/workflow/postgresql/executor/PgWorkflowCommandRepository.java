// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.Deferral;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DispositionResult;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.QueueCounts;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntToLongFunction;

/**
 * PostgreSQL implementation of {@link WorkflowCommandRepository} over {@code workflow_commands} and
 * {@code workflow_command_queues}.
 *
 * <p>Each read borrows one pooled connection for one statement and returns it: no transaction is opened on a
 * caller's connection and no lock is taken. Eligibility is judged by {@link CommandEligibilitySql} on the database
 * clock. The lock and queue-sequence members run on the caller's transaction handle: the lock is the instance's
 * {@link ExecutionLock execution lock}, and the sequence is allocated from the instance's queue row under it.
 *
 * <p>The claim and the failure disposition each run in a short transaction of their own that only tries the
 * execution lock. The claim settles a command whose instance has ended and dead-letters a command that spent its
 * attempts, and the failure disposition settles a command whose segment failed; the transient retry and the release
 * lock only the command row. Every write of a reason stores a sanitized, bounded value built by
 * {@link CommandErrors}.
 */
@Singleton
public final class PgWorkflowCommandRepository implements WorkflowCommandRepository<SqlClient> {

    /** The instance statuses that are ended: the instance takes no further command. */
    private static final String ENDED = "('COMPLETED','COMPENSATED','CANCELLED','EXPIRED')";

    private static final String ENDED_OR_FAILED = "('COMPLETED','COMPENSATED','CANCELLED','EXPIRED','FAILED')";

    private final Pool pool;
    private final PgDbExceptionMapper exceptionMapper;
    private final PgCommandProbeQueries queries;
    private final PgCommandWakeupQueries wakeupQueries;
    private final ExecutionLock executionLock;
    private final String insertQueue;
    private final String advanceQueue;
    private final CommandEligibilitySql eligibility;
    private final String insertStart;
    private final String peekHeads;
    private final String claimLookup;
    private final String claimTake;
    private final String lockForDeferral;
    private final String deferRow;
    private final String settleEnded;
    private final String deadLetter;
    private final String failCommandRow;
    private final String failInstance;
    private final String insertHistory;
    private final String lockForTransientRetry;
    private final String retryTransientRow;
    private final String lockForFailure;
    private final String fenceCommand;
    private final String completeCommand;
    private final String rejectCommand;
    private final String releaseCommand;
    private final String settleClaimed;
    private final String settleParkedRow;
    private final String parkCommand;
    private final String requeueParkedRow;
    private final String raiseOpenSignals;
    private final String lowerOpenSignals;
    private final String insertSignal;
    private final String declaredNames;
    private final String parkedAt;
    private final String parkedAll;
    private final String readParkedRow;
    private final String blockedByDeadline;
    private final String parkedBefore;

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
        this.wakeupQueries = new PgCommandWakeupQueries(new CommandEligibilitySql(schemas));
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
                + " i.definition_version, i.plan_hash, i.status IN " + ENDED + " AS instance_ended FROM " + commands
                + " c JOIN " + instances
                + " i ON i.id = c.workflow_id WHERE " + eligibility.claimable("c")
                + " ORDER BY c.available_at, c.id LIMIT $1";
        this.claimLookup =
                "SELECT c.state, c.attempts, c.kind, i.status AS instance_status, c.target_json->>'name' AS signal_name,"
                        + " c.target_json->>'branchId' IS NOT NULL AS branch_signal FROM " + commands + " c JOIN "
                        + instances + " i ON i.id = c.workflow_id WHERE c.id = $1 AND " + eligibility.claimable("c")
                        + " FOR UPDATE OF c";
        this.claimTake = "UPDATE " + commands + " SET state = 'CLAIMED', attempts = attempts + 1,"
                + " claim_token = gen_random_uuid(),"
                + " lease_until = clock_timestamp() + ($2::int * interval '1 second'), claimed_by = $3"
                + " WHERE id = $1 RETURNING claim_token, attempts";
        this.lockForDeferral = "SELECT c.seq, c.kind, c.deferral_count FROM " + commands + " c JOIN " + instances
                + " i ON i.id = c.workflow_id WHERE c.id = $1 AND " + eligibility.claimable("c")
                + " AND i.definition_scope_key = $2 AND i.definition_id = $3 AND i.definition_version = $4"
                + " AND i.plan_hash IS NOT DISTINCT FROM $5::text FOR UPDATE OF c";
        this.deferRow = "UPDATE " + commands + " SET state = 'DEFERRED', deferral_count = $2,"
                + " available_at = clock_timestamp() + ($3::float8 * interval '1 millisecond'), last_error = $4"
                + " WHERE id = $1";
        // Every write that takes a SIGNAL out of its open states frees one slot of the instance's open-signal count in
        // the same statement; the CTE is executed whether or not the outer query reads it.
        this.settleEnded = withLowering(
                queues,
                "UPDATE " + commands + " SET state = $3, claim_token = NULL, lease_until = NULL,"
                        + " settled_at = now(), last_error = $2 WHERE id = $1");
        this.deadLetter = withLowering(
                queues,
                "UPDATE " + commands + " SET state = 'DEAD', claim_token = NULL, lease_until = NULL,"
                        + " last_error = $2 WHERE id = $1");
        this.failCommandRow = withLowering(
                queues,
                "UPDATE " + commands + " SET state = 'FAILED', claim_token = NULL, lease_until = NULL,"
                        + " settled_at = now(), last_error = $2 WHERE id = $1");
        this.failInstance = "UPDATE " + instances + " SET status = 'FAILED', error_type = $2, error_message = $3,"
                + " version = version + 1, wait_type = NULL, wait_key = NULL, wait_aux_id = NULL,"
                + " updated_at = now(), completed_at = now() WHERE id = $1 AND status NOT IN " + ENDED_OR_FAILED
                + " RETURNING id";
        String history = PgTableNames.qualify(schemas, "workflow", "workflow_history");
        this.insertHistory = "INSERT INTO " + history
                + " (workflow_id, sequence, entry_type, payload_json, recorded_at, command_id, outcome)"
                + " SELECT $1::uuid, COALESCE(MAX(sequence), 0) + 1, $2, $3::jsonb, now(), $4::bigint, $5 FROM "
                + history + " WHERE workflow_id = $1::uuid RETURNING sequence";
        this.lockForTransientRetry = "SELECT transient_count FROM " + commands
                + " WHERE id = $1 AND state = 'CLAIMED' AND claim_token = $2 AND lease_until > now() FOR UPDATE";
        this.retryTransientRow = "UPDATE " + commands + " SET state = 'PENDING', attempts = attempts - 1,"
                + " transient_count = $2, available_at = clock_timestamp() + ($3::float8 * interval '1 millisecond'),"
                + " claim_token = NULL, lease_until = NULL, last_error = $4 WHERE id = $1";
        this.lockForFailure =
                "SELECT kind FROM " + commands + " WHERE id = $1 AND state = 'CLAIMED' AND claim_token = $2 FOR UPDATE";
        this.fenceCommand =
                "SELECT c.id, c.payload_json::text AS payload_json, c.target_json::text AS target_json, c.dedup_key,"
                        + " c.explicit_carrier_json::text AS explicit_carrier_json FROM "
                        + commands
                        + " c WHERE c.id = $1 AND c.state = 'CLAIMED' AND c.claim_token = $2 AND "
                        + eligibility.atHead("c")
                        + " FOR UPDATE OF c";
        this.settleClaimed = withLowering(
                queues,
                "UPDATE " + commands + " SET state = $3, claim_token = NULL, lease_until = NULL, settled_at = now(),"
                        + " last_error = COALESCE($4::text, last_error) WHERE id = $1 AND state = 'CLAIMED'"
                        + " AND claim_token = $2");
        this.completeCommand = settleClaimed;
        this.rejectCommand = settleClaimed;
        this.settleParkedRow = withLowering(
                queues,
                "UPDATE " + commands
                        + " SET state = $2, settled_at = now(), last_error = COALESCE($3::text, last_error)"
                        + " WHERE id = $1 AND state = 'PARKED'");
        this.parkCommand = "UPDATE " + commands + " SET state = 'PARKED', attempts = attempts - 1, claim_token = NULL,"
                + " lease_until = NULL WHERE id = $1 AND state = 'CLAIMED' AND claim_token = $2";
        this.requeueParkedRow = "UPDATE " + commands + " SET state = 'PENDING',"
                + " available_at = LEAST(available_at, clock_timestamp()) WHERE id = $1 AND state = 'PARKED'";
        this.raiseOpenSignals = "UPDATE " + queues
                + " SET open_signals = open_signals + 1 WHERE workflow_id = $1 AND open_signals < $2";
        this.lowerOpenSignals =
                "UPDATE " + queues + " SET open_signals = open_signals - 1 WHERE workflow_id = $1 AND open_signals > 0";
        this.insertSignal = "WITH recorded AS (SELECT clock_timestamp() AS at) INSERT INTO " + commands
                + " (workflow_id, seq, kind, state, recorded_at, available_at, payload_json, dedup_key, target_json,"
                + " explicit_carrier_json) SELECT $1, $2, 'SIGNAL', 'PENDING', recorded.at, recorded.at, $3::jsonb,"
                + " $4, $5::jsonb, $6::jsonb FROM recorded RETURNING id";
        this.declaredNames = "SELECT signal_names_json::text AS names FROM "
                + PgTableNames.qualify(schemas, "workflow", "workflow_definitions")
                + " WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3";
        String parked = "SELECT c.id, c.seq, c.target_json->>'name' AS signal_name, c.target_json->>'forkStepId' AS"
                + " fork_step_id, c.target_json->>'branchId' AS branch_id, c.recorded_at FROM " + commands
                + " c WHERE c.workflow_id = $1 AND c.kind = 'SIGNAL' AND c.state = 'PARKED'";
        this.parkedAt =
                parked + " AND c.target_json->>'name' = $2 AND c.target_json->>'forkStepId' IS NOT DISTINCT FROM $3"
                        + " AND c.target_json->>'branchId' IS NOT DISTINCT FROM $4 ORDER BY c.seq";
        this.parkedAll = parked + " ORDER BY c.seq";
        this.readParkedRow =
                "SELECT c.payload_json::text AS payload_json, c.target_json::text AS target_json, c.dedup_key,"
                        + " c.explicit_carrier_json::text AS explicit_carrier_json FROM " + commands
                        + " c WHERE c.workflow_id = $1 AND c.id = $2 AND c.state = 'PARKED'";
        this.parkedBefore = "SELECT EXISTS (SELECT 1 FROM " + history
                + " WHERE workflow_id = $1 AND entry_type = 'SIGNAL_PARKED' AND (payload_json->>'commandId')::bigint = $2)"
                + " AS parked";
        this.blockedByDeadline =
                "SELECT s.id FROM " + commands + " s WHERE s.workflow_id = $1 AND s.id = ANY($2::bigint[])"
                        + " AND EXISTS (SELECT 1 FROM " + commands
                        + " t WHERE t.workflow_id = s.workflow_id AND t.kind = 'TIMER'"
                        + " AND t.timer_kind = 'TIMEOUT' AND t.target_json->>'stepId' = $3"
                        + " AND t.target_json->>'branchId' IS NOT DISTINCT FROM $4 AND t.fire_at <= s.recorded_at"
                        + " AND t.state IN ('PENDING','DEFERRED','CLAIMED','DEAD'))";
        this.releaseCommand = "UPDATE " + commands + " SET state = 'PENDING', attempts = attempts - 1,"
                + " claim_token = NULL, lease_until = NULL WHERE id = $1 AND state = 'CLAIMED' AND claim_token = $2"
                + " AND lease_until > now()";
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
                                row.getString("plan_hash"),
                                row.getBoolean("instance_ended")));
                    }
                    return heads;
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands peek")));
    }

    @Override
    public Future<Optional<ClaimedCommand>> claim(
            CommandHead head,
            String claimedBy,
            int leaseSeconds,
            int maxAttempts,
            InstanceFailureListener<SqlClient> failures) {
        return pool.withTransaction(conn -> executionLock
                        .tryAcquire(head.workflowId(), conn)
                        .compose(locked -> {
                            if (!locked) {
                                return Future.succeededFuture(Optional.<ClaimedCommand>empty());
                            }
                            return conn.preparedQuery(claimLookup)
                                    .execute(Tuple.of(head.commandId()))
                                    .compose(rows -> {
                                        if (rows.size() == 0) {
                                            return Future.succeededFuture(Optional.<ClaimedCommand>empty());
                                        }
                                        return claimLocked(
                                                        head,
                                                        rows.iterator().next(),
                                                        claimedBy,
                                                        leaseSeconds,
                                                        maxAttempts,
                                                        failures,
                                                        conn)
                                                .map(Optional::of);
                                    });
                        }))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands claim")));
    }

    @Override
    public Future<Integer> defer(CommandHead head) {
        return pool.withTransaction(conn -> executionLock
                        .tryAcquire(head.workflowId(), conn)
                        .compose(locked -> {
                            if (!locked) {
                                return Future.succeededFuture(0);
                            }
                            // claimable already requires the command to be available now, so a command deferred into
                            // the future fails this read as well
                            return conn.preparedQuery(lockForDeferral)
                                    .execute(Tuple.of(
                                            head.commandId(),
                                            head.definitionScopeKey(),
                                            head.definitionId(),
                                            head.definitionVersion(),
                                            head.planHash()))
                                    .compose(rows -> {
                                        if (rows.size() == 0) {
                                            return Future.succeededFuture(0);
                                        }
                                        return deferLocked(head, rows.iterator().next(), conn);
                                    });
                        }))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands defer")));
    }

    /** The deferral of a command that was locked and found claimable and still pinned as observed. */
    private Future<Integer> deferLocked(CommandHead head, Row looked, SqlClient conn) {
        int before = looked.getInteger("deferral_count");
        String kind = looked.getString("kind");
        String reason = CommandErrors.notRunnable(kind);
        return conn.preparedQuery(deferRow)
                .execute(Tuple.of(head.commandId(), before + 1, (double) Deferral.backoffMs(before), reason))
                .compose(ignored -> {
                    if (before > 0) {
                        return Future.<Void>succeededFuture();
                    }
                    JsonObject payload = new JsonObject()
                            .put("commandId", head.commandId())
                            .put("seq", looked.getLong("seq"))
                            .put("kind", kind)
                            .put("reason", reason);
                    return appendHistory(
                                    conn, head.workflowId(), head.commandId(), "COMMAND_DEFERRED", payload, "DEFERRED")
                            .mapEmpty();
                })
                .map(ignored -> before + 1);
    }

    /**
     * The claim of a command that was locked and found claimable: settles it when its instance has ended, dead-letters
     * it when its attempts are spent, and otherwise takes it.
     */
    private Future<ClaimedCommand> claimLocked(
            CommandHead head,
            Row looked,
            String claimedBy,
            int leaseSeconds,
            int maxAttempts,
            InstanceFailureListener<SqlClient> failures,
            SqlClient conn) {
        String kind = looked.getString("kind");
        int attempts = looked.getInteger("attempts");
        boolean lapsed = "CLAIMED".equals(looked.getString("state"));
        String instanceStatus = looked.getString("instance_status");
        long id = head.commandId();
        if (isEnded(instanceStatus)) {
            // The outcome table settles a signal of an ended instance as rejected, and a branch-addressed one as stale.
            boolean stale = "SIGNAL".equals(kind) && Boolean.TRUE.equals(looked.getBoolean("branch_signal"));
            String state = stale ? "STALE" : "REJECTED";
            String reason = "SIGNAL".equals(kind)
                    ? CommandErrors.signalInstanceEnded(looked.getString("signal_name"), instanceStatus, stale)
                    : CommandErrors.instanceEnded(kind, instanceStatus);
            return conn.preparedQuery(settleEnded)
                    .execute(Tuple.of(id, reason, state))
                    .compose(ignored -> appendCommandHistory(
                            conn, head, stale ? "COMMAND_STALE" : "COMMAND_REJECTED", reason, state))
                    .map(ignored -> new ClaimedCommand(
                            id,
                            head.workflowId(),
                            kind,
                            null,
                            attempts,
                            ClaimedCommand.Result.SETTLED_INSTANCE_ENDED,
                            lapsed));
        }
        if (attempts >= maxAttempts) {
            String reason = CommandErrors.attemptsExhausted(kind, attempts, maxAttempts);
            Future<Void> written = conn.preparedQuery(deadLetter)
                    .execute(Tuple.of(id, reason))
                    .compose(ignored ->
                            appendCommandHistory(conn, head, "COMMAND_DEAD_LETTERED", reason, "DEAD_LETTERED"));
            if ("START".equals(kind)) {
                written = written.compose(ignored -> failInstanceOf(
                        conn, head.workflowId(), id, CommandErrors.Reason.ATTEMPTS_EXHAUSTED.name(), reason, failures));
            }
            return written.map(ignored -> new ClaimedCommand(
                    id, head.workflowId(), kind, null, attempts, ClaimedCommand.Result.DEAD_LETTERED, lapsed));
        }
        return conn.preparedQuery(claimTake)
                .execute(Tuple.of(id, leaseSeconds, claimedBy))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new ClaimedCommand(
                            id,
                            head.workflowId(),
                            kind,
                            row.getUUID("claim_token"),
                            row.getInteger("attempts"),
                            ClaimedCommand.Result.CLAIMED,
                            lapsed);
                });
    }

    private static boolean isEnded(String instanceStatus) {
        return switch (instanceStatus) {
            case "COMPLETED", "COMPENSATED", "CANCELLED", "EXPIRED" -> true;
            default -> false;
        };
    }

    /**
     * Fails the instance with the reason and writes the history entry of the failure, unless the instance already
     * ended or failed.
     */
    private Future<Void> failInstanceOf(
            SqlClient conn,
            WorkflowInstanceId workflow,
            long commandId,
            String reasonCode,
            String message,
            InstanceFailureListener<SqlClient> failures) {
        return conn.preparedQuery(failInstance)
                .execute(Tuple.of(workflow.value(), reasonCode, message))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.<Void>succeededFuture();
                    }
                    JsonObject payload = new JsonObject()
                            .putNull("stepId")
                            .put("errorType", reasonCode)
                            .put("errorMessage", message);
                    return appendHistory(conn, workflow, commandId, "FAILED", payload, "FAILED")
                            .compose(sequence ->
                                    failures.onInstanceFailed(workflow, sequence, reasonCode, message, conn));
                });
    }

    private Future<Void> appendCommandHistory(
            SqlClient conn, CommandHead head, String entryType, String reason, String outcome) {
        JsonObject payload = new JsonObject()
                .put("commandId", head.commandId())
                .put("kind", head.kind())
                .put("reason", reason);
        return appendHistory(conn, head.workflowId(), head.commandId(), entryType, payload, outcome)
                .mapEmpty();
    }

    private Future<Long> appendHistory(
            SqlClient conn,
            WorkflowInstanceId workflow,
            long commandId,
            String entryType,
            JsonObject payload,
            String outcome) {
        return conn.preparedQuery(insertHistory)
                .execute(Tuple.of(workflow.value(), entryType, payload, commandId, outcome))
                .map(rows -> rows.iterator().next().getLong("sequence"));
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
                            row.getString("dedup_key"),
                            row.getString("explicit_carrier_json")));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands fence")));
    }

    @Override
    public Future<Integer> complete(long commandId, UUID token, SqlClient tx) {
        return settle(completeCommand, "COMPLETE", commandId, token, null, tx, "complete");
    }

    @Override
    public Future<Integer> reject(long commandId, UUID token, SqlClient tx) {
        return settle(rejectCommand, "REJECTED", commandId, token, null, tx, "reject");
    }

    @Override
    public Future<Integer> reject(long commandId, UUID token, String lastError, SqlClient tx) {
        return settle(rejectCommand, "REJECTED", commandId, token, lastError, tx, "reject");
    }

    @Override
    public Future<Integer> stale(long commandId, UUID token, String lastError, SqlClient tx) {
        return settle(settleClaimed, "STALE", commandId, token, lastError, tx, "stale");
    }

    private Future<Integer> settle(
            String sql,
            String state,
            long commandId,
            UUID token,
            @Nullable String lastError,
            SqlClient tx,
            String operation) {
        Tuple parameters = Tuple.tuple();
        parameters.addValue(commandId);
        parameters.addValue(token);
        parameters.addValue(state);
        parameters.addValue(lastError);
        return tx.preparedQuery(sql)
                .execute(parameters)
                .map(rows -> rows.iterator().next().getInteger("n"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands " + operation)));
    }

    @Override
    public Future<Integer> park(long commandId, UUID token, SqlClient tx) {
        return tx.preparedQuery(parkCommand)
                .execute(Tuple.of(commandId, token))
                .map(RowSet::rowCount)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands park")));
    }

    @Override
    public Future<Optional<List<String>>> declaredSignalNames(
            DefinitionKey pin, @Nullable String forkStepId, @Nullable String branchId, SqlClient tx) {
        return tx.preparedQuery(declaredNames)
                .execute(Tuple.of(pin.scopeKey(), pin.definitionId(), pin.definitionVersion()))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.<List<String>>empty();
                    }
                    String stored = rows.iterator().next().getString("names");
                    if (stored == null) {
                        return Optional.<List<String>>empty();
                    }
                    JsonObject names = new JsonObject(stored);
                    JsonArray declared;
                    if (forkStepId == null || branchId == null) {
                        declared = names.getJsonArray("instance");
                    } else {
                        JsonObject branches = names.getJsonObject("branches");
                        declared = branches == null ? null : branches.getJsonArray(forkStepId + "/" + branchId);
                    }
                    List<String> result = new ArrayList<>();
                    if (declared != null) {
                        declared.forEach(name -> result.add(String.valueOf(name)));
                    }
                    return Optional.of(List.copyOf(result));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_definitions signal names")));
    }

    @Override
    public Future<Boolean> raiseOpenSignals(WorkflowInstanceId instance, int limit, SqlClient tx) {
        return tx.preparedQuery(insertQueue)
                .execute(Tuple.of(instance.value()))
                .compose(created -> tx.preparedQuery(raiseOpenSignals).execute(Tuple.of(instance.value(), limit)))
                .map(rows -> rows.rowCount() > 0)
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_command_queues open signals")));
    }

    @Override
    public Future<Void> lowerOpenSignals(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(lowerOpenSignals)
                .execute(Tuple.of(instance.value()))
                .<Void>mapEmpty()
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_command_queues open signals")));
    }

    @Override
    public Future<Long> recordSignal(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            String dedupKey,
            @Nullable String payloadJson,
            @Nullable String explicitCarrierJson,
            SqlClient tx) {
        JsonObject target = new JsonObject().put("name", signalName);
        if (forkStepId != null) {
            target.put("forkStepId", forkStepId);
        }
        if (branchId != null) {
            target.put("branchId", branchId);
        }
        return nextQueueSequence(instance, tx)
                .compose(seq -> tx.preparedQuery(insertSignal)
                        .execute(Tuple.of(
                                instance.value(),
                                seq,
                                jsonValue(payloadJson),
                                dedupKey,
                                target,
                                jsonValue(explicitCarrierJson))))
                .map(rows -> rows.iterator().next().getLong("id"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands recordSignal")));
    }

    @Override
    public Future<List<ParkedSignal>> parkedSignalsAt(
            WorkflowInstanceId instance,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            SqlClient tx) {
        Tuple parameters = Tuple.tuple();
        parameters.addValue(instance.value());
        parameters.addValue(signalName);
        parameters.addValue(forkStepId);
        parameters.addValue(branchId);
        return tx.preparedQuery(parkedAt)
                .execute(parameters)
                .map(PgWorkflowCommandRepository::parkedSignals)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands parkedSignalsAt")));
    }

    @Override
    public Future<List<ParkedSignal>> parkedSignals(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(parkedAll)
                .execute(Tuple.of(instance.value()))
                .map(PgWorkflowCommandRepository::parkedSignals)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands parkedSignals")));
    }

    private static List<ParkedSignal> parkedSignals(RowSet<Row> rows) {
        List<ParkedSignal> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            result.add(new ParkedSignal(
                    row.getLong("id"),
                    row.getLong("seq"),
                    row.getString("signal_name"),
                    row.getString("fork_step_id"),
                    row.getString("branch_id"),
                    row.getOffsetDateTime("recorded_at").toInstant()));
        }
        return result;
    }

    @Override
    public Future<Optional<SegmentCommand>> readParked(WorkflowInstanceId instance, long commandId, SqlClient tx) {
        return tx.preparedQuery(readParkedRow)
                .execute(Tuple.of(instance.value(), commandId))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.<SegmentCommand>empty();
                    }
                    Row row = rows.iterator().next();
                    return Optional.of(new SegmentCommand(
                            commandId,
                            instance,
                            "SIGNAL",
                            row.getString("payload_json"),
                            row.getString("target_json"),
                            row.getString("dedup_key"),
                            row.getString("explicit_carrier_json")));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands readParked")));
    }

    @Override
    public Future<Integer> settleParked(long commandId, String state, @Nullable String lastError, SqlClient tx) {
        Tuple parameters = Tuple.tuple();
        parameters.addValue(commandId);
        parameters.addValue(state);
        parameters.addValue(lastError);
        return tx.preparedQuery(settleParkedRow)
                .execute(parameters)
                .map(rows -> rows.iterator().next().getInteger("n"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands settleParked")));
    }

    @Override
    public Future<Boolean> wasParked(WorkflowInstanceId instance, long commandId, SqlClient tx) {
        return tx.preparedQuery(parkedBefore)
                .execute(Tuple.of(instance.value(), commandId))
                .map(rows -> rows.iterator().next().getBoolean("parked"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_history wasParked")));
    }

    @Override
    public Future<Integer> requeueParked(long commandId, SqlClient tx) {
        return tx.preparedQuery(requeueParkedRow)
                .execute(Tuple.of(commandId))
                .map(RowSet::rowCount)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands requeueParked")));
    }

    @Override
    public Future<Set<Long>> deadlineBlocked(
            WorkflowInstanceId instance,
            String waitStepId,
            @Nullable String branchId,
            Collection<Long> signals,
            SqlClient tx) {
        if (signals.isEmpty()) {
            return Future.succeededFuture(Set.of());
        }
        Tuple parameters = Tuple.tuple();
        parameters.addValue(instance.value());
        parameters.addValue(signals.toArray(new Long[0]));
        parameters.addValue(waitStepId);
        parameters.addValue(branchId);
        return tx.preparedQuery(blockedByDeadline)
                .execute(parameters)
                .map(rows -> {
                    Set<Long> blocked = new HashSet<>();
                    for (Row row : rows) {
                        blocked.add(row.getLong("id"));
                    }
                    return Set.copyOf(blocked);
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands deadlineBlocked")));
    }

    /**
     * Wraps an update of one command row so that, when the row is a {@code SIGNAL}, the same statement frees one slot
     * of its instance's open-signal count. The wrapped statement answers the number of command rows it updated as
     * {@code n}.
     */
    private static String withLowering(String queues, String update) {
        return "WITH settled AS (" + update + " RETURNING workflow_id, kind), lowered AS (UPDATE " + queues
                + " SET open_signals = open_signals - 1 WHERE open_signals > 0 AND workflow_id IN (SELECT workflow_id"
                + " FROM settled WHERE kind = 'SIGNAL') RETURNING workflow_id) SELECT count(*)::int AS n FROM settled";
    }

    @Override
    public Future<Boolean> release(ClaimedCommand claim) {
        return pool.preparedQuery(releaseCommand)
                .execute(Tuple.of(claim.commandId(), claim.token()))
                .map(rows -> rows.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands release")));
    }

    @Override
    public Future<Integer> retryTransient(ClaimedCommand claim, IntToLongFunction backoffMs, String lastError) {
        return pool.withTransaction(conn -> conn.preparedQuery(lockForTransientRetry)
                        .execute(Tuple.of(claim.commandId(), claim.token()))
                        .compose(rows -> {
                            if (rows.size() == 0) {
                                return Future.succeededFuture(0);
                            }
                            int count = rows.iterator().next().getInteger("transient_count") + 1;
                            return conn.preparedQuery(retryTransientRow)
                                    .execute(Tuple.of(
                                            claim.commandId(), count, (double) backoffMs.applyAsLong(count), lastError))
                                    .map(ignored -> count);
                        }))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands retryTransient")));
    }

    @Override
    public Future<DispositionResult> failCommand(
            ClaimedCommand claim, String reasonCode, String lastError, InstanceFailureListener<SqlClient> failures) {
        return pool.withTransaction(conn -> executionLock
                        .tryAcquire(claim.workflowId(), conn)
                        .compose(locked -> {
                            if (!locked) {
                                return Future.succeededFuture(DispositionResult.BUSY);
                            }
                            return conn.preparedQuery(lockForFailure)
                                    .execute(Tuple.of(claim.commandId(), claim.token()))
                                    .compose(rows -> {
                                        if (rows.size() == 0) {
                                            return Future.succeededFuture(DispositionResult.CLAIM_LOST);
                                        }
                                        return conn.preparedQuery(failCommandRow)
                                                .execute(Tuple.of(claim.commandId(), lastError))
                                                .compose(ignored -> failInstanceOf(
                                                        conn,
                                                        claim.workflowId(),
                                                        claim.commandId(),
                                                        reasonCode,
                                                        lastError,
                                                        failures))
                                                .map(ignored -> DispositionResult.APPLIED);
                                    });
                        }))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands failCommand")));
    }

    @Override
    public Future<List<PendingCommand>> findStuck(@Nullable WorkflowInstanceId instance, long stuckAfterSeconds) {
        Tuple tuple = scope(instance);
        tuple.addValue(stuckAfterSeconds);
        return pool.preparedQuery(queries.findStuck)
                .execute(tuple)
                .map(PgWorkflowCommandRepository::pendingCommands)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands findStuck")));
    }

    @Override
    public Future<QueueCounts> countQueue(long stuckAfterSeconds) {
        return pool.preparedQuery(queries.countQueue)
                .execute(Tuple.of(stuckAfterSeconds))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new QueueCounts(
                            row.getLong("available"),
                            row.getLong("stuck"),
                            row.getLong("parked"),
                            row.getLong("dead_lettered"));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands countQueue")));
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

    @Override
    public Future<Optional<Long>> findEarliestAvailabilityDelayMs() {
        return pool.preparedQuery(wakeupQueries.findEarliestAvailabilityDelayMs)
                .execute()
                .map(rows -> Optional.ofNullable(rows.iterator().next().getLong("delay_ms")))
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_commands findEarliestAvailabilityDelayMs")));
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
