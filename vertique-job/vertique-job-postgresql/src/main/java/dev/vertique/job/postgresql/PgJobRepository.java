// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static dev.vertique.job.postgresql.JobExecutionMapper.COL_ID;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgLockMode;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.LogEntry;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL-backed implementation of {@link JobRepository}.
 *
 * <p>All SQL operations use the Vert.x reactive PostgreSQL client via the {@link PgSqlRepository}
 * query builder. JSONB columns ({@code payload}, {@code progress}) are serialized as Vert.x
 * {@link JsonObject} instances, which the pg-client driver handles natively.
 *
 * <p>The {@link #claimNextJob(String, int)} method runs inside a single transaction: it first
 * selects eligible rows with {@code FOR UPDATE SKIP LOCKED}, then immediately updates their state
 * to {@code PROCESSING} and records this node's identifier in {@code locked_by}. This guarantees
 * that no two callers claim the same execution and enables stale-execution attribution.
 *
 * <p>Bind this class through {@link JobPostgresqlModule} so that {@link JobRepository} resolves to
 * this implementation in the Dagger component.
 */
@Slf4j
@Singleton
public class PgJobRepository extends PgSqlRepository implements JobRepository {

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the job instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    private final String sqlInsert;
    private final String sqlSelectAllCols;
    private final String sqlClaimSelect;
    private final String sqlClaimUpdate;
    private final String sqlFindById;
    private final String sqlUpdateState;
    private final String sqlComplete;
    private final String sqlScheduleRetry;
    private final String sqlHeartbeat;
    private final String sqlFindStale;
    private final String sqlInsertLog;
    private final String sqlTryInsert;
    private final String sqlSaveSchedule;
    private final String sqlUpdateScheduleFireTimes;
    private final String sqlFindSchedule;
    private final String sqlServerHeartbeat;
    private final String sqlFindDeadServers;
    private final String sqlRemoveServer;
    private final String sqlFindByLockedBy;
    private final String sqlPurgeTerminal;

    // --- Node identity ---

    /**
     * Stable identifier for this repository instance, used as {@code locked_by} during job claim.
     * Constructed from the hostname and a random suffix to differentiate multiple JVM instances
     * running on the same host.
     */
    private final String nodeId;

    // --- Constructor ---

    /**
     * Creates a new PostgreSQL job repository whose tables are not schema-qualified, so they resolve
     * through the connection's default schema.
     *
     * @param pool          the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     */
    @Inject
    public PgJobRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        this(pool, exceptionMapper, DbSchemas.empty());
    }

    /**
     * Creates a new PostgreSQL job repository that qualifies every table reference with the schema
     * mapped to the {@code job} instance, when {@code schemas} holds one. Without a
     * mapping the statements are identical to those of the two-argument constructor.
     *
     * <p>The mapping is validated here, before any query runs.
     *
     * @param pool          the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     * @param schemas       the schema placement shared with the migrations; never {@code null}
     * @throws IllegalArgumentException if the schema mapped for the job instance is not a valid
     *                                  single-segment PostgreSQL schema name
     */
    public PgJobRepository(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        super(pool, exceptionMapper);
        this.nodeId = resolveNodeId();

        String executions = PgTableNames.qualify(schemas, JobMigrationsModule.INSTANCE_NAME, "job_executions");
        String logs = PgTableNames.qualify(schemas, JobMigrationsModule.INSTANCE_NAME, "job_logs");
        String jobSchedules = PgTableNames.qualify(schemas, JobMigrationsModule.INSTANCE_NAME, "job_schedules");
        String heartbeats = PgTableNames.qualify(schemas, JobMigrationsModule.INSTANCE_NAME, "job_server_heartbeats");

        this.sqlInsert = """
                INSERT INTO %s (
                    id, job_id, job_type, handler, queue, state, payload, progress, parameters,
                    metadata, attempt, max_attempts, priority, scheduled_at, locked_by,
                    last_error, error_type, created_at, updated_at
                ) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9::jsonb, $10::jsonb,
                          $11, $12, $13, $14, $15, $16, $17, NOW(), NOW())
                RETURNING id
                """.formatted(executions);

        this.sqlSelectAllCols = """
                SELECT id, job_id, job_type, handler, queue, state, payload, progress, parameters,
                       metadata, attempt, max_attempts, priority, scheduled_at, started_at, completed_at,
                       locked_by, last_error, error_type, created_at, updated_at
                FROM %s
                """.formatted(executions);

        this.sqlClaimSelect = sqlSelectAllCols
                + "WHERE queue = $1 AND state = 'ENQUEUED' AND scheduled_at <= NOW() "
                + "ORDER BY priority DESC, scheduled_at ASC "
                + "LIMIT $2";

        this.sqlClaimUpdate = "UPDATE " + executions
                + " SET state = 'PROCESSING', started_at = NOW(), updated_at = NOW(), locked_by = $1 "
                + "WHERE id = ANY($2)";

        this.sqlFindById = sqlSelectAllCols + "WHERE id = $1";

        this.sqlUpdateState =
                "UPDATE " + executions + " SET state = $2, updated_at = NOW() WHERE id = $1 AND state = $3";

        // The WHERE clause validates source/target pairs against the JobState transition model so an
        // invalid transition (e.g. ABANDONED->ABANDONED) matches zero rows and returns empty rather than
        // emitting a spurious duplicate transition. PROCESSING accepts any terminal/abandoned target;
        // ABANDONED accepts only the late-completion / exhaustion targets (SUCCEEDED/FAILED/DEAD_LETTER) —
        // re-enqueue from ABANDONED goes through sqlScheduleRetry, not here.
        this.sqlComplete = "UPDATE " + executions + " SET state = $2, completed_at = NOW(), updated_at = NOW(), "
                + "last_error = $3, error_type = $4, progress = $5::jsonb "
                + "WHERE id = $1 AND ("
                + "(state = 'PROCESSING' AND $2 IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'ABANDONED', 'DEAD_LETTER')) "
                + "OR (state = 'ABANDONED' AND $2 IN ('SUCCEEDED', 'FAILED', 'DEAD_LETTER'))"
                + ") RETURNING *";

        this.sqlScheduleRetry = "UPDATE " + executions + " SET state = 'ENQUEUED', scheduled_at = $2, attempt = $3, "
                + "started_at = NULL, completed_at = NULL, locked_by = NULL, updated_at = NOW() "
                + "WHERE id = $1 AND state IN ('FAILED', 'ABANDONED')";

        this.sqlHeartbeat = "UPDATE " + executions + " SET updated_at = NOW(), progress = $2::jsonb WHERE id = $1";

        this.sqlFindStale =
                sqlSelectAllCols + "WHERE state = 'PROCESSING' AND updated_at < NOW() - ($1 * interval '1 second')";

        this.sqlInsertLog =
                "INSERT INTO " + logs + " (execution_id, level, message, logged_at) VALUES ($1, $2, $3, $4)";

        this.sqlTryInsert = """
                INSERT INTO %s (
                    id, job_id, job_type, handler, queue, state, payload, progress, parameters,
                    metadata, attempt, max_attempts, priority, scheduled_at, locked_by,
                    last_error, error_type, created_at, updated_at
                ) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9::jsonb, $10::jsonb,
                          $11, $12, $13, $14, $15, $16, $17, NOW(), NOW())
                ON CONFLICT (job_id, scheduled_at) WHERE job_type = 'CRON' AND state NOT IN ('DEAD_LETTER', 'CANCELLED')
                DO NOTHING
                RETURNING id
                """.formatted(executions);

        this.sqlSaveSchedule = """
                INSERT INTO %s (job_id, cron_expression, handler, target, execution_mode, timezone,
                    enabled, overlap_policy, max_attempts, tracked, created_at, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW(), NOW())
                ON CONFLICT (job_id) DO UPDATE SET
                    cron_expression = $2, handler = $3, target = $4, execution_mode = $5, timezone = $6,
                    enabled = $7, overlap_policy = $8, max_attempts = $9, tracked = $10, updated_at = NOW()
                """.formatted(jobSchedules);

        this.sqlUpdateScheduleFireTimes = "UPDATE " + jobSchedules
                + " SET last_fired_at = GREATEST(COALESCE(last_fired_at, $2), $2), next_fire_at = $3,"
                + " updated_at = NOW() WHERE job_id = $1";

        this.sqlFindSchedule = """
                SELECT job_id, cron_expression, handler, target, execution_mode, timezone, enabled,
                       overlap_policy, max_attempts, tracked, last_fired_at, next_fire_at
                FROM %s WHERE job_id = $1
                """.formatted(jobSchedules);

        this.sqlServerHeartbeat = "INSERT INTO " + heartbeats + " (server_id, last_heartbeat, started_at) "
                + "VALUES ($1, NOW(), NOW()) "
                + "ON CONFLICT (server_id) DO UPDATE SET last_heartbeat = NOW()";

        this.sqlFindDeadServers =
                "SELECT server_id FROM " + heartbeats + " WHERE last_heartbeat < NOW() - ($1 * interval '1 second')";

        this.sqlRemoveServer = "DELETE FROM " + heartbeats + " WHERE server_id = $1";

        this.sqlFindByLockedBy = sqlSelectAllCols + "WHERE locked_by = $1 AND state = 'PROCESSING'";

        // One bounded batch of finished executions older than $1 seconds, oldest first, by the age the partial
        // index idx_job_executions_retention is built on. SUCCEEDED, CANCELLED and DEAD_LETTER have no outgoing
        // transition. FAILED and ABANDONED can be re-enqueued, except on a CRON execution: the cron dispatcher
        // writes both through completeExecution and re-fires on the next tick as a new row, so nothing moves
        // them again. ENQUEUED and PROCESSING are live and never match. A CRON row is kept while a misfire
        // recovery could still fire its tick: recovery fires only ticks after the schedule's last_fired_at, and
        // idx_job_executions_cron_dedup is what stops such a tick from running twice, so a row whose tick is not
        // after last_fired_at is the only kind whose deletion cannot re-run a fire. job_logs rows go with their
        // execution through ON DELETE CASCADE.
        this.sqlPurgeTerminal = "WITH batch AS (SELECT e.id FROM " + executions + " e"
                + " WHERE (e.state IN ('SUCCEEDED', 'CANCELLED', 'DEAD_LETTER')"
                + "        OR (e.job_type = 'CRON' AND e.state IN ('FAILED', 'ABANDONED')))"
                + "   AND COALESCE(e.completed_at, e.updated_at) < NOW() - ($1 * interval '1 second')"
                + "   AND (e.job_type <> 'CRON' OR NOT EXISTS (SELECT 1 FROM " + jobSchedules + " s"
                + "        WHERE s.job_id = e.job_id"
                + "          AND (s.last_fired_at IS NULL OR s.last_fired_at < e.scheduled_at)))"
                + " ORDER BY COALESCE(e.completed_at, e.updated_at)"
                + " LIMIT $2"
                + " FOR UPDATE OF e SKIP LOCKED)"
                + " DELETE FROM " + executions + " USING batch WHERE " + executions + ".id = batch.id";
    }

    /**
     * Generates a node identifier from the hostname and a short random suffix.
     * Falls back to {@code "unknown"} if hostname resolution fails.
     *
     * @return a stable node identifier for this JVM instance
     */
    private static String resolveNodeId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        // Append a random suffix to distinguish multiple instances on the same host
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return hostname + "-" + suffix;
    }

    // --- JobRepository ---

    /**
     * {@inheritDoc}
     *
     * <p>The returned {@link UUID} is the database-assigned execution ID from the {@code RETURNING
     * id} clause.
     */
    @Override
    public Future<UUID> save(JobExecution execution) {
        return withInsertParams(execution, params -> this.<UUID>query(sqlInsert)
                .params(params)
                .mapping(row -> row.getUUID(COL_ID))
                .returning());
    }

    /**
     * Persists a new job execution record using the provided {@link SqlClient}, enabling
     * participation in an existing transaction.
     *
     * @param execution the execution to save
     * @param client    the SQL client (connection or transaction) to execute against
     * @return a future of the saved execution ID
     */
    public Future<UUID> save(JobExecution execution, SqlClient client) {
        return withInsertParams(execution, params -> this.<UUID>query(sqlInsert)
                .on(client)
                .params(params)
                .mapping(row -> row.getUUID(COL_ID))
                .returning());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The claim runs in a single transaction: eligible rows are selected with
     * {@code FOR UPDATE SKIP LOCKED}, then immediately transitioned to {@code PROCESSING}. The
     * {@code locked_by} column is set to this node's {@link #nodeId} (hostname + random suffix)
     * so that stale-job detection can identify which node owns each in-flight execution.
     */
    @Override
    public Future<List<JobExecution>> claimNextJob(String queue, int batchSize) {
        return transaction().execute(conn -> {
            // Step 1: SELECT eligible rows with SKIP LOCKED via framework query builder
            return this.<JobExecution>query(sqlClaimSelect)
                    .on(conn)
                    .params(Tuple.of(queue, batchSize))
                    .mapping(JobExecutionMapper::fromRow)
                    .queryClause(PgLockMode.FOR_UPDATE_SKIP_LOCKED)
                    .list()
                    .compose(claimed -> {
                        if (claimed.isEmpty()) {
                            return Future.succeededFuture(claimed);
                        }
                        // Step 2: UPDATE claimed rows to PROCESSING, recording the node owner
                        UUID[] ids = claimed.stream().map(JobExecution::id).toArray(UUID[]::new);
                        Tuple claimTuple = Tuple.tuple();
                        claimTuple.addValue(nodeId);
                        claimTuple.addArrayOfUUID(ids);
                        return this.<Void>query(sqlClaimUpdate)
                                .on(conn)
                                .params(claimTuple)
                                .execute()
                                .map(v -> claimed.stream()
                                        .map(e ->
                                                e.withState(JobState.PROCESSING).withLockedBy(nodeId))
                                        .toList());
                    });
        });
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> heartbeat(UUID executionId, ProgressSnapshot progress) {
        return this.<Void>query(sqlHeartbeat)
                .params(Tuple.of(executionId, toJsonObject(progress)))
                .execute()
                .mapEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<JobExecution>> findStale(Duration heartbeatTimeout) {
        return this.<JobExecution>query(sqlFindStale)
                .params(Tuple.of(heartbeatTimeout.toSeconds()))
                .mapping(JobExecutionMapper::fromRow)
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public Future<Optional<JobExecution>> findById(UUID executionId) {
        return this.<JobExecution>query(sqlFindById)
                .params(Tuple.of(executionId))
                .mapping(JobExecutionMapper::fromRow)
                .one();
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> updateState(UUID executionId, JobState fromState, JobState toState) {
        return this.<Void>query(sqlUpdateState)
                .params(Tuple.of(executionId, toState.name(), fromState.name()))
                .execute()
                .compose(count -> {
                    if (count == 0) {
                        return Future.failedFuture(new IllegalStateException("State transition failed: execution "
                                + executionId + " was not in state " + fromState));
                    }
                    return Future.succeededFuture();
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code RETURNING *} clause allows detection of whether any row was actually
     * transitioned: an empty result set means the execution was already in a terminal state
     * (idempotent no-op), while a non-empty result returns the persisted {@link JobExecution}.
     */
    @Override
    public Future<Optional<JobExecution>> completeExecution(
            UUID executionId, JobState newState, String errorMessage, String errorType, ProgressSnapshot progress) {
        return this.<JobExecution>query(sqlComplete)
                .params(Tuple.of(executionId, newState.name(), errorMessage, errorType, toJsonObject(progress)))
                .mapping(JobExecutionMapper::fromRow)
                .returningOptional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fails with {@link IllegalStateException} when no row is updated, which happens when the
     * execution is not in a retryable state ({@code FAILED} or {@code ABANDONED}). This surfaces a
     * programming error early rather than silently no-oping.
     */
    @Override
    public Future<Void> scheduleRetry(UUID executionId, Instant nextScheduledAt, int newAttempt) {
        return this.<Void>query(sqlScheduleRetry)
                .params(Tuple.of(executionId, toOffsetDateTime(nextScheduledAt), newAttempt))
                .execute()
                .compose(count -> {
                    if (count == 0) {
                        return Future.failedFuture(new IllegalStateException("Retry scheduling failed: execution "
                                + executionId + " was not in a retryable state (FAILED or ABANDONED)"));
                    }
                    return Future.succeededFuture();
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #markAndScheduleRetry} with {@link JobState#FAILED} as the recorded mark.
     */
    @Override
    public Future<Optional<JobExecution>> failAndScheduleRetry(
            UUID executionId,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt) {
        return markAndScheduleRetry(
                executionId, JobState.FAILED, errorMessage, errorType, progress, nextScheduledAt, nextAttempt);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #markAndScheduleRetry} with {@link JobState#ABANDONED} as the recorded mark.
     */
    @Override
    public Future<Optional<JobExecution>> abandonAndScheduleRetry(
            UUID executionId,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt) {
        return markAndScheduleRetry(
                executionId, JobState.ABANDONED, errorMessage, errorType, progress, nextScheduledAt, nextAttempt);
    }

    /**
     * Atomic "mark then re-enqueue" shared by {@link #failAndScheduleRetry} and
     * {@link #abandonAndScheduleRetry}. Runs the two writes in a single transaction so the row is never
     * left stranded in the intermediate {@code markState}: step 1 records {@code markState} via the
     * {@code sqlComplete} guard ({@code RETURNING *} the snapshot for audit) — valid only from a
     * {@code PROCESSING} row (an already-{@code ABANDONED} row passed to {@code abandonAndScheduleRetry}
     * is rejected by the state-pair guard and yields empty); step 2 re-enqueues the same row
     * ({@code markState} → {@code ENQUEUED}). If step 1 transitioned no row the result is empty; if step 2
     * unexpectedly updates no row the transaction rolls back so neither write persists.
     *
     * @param markState the durable attempt-outcome mark to record ({@code FAILED} or {@code ABANDONED})
     */
    private Future<Optional<JobExecution>> markAndScheduleRetry(
            UUID executionId,
            JobState markState,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt) {
        return transaction().execute(conn -> this.<JobExecution>query(sqlComplete)
                .on(conn)
                .params(Tuple.of(executionId, markState.name(), errorMessage, errorType, toJsonObject(progress)))
                .mapping(JobExecutionMapper::fromRow)
                .returningOptional()
                .compose(markSnapshot -> {
                    if (markSnapshot.isEmpty()) {
                        return Future.succeededFuture(Optional.<JobExecution>empty());
                    }
                    return this.<Void>query(sqlScheduleRetry)
                            .on(conn)
                            .params(Tuple.of(executionId, toOffsetDateTime(nextScheduledAt), nextAttempt))
                            .execute()
                            .compose(count -> {
                                if (count == 0) {
                                    return Future.failedFuture(new IllegalStateException(
                                            "markAndScheduleRetry: re-enqueue updated no rows for execution "
                                                    + executionId + " (transaction rolled back)"));
                                }
                                // Return the markState snapshot from step 1 — the committed row is ENQUEUED.
                                return Future.succeededFuture(markSnapshot);
                            });
                }));
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> saveLogs(UUID executionId, List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return Future.succeededFuture();
        }
        List<Tuple> batch = entries.stream()
                .map(e -> Tuple.of(executionId, e.level(), e.message(), toOffsetDateTime(e.loggedAt())))
                .toList();
        return this.<Void>query(sqlInsertLog).batch(batch).execute().mapEmpty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@code ON CONFLICT (job_id, scheduled_at) WHERE ... DO NOTHING RETURNING id} to
     * implement a PostgreSQL-native leader election: the first node to INSERT wins, subsequent
     * nodes get an empty RETURNING result and receive {@link java.util.Optional#empty()}.
     */
    @Override
    public Future<Optional<UUID>> tryInsert(JobExecution execution) {
        return withInsertParams(execution, params -> this.<UUID>query(sqlTryInsert)
                .params(params)
                .mapping(row -> row.getUUID(JobExecutionMapper.COL_ID))
                .returningOptional());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses an UPSERT so that every application restart refreshes the schedule definition from
     * code (annotation + config). The database entry is never the source of truth for schedule
     * configuration — only for dashboard visibility.
     */
    @Override
    public Future<Void> saveSchedule(CronJobSchedule schedule) {
        return this.<Void>query(sqlSaveSchedule)
                .params(Tuple.of(
                        schedule.jobId(),
                        schedule.cronExpression(),
                        schedule.handler(),
                        schedule.target(),
                        schedule.executionMode(),
                        schedule.timezone(),
                        schedule.enabled(),
                        schedule.overlapPolicy(),
                        schedule.maxAttempts(),
                        schedule.tracked()))
                .execute()
                .mapEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> updateScheduleFireTimes(String jobId, Instant lastFiredAt, Instant nextFireAt) {
        return this.<Void>query(sqlUpdateScheduleFireTimes)
                .params(Tuple.of(jobId, toOffsetDateTime(lastFiredAt), toOffsetDateTime(nextFireAt)))
                .execute()
                .mapEmpty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the {@code job_schedules} row for the given job ID and maps it to a
     * {@link CronJobSchedule} record. Returns {@link Optional#empty()} if no row is found —
     * this is expected for new jobs that have not yet been persisted via {@link #saveSchedule}.
     */
    @Override
    public Future<Optional<CronJobSchedule>> findSchedule(String jobId) {
        return this.<CronJobSchedule>query(sqlFindSchedule)
                .params(Tuple.of(jobId))
                .mapping(row -> {
                    OffsetDateTime lastFiredOdt = row.getOffsetDateTime("last_fired_at");
                    OffsetDateTime nextFireOdt = row.getOffsetDateTime("next_fire_at");
                    String handler = row.getString("handler");
                    String target = row.getString("target");
                    // For legacy rows written before the target column was added, derive
                    // the canonical eventbus: form from the handler address.
                    String resolvedTarget = (target != null) ? target : ("eventbus:" + handler);
                    return new CronJobSchedule(
                            row.getString("job_id"),
                            row.getString("cron_expression"),
                            handler,
                            resolvedTarget,
                            row.getString("execution_mode"),
                            row.getString("timezone"),
                            row.getBoolean("enabled"),
                            row.getString("overlap_policy"),
                            row.getInteger("max_attempts"),
                            row.getBoolean("tracked"),
                            lastFiredOdt != null ? lastFiredOdt.toInstant() : null,
                            nextFireOdt != null ? nextFireOdt.toInstant() : null);
                })
                .one();
    }

    // --- Node heartbeat ---

    /** {@inheritDoc} */
    @Override
    public Future<Void> serverHeartbeat(String serverId) {
        return this.<Void>query(sqlServerHeartbeat)
                .params(Tuple.of(serverId))
                .execute()
                .mapEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<String>> findDeadServers(Duration heartbeatTimeout) {
        return this.<String>query(sqlFindDeadServers)
                .params(Tuple.of(heartbeatTimeout.toSeconds()))
                .mapping(row -> row.getString("server_id"))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> removeServer(String serverId) {
        return this.<Void>query(sqlRemoveServer)
                .params(Tuple.of(serverId))
                .execute()
                .mapEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<JobExecution>> findByLockedBy(String serverId) {
        return this.<JobExecution>query(sqlFindByLockedBy)
                .params(Tuple.of(serverId))
                .mapping(JobExecutionMapper::fromRow)
                .list();
    }

    // --- Retention ---

    /** The batch delete statement, for the plan test. */
    String purgeStatement() {
        return sqlPurgeTerminal;
    }

    /**
     * Deletes one bounded batch of finished executions, together with their logs.
     *
     * <p>Only {@link JobState#SUCCEEDED}, {@link JobState#CANCELLED} and {@link JobState#DEAD_LETTER}
     * executions qualify, plus {@link JobState#FAILED} and {@link JobState#ABANDONED} executions of
     * type {@code CRON}, which are never re-enqueued. A delayed job in any other state can still run
     * or retry and is never deleted. An execution qualifies only once it has been finished for at
     * least {@code retention}: the age is {@code completed_at}, or {@code updated_at} for a state
     * change that did not set it. A {@code CRON} execution is also kept while its fire time is after
     * the schedule's {@code last_fired_at}, because that is the only range a misfire recovery can
     * fire again, and the row is what keeps such a fire from running twice. Rows another transaction
     * holds are skipped. The scan follows {@code idx_job_executions_retention}.
     *
     * <p>The batch limits executions, not their logs: the logs of each deleted execution go with it.
     *
     * @param retention how long an execution must have been finished before it is deleted
     * @param batchSize the most executions to delete in this call
     * @return the number of executions deleted; fewer than {@code batchSize} means no more qualified
     *         that no other transaction held when the batch ran
     */
    public Future<Integer> purgeTerminalExecutions(Duration retention, int batchSize) {
        // Not the query builder: it rejects a base statement that contains FOR UPDATE, and this one locks inside
        // the batch subquery.
        return pool.preparedQuery(sqlPurgeTerminal)
                .execute(Tuple.of(retention.toSeconds(), batchSize))
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "job purgeTerminalExecutions")));
    }

    // --- Internal helpers ---

    /**
     * Runs an insert with the parameter tuple of {@code execution}, so an execution that cannot be
     * stored fails the returned future instead of throwing past the caller's recovery.
     *
     * @param <T>       the insert's result type
     * @param execution the execution to build parameters for
     * @param insert    the insert to run with the parameters; its future is returned unchanged
     * @return the insert's future, or a future failed with {@link IllegalArgumentException} if a
     *         JSONB value cannot be serialized
     */
    private <T> Future<T> withInsertParams(JobExecution execution, Function<Tuple, Future<T>> insert) {
        Tuple params;
        try {
            params = buildInsertTuple(execution);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        return insert.apply(params);
    }

    /**
     * Builds the INSERT parameter tuple from a {@link JobExecution}. Serializes {@code payload},
     * {@code progress}, and {@code parameters} to {@link JsonObject} for JSONB columns.
     *
     * @param execution the execution to build parameters for
     * @return the parameter tuple
     */
    private Tuple buildInsertTuple(JobExecution execution) {
        return Tuple.of(
                execution.id(),
                execution.jobId(),
                execution.jobType().name(),
                execution.handler(),
                execution.queue(),
                execution.state().name(),
                payloadToJsonObject(execution.payload()),
                toJsonObject(execution.progress()),
                toJsonObject(execution.parameters()),
                execution.metadata().toCarrier(),
                execution.attemptNumber(),
                execution.maxAttempts(),
                execution.priority(),
                toOffsetDateTime(execution.scheduledAt()),
                execution.lockedBy(),
                execution.errorMessage(),
                execution.errorType());
    }

    /**
     * Converts an {@link Instant} to an {@link OffsetDateTime} at UTC for use in TIMESTAMPTZ
     * parameters. The Vert.x pg-client requires {@link OffsetDateTime} for timestamp-with-timezone
     * columns. Returns {@code null} when {@code instant} is {@code null}.
     *
     * @param instant the instant to convert, or {@code null}
     * @return the UTC {@link OffsetDateTime}, or {@code null}
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    /**
     * Converts a job payload to the {@link JsonObject} the {@code payload} column stores.
     *
     * @param payload the payload to convert, or {@code null}
     * @return the {@link JsonObject} representation, or {@code null}
     * @throws IllegalArgumentException if the payload does not serialize to a JSON object. The
     *                                  message names only the type: Jackson's own message quotes
     *                                  the value, and this one reaches error columns and logs
     */
    private static JsonObject payloadToJsonObject(Object payload) {
        try {
            return toJsonObject(payload);
        } catch (IllegalArgumentException | NullPointerException e) {
            // JsonObject.mapFrom reports a value that serializes to JSON null with a NullPointerException.
            throw new IllegalArgumentException(
                    "Job payload of type " + payload.getClass().getName() + " does not serialize to a JSON object");
        }
    }

    /**
     * Converts an arbitrary object to a {@link JsonObject} suitable for JSONB columns. Returns
     * {@code null} when {@code value} is {@code null}.
     *
     * <p>If the value is already a {@link JsonObject} it is returned as-is. Otherwise
     * {@link JsonObject#mapFrom(Object)} is used.
     *
     * @param value the value to convert, or {@code null}
     * @return the {@link JsonObject} representation, or {@code null}
     */
    private static JsonObject toJsonObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject jo) {
            return jo;
        }
        return JsonObject.mapFrom(value);
    }
}
