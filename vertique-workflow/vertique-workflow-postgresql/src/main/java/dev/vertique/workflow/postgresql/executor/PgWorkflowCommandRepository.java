// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.Deferral;
import dev.vertique.workflow.engine.executor.EndedInstanceOutcomes;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DispositionResult;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.QueueCounts;
import dev.vertique.workflow.engine.spi.RecordedTimer;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.SettledCommand;
import dev.vertique.workflow.engine.spi.TimerCommandSpec;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.postgresql.timer.WorkflowTimerCarriers;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.types.EchoedNames;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final String insertCompletion;
    private final String insertTimer;
    private final String selectRearmable;
    private final String insertRearmed;
    private final String relinkTimer;
    private final String failTimerRow;
    private final String cancelTimerRowOfCommand;
    private final String selectLiveTasks;
    private final String selectUnsettledTargets;
    private final String insertRecovery;
    private final String deadLettersExist;
    private final String readStartRow;
    private final String supersedeStartRow;
    private final String reofferRows;
    private final String cancelAllRows;
    private final String lockEndedLeftovers;
    private final String settleLeftovers;
    private final PgTimerStore timers;
    private final String cancelReservedTask;
    private final String reopenReservedTask;
    private final String declaredNames;
    private final String parkedAt;
    private final String parkedAll;
    private final String readParkedRow;
    private final String blockedByDeadline;
    private final String parkedBefore;
    private final @Nullable DurableContextPropagator propagator;

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
        this(pool, exceptionMapper, schemas, recording, null);
    }

    /**
     * Creates a repository that also stores, with every command it records, the durable context its propagator
     * captures at that moment (without an identity snapshot).
     *
     * @param pool            the connection pool the reads borrow from
     * @param exceptionMapper the mapper used to translate SQL errors into typed exceptions
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     * @param recording       the recording configuration, whose lock timeout bounds {@link #lockInstance}
     * @param propagator      the propagator that captures the recording caller's durable context, or {@code null} to
     *                        store no context
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment identifier
     */
    public PgWorkflowCommandRepository(
            Pool pool,
            PgDbExceptionMapper exceptionMapper,
            DbSchemas schemas,
            WorkflowRecordingConfig recording,
            @Nullable DurableContextPropagator propagator) {
        this.propagator = propagator;
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
                + " (workflow_id, seq, kind, state, recorded_at, available_at, payload_json, dedup_key, target_json,"
                + " context_json) SELECT $1, $2, 'START', 'PENDING', recorded.at, recorded.at, $3::jsonb, $4,"
                + " $5::jsonb, $6::jsonb FROM recorded RETURNING id";
        this.peekHeads = "SELECT c.id, c.workflow_id, c.kind, i.definition_scope_key, i.definition_id,"
                + " i.definition_version, i.plan_hash, i.status IN " + ENDED + " AS instance_ended,"
                + " CASE WHEN c.kind = 'MIGRATION' THEN (c.target_json->>'version')::bigint END AS migration_version,"
                + " CASE WHEN c.kind = 'MIGRATION' THEN c.target_json->>'scope' END AS migration_scope FROM " + commands
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
                + " version = version + 1, updated_at = now(), completed_at = now() WHERE id = $1 AND status NOT IN "
                + ENDED_OR_FAILED
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
                        + " c.explicit_carrier_json::text AS explicit_carrier_json, c.context_json::text AS context_json,"
                        + " c.timer_kind, c.timer_id, c.task_id FROM "
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
                + " explicit_carrier_json, context_json) SELECT $1, $2, 'SIGNAL', 'PENDING', recorded.at, recorded.at,"
                + " $3::jsonb, $4, $5::jsonb, $6::jsonb, $7::jsonb FROM recorded RETURNING id";
        this.insertCompletion = "WITH recorded AS (SELECT clock_timestamp() AS at) INSERT INTO " + commands
                + " (workflow_id, seq, kind, state, recorded_at, available_at, task_id, payload_json, dedup_key,"
                + " target_json, context_json) SELECT $1, $2, 'COMPLETION', 'PENDING', recorded.at, recorded.at, $3,"
                + " $4::jsonb, $5, $6::jsonb, $7::jsonb FROM recorded RETURNING id";
        // The fire time is read once, with the recording time, from the database clock: a delay counts from it and an
        // instant already past is taken as the recording time, so a timer never fires before it was recorded.
        this.insertTimer = "WITH recorded AS (SELECT clock_timestamp() AS at), fire AS (SELECT recorded.at AS at,"
                + " CASE WHEN $6::timestamptz IS NOT NULL THEN GREATEST($6::timestamptz, recorded.at)"
                + " ELSE recorded.at + ($7::float8 * interval '1 millisecond') END AS fire_at FROM recorded)"
                + " INSERT INTO " + commands
                + " (workflow_id, seq, kind, timer_kind, timer_id, task_id, target_json, state, recorded_at,"
                + " available_at, fire_at) SELECT $1, $2, 'TIMER', $3, $4, $5, $8::jsonb, 'PENDING', fire.at,"
                + " fire.fire_at, fire.fire_at FROM fire RETURNING id, recorded_at, fire_at";
        // A timer whose last command cannot fire it any more: a failed one (the row is FAILED) or one a failed instance
        // settled stale (the row is still SCHEDULED). The replacement keeps the original fire time.
        String timerTable = PgTableNames.qualify(schemas, "workflow", "workflow_timers");
        this.selectRearmable =
                "SELECT t.timer_id, l.fire_at, t.task_id, l.timer_kind, l.target_json::text AS target_json"
                        + " FROM " + timerTable
                        + " t JOIN LATERAL (SELECT c.timer_kind, c.target_json, c.state, c.fire_at FROM "
                        + commands + " c WHERE c.workflow_id = t.workflow_id AND c.timer_id = t.timer_id"
                        + " ORDER BY c.id DESC LIMIT 1) l ON TRUE WHERE t.workflow_id = $1"
                        + " AND ((t.status = 'FAILED' AND l.state = 'FAILED') OR (t.status = 'SCHEDULED' AND l.state = 'STALE'))"
                        + " ORDER BY l.fire_at, t.timer_id FOR UPDATE OF t";
        this.insertRearmed = "INSERT INTO " + commands
                + " (workflow_id, seq, kind, timer_kind, timer_id, task_id, target_json, state, recorded_at,"
                + " available_at, fire_at) VALUES ($1, $2, 'TIMER', $3, $4, $5, $6::jsonb, 'PENDING',"
                + " clock_timestamp(), LEAST($7::timestamptz, clock_timestamp()), $7::timestamptz) RETURNING id";
        this.relinkTimer = "UPDATE " + timerTable + " SET status = 'SCHEDULED', command_id = $2, failed_at = NULL,"
                + " failure_reason = NULL WHERE timer_id = $1";
        this.failTimerRow = "UPDATE " + timerTable + " SET status = 'FAILED', failed_at = now(), failure_reason = $2"
                + " WHERE status = 'SCHEDULED' AND timer_id = (SELECT timer_id FROM " + commands + " WHERE id = $1)";
        this.cancelTimerRowOfCommand = "UPDATE " + timerTable + " SET status = 'CANCELLED', cancelled_at = now()"
                + " WHERE status = 'SCHEDULED' AND timer_id = (SELECT timer_id FROM " + commands + " WHERE id = $1)";
        this.selectLiveTasks = "SELECT task_id FROM " + PgTableNames.qualify(schemas, "workflow", "workflow_tasks")
                + " WHERE workflow_id = $1 AND branch_token_id IS NULL AND status IN ('OPEN','COMPLETING')"
                + " ORDER BY task_id";
        this.selectUnsettledTargets = "SELECT COALESCE(target_json::text, '') AS target FROM " + commands
                + " WHERE workflow_id = $1 AND kind = $2 AND state IN ('PENDING','DEFERRED','CLAIMED') ORDER BY seq";
        this.insertRecovery = "WITH recorded AS (SELECT clock_timestamp() AS at) INSERT INTO " + commands
                + " (workflow_id, seq, kind, state, recorded_at, available_at, target_json, context_json)"
                + " SELECT $1, $2, $3, 'PENDING', recorded.at, recorded.at, $4::jsonb, $5::jsonb FROM recorded"
                + " RETURNING id";
        this.deadLettersExist =
                "SELECT EXISTS (SELECT 1 FROM " + commands + " WHERE workflow_id = $1 AND state = 'DEAD') AS dead";
        this.readStartRow =
                "SELECT c.id, c.payload_json::text AS payload_json, c.target_json::text AS target_json, c.dedup_key,"
                        + " c.context_json::text AS context_json FROM " + commands
                        + " c WHERE c.workflow_id = $1 AND c.kind = 'START' ORDER BY c.seq LIMIT 1";
        this.supersedeStartRow = "UPDATE " + commands + " SET state = 'SUPERSEDED', claim_token = NULL,"
                + " lease_until = NULL, settled_at = now(), last_error = $2 WHERE workflow_id = $1"
                + " AND kind = 'START' AND state = 'DEAD' RETURNING id";
        // A dead-lettered signal was counted when it was recorded and released when it was dead-lettered, so offering
        // it
        // again counts it again, without the limit: the slot is the one it held.
        this.reofferRows = "WITH reoffered AS (UPDATE " + commands
                + " SET state = 'PENDING', attempts = 0, claim_token = NULL, lease_until = NULL,"
                + " available_at = LEAST(available_at, clock_timestamp()) WHERE workflow_id = $1 AND state = 'DEAD'"
                + " RETURNING kind), raised AS (UPDATE " + queues
                + " SET open_signals = open_signals + (SELECT count(*) FROM reoffered WHERE kind = 'SIGNAL')"
                + " WHERE workflow_id = $1 AND EXISTS (SELECT 1 FROM reoffered WHERE kind = 'SIGNAL')"
                + " RETURNING workflow_id) SELECT count(*)::int AS n FROM reoffered";
        // R6: one statement locks every unsettled command of the instance in ascending id order and cancels it; a
        // signal
        // that was open frees its slot of the open-signal count in the same statement, a dead-lettered one frees none.
        this.cancelAllRows = "WITH targets AS (SELECT c.id, c.state FROM " + commands
                + " c WHERE c.workflow_id = $1 AND c.state IN ('PENDING','DEFERRED','CLAIMED','PARKED','DEAD')"
                + " ORDER BY c.id FOR UPDATE OF c), settled AS (UPDATE " + commands
                + " c SET state = 'CANCELLED', claim_token = NULL, lease_until = NULL, settled_at = now(),"
                + " last_error = CASE c.kind WHEN 'START' THEN $2::text WHEN 'SIGNAL' THEN $3::text"
                + " WHEN 'COMPLETION' THEN $4::text WHEN 'RETRY' THEN $5::text WHEN 'MIGRATION' THEN $6::text"
                + " ELSE $7::text END FROM targets t WHERE c.id = t.id RETURNING c.id, c.kind, c.task_id, c.timer_id,"
                + " c.last_error, c.target_json->>'name' AS signal_name, t.state AS prior_state), lowered AS (UPDATE "
                + queues
                + " SET open_signals = GREATEST(open_signals - (SELECT count(*) FROM settled WHERE kind = 'SIGNAL'"
                + " AND prior_state <> 'DEAD')::int, 0) WHERE workflow_id = $1 AND EXISTS (SELECT 1 FROM settled"
                + " WHERE kind = 'SIGNAL' AND prior_state <> 'DEAD') RETURNING workflow_id)"
                + " SELECT id, kind, task_id, timer_id, last_error, signal_name FROM settled ORDER BY id";
        this.lockEndedLeftovers =
                "SELECT c.id, c.kind, c.state, c.target_json->>'name' AS signal_name, i.status AS instance_status"
                        + " FROM " + commands + " c JOIN " + instances
                        + " i ON i.id = c.workflow_id WHERE c.workflow_id = $1"
                        + " AND c.state IN ('PARKED','DEAD') AND i.status IN " + ENDED
                        + " ORDER BY c.id FOR UPDATE OF c";
        this.settleLeftovers = "WITH settled AS (UPDATE " + commands
                + " c SET state = r.state, claim_token = NULL, lease_until = NULL, settled_at = now(),"
                + " last_error = r.reason FROM unnest($2::bigint[], $3::text[], $4::text[], $5::text[])"
                + " AS r(id, state, reason, prior) WHERE c.id = r.id AND c.state IN ('PARKED','DEAD')"
                + " RETURNING c.id, c.kind, r.prior), lowered AS (UPDATE " + queues
                + " SET open_signals = GREATEST(open_signals - (SELECT count(*) FROM settled WHERE kind = 'SIGNAL'"
                + " AND prior = 'PARKED')::int, 0) WHERE workflow_id = $1 AND EXISTS (SELECT 1 FROM settled"
                + " WHERE kind = 'SIGNAL' AND prior = 'PARKED') RETURNING workflow_id)"
                + " SELECT count(*)::int AS n FROM settled";
        this.timers = new PgTimerStore(exceptionMapper, schemas);
        String tasks = PgTableNames.qualify(schemas, "workflow", "workflow_tasks");
        this.cancelReservedTask = "UPDATE " + tasks + " SET status = 'CANCELLED', cancelled_at = now(),"
                + " cancelled_by = '{\"kind\":\"SYSTEM\",\"value\":\"INSTANCE_ENDED\"}'::jsonb,"
                + " cancellation_reason = 'INSTANCE_ENDED', updated_at = now()"
                + " WHERE reservation_command_id = $1 AND status = 'COMPLETING'";
        this.reopenReservedTask = "UPDATE " + tasks + " SET status = 'OPEN', reservation_command_id = NULL,"
                + " updated_at = now() WHERE reservation_command_id = $1 AND status = 'COMPLETING'";
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
                        + " c.explicit_carrier_json::text AS explicit_carrier_json, c.context_json::text AS context_json FROM "
                        + commands + " c WHERE c.workflow_id = $1 AND c.id = $2 AND c.state = 'PARKED'";
        this.parkedBefore = "SELECT EXISTS (SELECT 1 FROM " + history
                + " WHERE workflow_id = $1 AND entry_type IN ('SIGNAL_PARKED','COMMAND_DEAD_LETTERED')"
                + " AND (payload_json->>'commandId')::bigint = $2)"
                + " AS parked";
        this.blockedByDeadline =
                "SELECT s.id FROM " + commands + " s WHERE s.workflow_id = $1 AND s.id = ANY($2::bigint[])"
                        + " AND EXISTS (SELECT 1 FROM " + commands
                        + " t WHERE t.workflow_id = s.workflow_id AND t.kind = 'TIMER'"
                        + " AND t.timer_kind = 'TIMEOUT' AND t.target_json->>'stepId' = $3"
                        + " AND t.target_json->>'branchId' IS NOT DISTINCT FROM $4 AND t.fire_at <= s.recorded_at"
                        + " AND t.state IN ('PENDING','DEFERRED','CLAIMED','DEAD')"
                        // a timeout that has fired has left its wait, even while its own segment is still open: a wait
                        // the instance enters again is a new one
                        + " AND EXISTS (SELECT 1 FROM " + PgTableNames.qualify(schemas, "workflow", "workflow_timers")
                        + " tm WHERE tm.timer_id = t.timer_id AND tm.status = 'SCHEDULED'))";
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
                                instance.value(),
                                seq,
                                jsonValue(payloadJson),
                                idempotencyKey,
                                jsonValue(targetJson),
                                capturedContext(instance, seq))))
                .map(rows -> rows.iterator().next().getLong("id"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands recordStart")));
    }

    /**
     * The durable context the recording caller has bound, encoded for the carrier of the command row at {@code seq}
     * and without an identity snapshot, as the carrier wrapper; {@code null} when the capture is empty.
     */
    private @Nullable Object capturedContext(WorkflowInstanceId instance, long seq) {
        if (propagator == null) {
            return null;
        }
        DurableMetadata captured = propagator
                .mergeCaptured(
                        DurableMetadata.empty(), DispatchBoundary.WORKFLOW, CommandContextCarrier.of(instance, seq))
                .without(CommandContextCarrier.IDENTITY_SNAPSHOT_NAMESPACE);
        return captured.isEmpty() ? null : captured.toCarrier();
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
                                row.getBoolean("instance_ended"),
                                row.getLong("migration_version"),
                                row.getString("migration_scope")));
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
        Optional<EndedInstanceOutcomes.Outcome> met = EndedInstanceOutcomes.isEnded(instanceStatus)
                ? EndedInstanceOutcomes.of(
                        kind,
                        instanceStatus,
                        EndedInstanceOutcomes.Target.signal(
                                looked.getString("signal_name"),
                                Boolean.TRUE.equals(looked.getBoolean("branch_signal")),
                                false))
                : Optional.empty();
        if (met.isPresent()) {
            EndedInstanceOutcomes.Outcome outcome = met.get();
            String state = outcome.settlement().name();
            Future<?> reservation = outcome.reservation() == EndedInstanceOutcomes.Reservation.CANCEL
                    ? conn.preparedQuery(cancelReservedTask).execute(Tuple.of(id))
                    : Future.succeededFuture();
            return reservation
                    .compose(ignored -> conn.preparedQuery(settleEnded).execute(Tuple.of(id, outcome.reason(), state)))
                    .compose(ignored -> "TIMER".equals(kind)
                            ? conn.preparedQuery(cancelTimerRowOfCommand).execute(Tuple.of(id))
                            : Future.succeededFuture())
                    .compose(ignored ->
                            appendCommandHistory(conn, head, entryType(outcome.settlement()), outcome.reason(), state))
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

    /** The history entry type that records a command settled with the given settlement. */
    private static String entryType(EndedInstanceOutcomes.Settlement settlement) {
        return switch (settlement) {
            case STALE -> "COMMAND_STALE";
            case CANCELLED -> "COMMAND_CANCELLED";
            default -> "COMMAND_REJECTED";
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
                            row.getString("explicit_carrier_json"),
                            row.getString("context_json"),
                            row.getString("timer_kind"),
                            row.getUUID("timer_id"),
                            row.getUUID("task_id")));
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
                                jsonValue(explicitCarrierJson),
                                capturedContext(instance, seq))))
                .map(rows -> rows.iterator().next().getLong("id"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands recordSignal")));
    }

    @Override
    public Future<Long> recordRetry(WorkflowInstanceId instance, SqlClient tx) {
        return recordRecovery(instance, "RETRY", null, tx, "recordRetry");
    }

    @Override
    public Future<Long> recordMigration(
            WorkflowInstanceId instance, long targetVersion, @Nullable String targetScope, SqlClient tx) {
        JsonObject target = new JsonObject().put("version", targetVersion);
        if (targetScope != null) {
            target.put("scope", targetScope);
        }
        return recordRecovery(instance, "MIGRATION", target, tx, "recordMigration");
    }

    private Future<Long> recordRecovery(
            WorkflowInstanceId instance, String kind, @Nullable JsonObject target, SqlClient tx, String operation) {
        return nextQueueSequence(instance, tx)
                .compose(seq -> tx.preparedQuery(insertRecovery)
                        .execute(Tuple.of(instance.value(), seq, kind, target, capturedContext(instance, seq))))
                .map(rows -> rows.iterator().next().getLong("id"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands " + operation)));
    }

    @Override
    public Future<Boolean> hasDeadLetters(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(deadLettersExist)
                .execute(Tuple.of(instance.value()))
                .map(rows -> rows.iterator().next().getBoolean("dead"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands hasDeadLetters")));
    }

    @Override
    public Future<Optional<SegmentCommand>> readStart(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(readStartRow)
                .execute(Tuple.of(instance.value()))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.<SegmentCommand>empty();
                    }
                    Row row = rows.iterator().next();
                    return Optional.of(new SegmentCommand(
                            row.getLong("id"),
                            instance,
                            "START",
                            row.getString("payload_json"),
                            row.getString("target_json"),
                            row.getString("dedup_key"),
                            null,
                            row.getString("context_json")));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands readStart")));
    }

    @Override
    public Future<Integer> supersedeDeadStart(WorkflowInstanceId instance, SqlClient tx) {
        String reason = CommandErrors.superseded("START");
        return tx.preparedQuery(supersedeStartRow)
                .execute(Tuple.of(instance.value(), reason))
                .compose(rows -> {
                    Future<Void> written = Future.succeededFuture();
                    for (Row row : rows) {
                        long id = row.getLong("id");
                        JsonObject payload = new JsonObject()
                                .put("commandId", id)
                                .put("kind", "START")
                                .put("reason", reason);
                        written = written.compose(
                                done -> appendHistory(tx, instance, id, "COMMAND_SUPERSEDED", payload, "SUPERSEDED")
                                        .mapEmpty());
                    }
                    int count = rows.size();
                    return written.map(done -> count);
                })
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands supersedeDeadStart")));
    }

    @Override
    public Future<Integer> reofferDeadLetters(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(reofferRows)
                .execute(Tuple.of(instance.value()))
                .map(rows -> rows.iterator().next().getInteger("n"))
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands reofferDeadLetters")));
    }

    @Override
    public Future<List<SettledCommand>> cancelAll(WorkflowInstanceId instance, SqlClient tx) {
        Tuple parameters = Tuple.tuple();
        parameters.addValue(instance.value());
        for (String kind : List.of("START", "SIGNAL", "COMPLETION", "RETRY", "MIGRATION", "TIMER")) {
            parameters.addValue(CommandErrors.instanceCancelled(kind));
        }
        return tx.preparedQuery(cancelAllRows)
                .execute(parameters)
                .compose(rows -> {
                    List<SettledCommand> cancelled = new ArrayList<>(rows.size());
                    Future<Void> written = Future.succeededFuture();
                    for (Row row : rows) {
                        long id = row.getLong("id");
                        String kind = row.getString("kind");
                        cancelled.add(new SettledCommand(id, kind, row.getUUID("task_id"), row.getUUID("timer_id")));
                        written = written.compose(done -> appendSettledHistory(
                                tx,
                                instance,
                                id,
                                kind,
                                row.getString("signal_name"),
                                row.getString("last_error"),
                                "COMMAND_CANCELLED",
                                "CANCELLED"));
                    }
                    return written.map(done -> List.copyOf(cancelled));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands cancelAll")));
    }

    @Override
    public Future<Integer> cancel(long commandId, UUID token, String lastError, SqlClient tx) {
        return settle(settleClaimed, "CANCELLED", commandId, token, lastError, tx, "cancel");
    }

    @Override
    public Future<Integer> settleEnded(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(lockEndedLeftovers)
                .execute(Tuple.of(instance.value()))
                .compose(rows -> {
                    int count = rows.size();
                    if (count == 0) {
                        return Future.succeededFuture(0);
                    }
                    Long[] ids = new Long[count];
                    String[] states = new String[count];
                    String[] reasons = new String[count];
                    String[] priors = new String[count];
                    List<Row> locked = new ArrayList<>(count);
                    int index = 0;
                    for (Row row : rows) {
                        EndedInstanceOutcomes.Outcome outcome = EndedInstanceOutcomes.atEnd(
                                row.getString("kind"), row.getString("instance_status"), row.getString("signal_name"));
                        ids[index] = row.getLong("id");
                        states[index] = outcome.settlement().name();
                        reasons[index] = outcome.reason();
                        priors[index] = row.getString("state");
                        locked.add(row);
                        index++;
                    }
                    Tuple parameters = Tuple.tuple();
                    parameters.addValue(instance.value());
                    parameters.addValue(ids);
                    parameters.addValue(states);
                    parameters.addValue(reasons);
                    parameters.addValue(priors);
                    Future<Void> written = tx.preparedQuery(settleLeftovers)
                            .execute(parameters)
                            .mapEmpty();
                    for (int i = 0; i < count; i++) {
                        Row row = locked.get(i);
                        long id = ids[i];
                        String kind = row.getString("kind");
                        EndedInstanceOutcomes.Settlement settlement =
                                EndedInstanceOutcomes.Settlement.valueOf(states[i]);
                        String reason = reasons[i];
                        written = written.compose(done -> "COMPLETION".equals(kind)
                                        ? tx.preparedQuery(cancelReservedTask).execute(Tuple.of(id))
                                        : Future.succeededFuture())
                                .compose(done -> "TIMER".equals(kind)
                                        ? tx.preparedQuery(cancelTimerRowOfCommand)
                                                .execute(Tuple.of(id))
                                        : Future.succeededFuture())
                                .compose(done -> appendSettledHistory(
                                        tx,
                                        instance,
                                        id,
                                        kind,
                                        row.getString("signal_name"),
                                        reason,
                                        entryType(settlement),
                                        settlement.name()));
                    }
                    return written.map(done -> count);
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands settleEnded")));
    }

    /** Writes the history entry of a command a bulk write settled; a signal's entry also names the signal, bounded. */
    private Future<Void> appendSettledHistory(
            SqlClient tx,
            WorkflowInstanceId instance,
            long commandId,
            String kind,
            @Nullable String signalName,
            String reason,
            String entryType,
            String outcome) {
        JsonObject payload =
                new JsonObject().put("commandId", commandId).put("kind", kind).put("reason", reason);
        if ("SIGNAL".equals(kind)) {
            payload.put("signalName", EchoedNames.bounded(signalName));
        }
        return appendHistory(tx, instance, commandId, entryType, payload, outcome)
                .mapEmpty();
    }

    @Override
    public Future<RecordedTimer> recordTimer(TimerCommandSpec timer, SqlClient tx) {
        UUID timerId = UUID.randomUUID();
        String timerKind = timerKind(timer.purpose());
        JsonObject target = timeoutTarget(timer);
        Instant at = timer.fireTime() instanceof TimerCommandSpec.FireTime.At fixed ? fixed.at() : null;
        double delayMs = timer.fireTime() instanceof TimerCommandSpec.FireTime.After after
                ? (double) after.delay().toMillis()
                : 0d;
        return nextQueueSequence(timer.workflowId(), tx)
                .compose(seq -> {
                    Tuple parameters = Tuple.tuple();
                    parameters.addValue(timer.workflowId().value());
                    parameters.addValue(seq);
                    parameters.addValue(timerKind);
                    parameters.addValue(timerId);
                    parameters.addValue(timer.taskId());
                    parameters.addValue(at == null ? null : at.atOffset(ZoneOffset.UTC));
                    parameters.addValue(delayMs);
                    parameters.addValue(target);
                    return tx.preparedQuery(insertTimer).execute(parameters);
                })
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    long commandId = row.getLong("id");
                    OffsetDateTime recordedAt = row.getOffsetDateTime("recorded_at");
                    OffsetDateTime fireAt = row.getOffsetDateTime("fire_at");
                    TimerRecord record = new TimerRecord(
                            timerId,
                            timer.workflowId(),
                            timer.stepId(),
                            fireAt.toInstant(),
                            TimerStatus.SCHEDULED,
                            recordedAt.toInstant(),
                            null,
                            null,
                            null,
                            null,
                            timer.purpose(),
                            timer.taskId(),
                            timer.branchTokenId(),
                            timer.forkStepId(),
                            timer.branchId(),
                            timerMetadata(timer, timerId),
                            commandId);
                    return timers.insertScheduled(record, tx)
                            .map(done -> new RecordedTimer(timerId, commandId, fireAt.toInstant()));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands recordTimer")));
    }

    @Override
    public Future<List<UUID>> liveTasks(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(selectLiveTasks)
                .execute(Tuple.of(instance.value()))
                .map(rows -> {
                    List<UUID> tasks = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        tasks.add(row.getUUID("task_id"));
                    }
                    return List.copyOf(tasks);
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks liveTasks")));
    }

    @Override
    public Future<List<String>> unsettledTargets(WorkflowInstanceId instance, String kind, SqlClient tx) {
        return tx.preparedQuery(selectUnsettledTargets)
                .execute(Tuple.of(instance.value(), kind))
                .map(rows -> {
                    List<String> targets = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        targets.add(row.getString("target"));
                    }
                    return List.copyOf(targets);
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands unsettledTargets")));
    }

    @Override
    public Future<Integer> rearmTimers(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(selectRearmable)
                .execute(Tuple.of(instance.value()))
                .compose(rows -> {
                    List<Row> due = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        due.add(row);
                    }
                    Future<Integer> chain = Future.succeededFuture(0);
                    for (Row row : due) {
                        chain = chain.compose(count -> rearm(instance, row, tx).map(done -> count + 1));
                    }
                    return chain;
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands rearmTimers")));
    }

    private Future<Void> rearm(WorkflowInstanceId instance, Row timer, SqlClient tx) {
        UUID timerId = timer.getUUID("timer_id");
        return nextQueueSequence(instance, tx)
                .compose(seq -> {
                    Tuple parameters = Tuple.tuple();
                    parameters.addValue(instance.value());
                    parameters.addValue(seq);
                    parameters.addValue(timer.getString("timer_kind"));
                    parameters.addValue(timerId);
                    parameters.addValue(timer.getUUID("task_id"));
                    parameters.addValue(jsonValue(timer.getString("target_json")));
                    parameters.addValue(timer.getOffsetDateTime("fire_at"));
                    return tx.preparedQuery(insertRearmed).execute(parameters);
                })
                .compose(inserted -> tx.preparedQuery(relinkTimer)
                        .execute(Tuple.of(timerId, inserted.iterator().next().getLong("id"))))
                .mapEmpty();
    }

    /** The wait a timeout belongs to, as the timeout command stores it; {@code null} for the other timers. */
    private static @Nullable JsonObject timeoutTarget(TimerCommandSpec timer) {
        if (timer.purpose() != TimerPurpose.SIGNAL_TIMEOUT) {
            return null;
        }
        JsonObject target = new JsonObject().put("stepId", timer.stepId());
        if (timer.forkStepId() != null) {
            target.put("forkStepId", timer.forkStepId());
        }
        if (timer.branchId() != null) {
            target.put("branchId", timer.branchId());
        }
        return target.put("signalNames", new JsonArray(timer.signalNames()));
    }

    private static String timerKind(TimerPurpose purpose) {
        return switch (purpose) {
            case STANDALONE -> "FIRE";
            case SIGNAL_TIMEOUT -> "TIMEOUT";
            case TASK_DUE -> "DUE";
            case TASK_REMINDER -> "REMINDER";
        };
    }

    /**
     * The durable context the recording drive has bound, encoded for the timer row's own carrier and without an
     * identity snapshot; empty when the capture is empty or no propagator is configured.
     */
    private DurableMetadata timerMetadata(TimerCommandSpec timer, UUID timerId) {
        if (propagator == null) {
            return DurableMetadata.empty();
        }
        return propagator
                .mergeCaptured(
                        DurableMetadata.empty(),
                        DispatchBoundary.DELAYED_JOB,
                        WorkflowTimerCarriers.of(timerId, timer.workflowId()))
                .without(CommandContextCarrier.IDENTITY_SNAPSHOT_NAMESPACE);
    }

    @Override
    public Future<Long> recordCompletion(
            WorkflowInstanceId instance,
            UUID taskId,
            String dedupKey,
            @Nullable String payloadJson,
            String targetJson,
            SqlClient tx) {
        return nextQueueSequence(instance, tx)
                .compose(seq -> tx.preparedQuery(insertCompletion)
                        .execute(Tuple.of(
                                instance.value(),
                                seq,
                                taskId,
                                jsonValue(payloadJson),
                                dedupKey,
                                jsonValue(targetJson),
                                capturedContext(instance, seq))))
                .map(rows -> rows.iterator().next().getLong("id"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands recordCompletion")));
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
                            row.getString("explicit_carrier_json"),
                            row.getString("context_json")));
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
                                        Future<?> reopened = "COMPLETION".equals(claim.kind())
                                                ? conn.preparedQuery(reopenReservedTask)
                                                        .execute(Tuple.of(claim.commandId()))
                                                : Future.succeededFuture();
                                        if (rejectsInsteadOfFailing(claim.kind(), reasonCode)) {
                                            return reopened.compose(ignored -> conn.preparedQuery(rejectCommand)
                                                            .execute(Tuple.of(
                                                                    claim.commandId(),
                                                                    claim.token(),
                                                                    "REJECTED",
                                                                    lastError)))
                                                    .compose(ignored -> appendHistory(
                                                            conn,
                                                            claim.workflowId(),
                                                            claim.commandId(),
                                                            "COMMAND_REJECTED",
                                                            new JsonObject()
                                                                    .put("commandId", claim.commandId())
                                                                    .put("kind", claim.kind())
                                                                    .put("reason", lastError),
                                                            "REJECTED"))
                                                    .map(ignored -> DispositionResult.REJECTED);
                                        }
                                        return reopened.compose(ignored -> conn.preparedQuery(failCommandRow)
                                                        .execute(Tuple.of(claim.commandId(), lastError)))
                                                .compose(ignored -> "TIMER".equals(claim.kind())
                                                        ? conn.preparedQuery(failTimerRow)
                                                                .execute(Tuple.of(claim.commandId(), lastError))
                                                        : Future.succeededFuture())
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

    /**
     * Whether a command that failed deterministically is rejected without failing its instance: a signal, a completion
     * or a migration, whose work runs under a savepoint, fails outside it only when its recorded target cannot be read.
     * An overrun of the step cap fails the instance whatever the kind.
     */
    private static boolean rejectsInsteadOfFailing(String kind, String reasonCode) {
        return CommandErrors.Reason.DETERMINISTIC_FAILURE.name().equals(reasonCode)
                && ("SIGNAL".equals(kind) || "COMPLETION".equals(kind) || "MIGRATION".equals(kind));
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
