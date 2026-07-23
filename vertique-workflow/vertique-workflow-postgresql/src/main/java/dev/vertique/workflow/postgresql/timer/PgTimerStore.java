// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.timer;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStatusTransition;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed implementation of {@link TimerStore} for the {@code workflow_timers} table.
 *
 * <p>All methods operate within the caller-supplied {@link SqlClient} transaction — no pool field
 * is held, and no internal transaction is opened. The caller is responsible for committing or
 * rolling back the transaction after each operation.
 *
 * <p>The {@code mark*} methods use a conditional {@code UPDATE ... WHERE status = 'SCHEDULED'} to
 * implement a compare-and-swap. If the {@code UPDATE} matches zero rows (another actor won the
 * race), a follow-up {@code SELECT status} classifies which terminal status was already applied and
 * returns the appropriate {@link TimerStatusTransition#LOST_TO_FIRED},
 * {@link TimerStatusTransition#LOST_TO_CANCELLED}, or {@link TimerStatusTransition#LOST_TO_FAILED}
 * constant.
 *
 * <p>SQL follows the direct {@code tx.preparedQuery(SQL).execute(Tuple)} pattern used by all other
 * repositories in this module.
 */
@Singleton
public final class PgTimerStore implements TimerStore<SqlClient> {

    // --- SQL constants ---

    private static final String SQL_INSERT = "INSERT INTO workflow_timers"
            + " (timer_id, workflow_id, step_id, status, fire_at, delayed_job_execution_id,"
            + " scheduled_at, purpose, task_id, branch_token_id, fork_step_id, branch_id, metadata)"
            + " VALUES ($1, $2, $3, 'SCHEDULED', $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb)";

    private static final String SQL_SELECT_ALL_COLUMNS =
            "SELECT timer_id, workflow_id, step_id, status, fire_at, delayed_job_execution_id,"
                    + " scheduled_at, fired_at, cancelled_at, failed_at, failure_reason,"
                    + " purpose, task_id, branch_token_id, fork_step_id, branch_id, metadata"
                    + " FROM workflow_timers";

    private static final String SQL_LOCK_FOR_FIRING = SQL_SELECT_ALL_COLUMNS + " WHERE timer_id = $1 FOR UPDATE";

    private static final String SQL_MARK_FIRED = "UPDATE workflow_timers SET status = 'FIRED', fired_at = $2"
            + " WHERE timer_id = $1 AND status = 'SCHEDULED'";

    private static final String SQL_MARK_CANCELLED =
            "UPDATE workflow_timers SET status = 'CANCELLED', cancelled_at = $2"
                    + " WHERE timer_id = $1 AND status = 'SCHEDULED'";

    private static final String SQL_MARK_FAILED =
            "UPDATE workflow_timers SET status = 'FAILED', failed_at = $2, failure_reason = $3"
                    + " WHERE timer_id = $1 AND status = 'SCHEDULED'";

    private static final String SQL_SELECT_STATUS = "SELECT status FROM workflow_timers WHERE timer_id = $1";

    private static final String SQL_FIND_RECOVERABLE_SCHEDULED = SQL_SELECT_ALL_COLUMNS
            + " WHERE status = 'SCHEDULED' AND fire_at < $1"
            + " ORDER BY fire_at ASC"
            + " LIMIT $2";

    private static final String SQL_UPDATE_EXECUTION_ID = "UPDATE workflow_timers SET delayed_job_execution_id = $2"
            + " WHERE timer_id = $1 AND status = 'SCHEDULED'";

    private static final String SQL_FIND_BY_ID = SQL_SELECT_ALL_COLUMNS + " WHERE timer_id = $1";

    private static final String SQL_FIND_SCHEDULED_BY_BRANCH_TOKEN =
            SQL_SELECT_ALL_COLUMNS + " WHERE branch_token_id = $1 AND status = 'SCHEDULED'";

    // --- Fields ---

    private final PgDbExceptionMapper exceptionMapper;

    // --- Constructor ---

    /**
     * Creates a new store with the given exception mapper.
     *
     * @param exceptionMapper the mapper used to translate SQL errors into typed domain exceptions
     */
    @Inject
    public PgTimerStore(PgDbExceptionMapper exceptionMapper) {
        this.exceptionMapper = exceptionMapper;
    }

    // --- TimerStore implementation ---

    /**
     * {@inheritDoc}
     *
     * <p>Inserts a {@code SCHEDULED} row. Explicitly bound columns are
     * {@code timer_id}, {@code workflow_id}, {@code step_id}, {@code fire_at},
     * {@code delayed_job_execution_id}, {@code scheduled_at}, {@code purpose}, {@code task_id},
     * {@code branch_token_id}, {@code fork_step_id}, {@code branch_id}, and {@code metadata};
     * {@code status} is the literal {@code 'SCHEDULED'}.
     */
    @Override
    public Future<Void> insertScheduled(TimerRecord record, SqlClient tx) {
        Tuple params = Tuple.of(
                record.timerId(),
                record.workflowId().value(),
                record.stepId(),
                toOffsetDateTime(record.fireAt()),
                record.delayedJobExecutionId(),
                toOffsetDateTime(record.scheduledAt()),
                record.purpose().name(),
                record.taskId(),
                record.branchTokenId(),
                record.forkStepId(),
                record.branchId(),
                toJsonObject(record.metadata()));
        return tx.preparedQuery(SQL_INSERT)
                .execute(params)
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers insertScheduled")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a plain {@code SELECT} (no {@code FOR UPDATE}). Safe to call in a transaction
     * that already holds the row lock via {@link #lockForFiring}: Postgres returns the current
     * version of the row without blocking.
     */
    @Override
    public Future<Optional<TimerRecord>> findById(UUID timerId, SqlClient tx) {
        return tx.preparedQuery(SQL_FIND_BY_ID)
                .execute(Tuple.of(timerId))
                .<Optional<TimerRecord>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(mapRow(it.next())) : Optional.empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers findById")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes {@code SELECT ... FOR UPDATE}. Returns {@link Optional#empty()} when the timer
     * row does not exist (e.g., it was CASCADE-deleted when the parent workflow instance was
     * removed). The lock is held until the caller commits or rolls back.
     */
    @Override
    public Future<Optional<TimerRecord>> lockForFiring(UUID timerId, SqlClient tx) {
        return tx.preparedQuery(SQL_LOCK_FOR_FIRING)
                .execute(Tuple.of(timerId))
                .<Optional<TimerRecord>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(mapRow(it.next())) : Optional.empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers lockForFiring")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a conditional {@code UPDATE ... WHERE status = 'SCHEDULED'} and, on zero rows
     * updated, executes a follow-up {@code SELECT status} to classify the winning terminal state.
     */
    @Override
    public Future<TimerStatusTransition> markFired(UUID timerId, Instant firedAt, SqlClient tx) {
        Tuple params = Tuple.of(timerId, toOffsetDateTime(firedAt));
        return tx.preparedQuery(SQL_MARK_FIRED)
                .execute(params)
                .compose(rs -> rs.rowCount() == 1
                        ? Future.succeededFuture(TimerStatusTransition.APPLIED)
                        : classifyLostTransition(timerId, tx))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers markFired")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a conditional {@code UPDATE ... WHERE status = 'SCHEDULED'} and, on zero rows
     * updated, executes a follow-up {@code SELECT status} to classify the winning terminal state.
     */
    @Override
    public Future<TimerStatusTransition> markCancelled(UUID timerId, Instant cancelledAt, SqlClient tx) {
        Tuple params = Tuple.of(timerId, toOffsetDateTime(cancelledAt));
        return tx.preparedQuery(SQL_MARK_CANCELLED)
                .execute(params)
                .compose(rs -> rs.rowCount() == 1
                        ? Future.succeededFuture(TimerStatusTransition.APPLIED)
                        : classifyLostTransition(timerId, tx))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers markCancelled")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a conditional {@code UPDATE ... WHERE status = 'SCHEDULED'} and, on zero rows
     * updated, executes a follow-up {@code SELECT status} to classify the winning terminal state.
     */
    @Override
    public Future<TimerStatusTransition> markFailed(
            UUID timerId, Instant failedAt, String failureReason, SqlClient tx) {
        Tuple params = Tuple.of(timerId, toOffsetDateTime(failedAt), failureReason);
        return tx.preparedQuery(SQL_MARK_FAILED)
                .execute(params)
                .compose(rs -> rs.rowCount() == 1
                        ? Future.succeededFuture(TimerStatusTransition.APPLIED)
                        : classifyLostTransition(timerId, tx))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers markFailed")));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<List<TimerRecord>> findRecoverableScheduled(Instant beforeFireAt, int limit, SqlClient tx) {
        Tuple params = Tuple.of(toOffsetDateTime(beforeFireAt), limit);
        return tx.preparedQuery(SQL_FIND_RECOVERABLE_SCHEDULED)
                .execute(params)
                .map(rs -> {
                    List<TimerRecord> records = new ArrayList<>();
                    for (var row : rs) {
                        records.add(mapRow(row));
                    }
                    return records;
                })
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_timers findRecoverableScheduled")));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Void> updateExecutionId(UUID timerId, UUID delayedJobExecutionId, SqlClient tx) {
        Tuple params = Tuple.of(timerId, delayedJobExecutionId);
        return tx.preparedQuery(SQL_UPDATE_EXECUTION_ID)
                .execute(params)
                // Translate SQL errors to typed DB exceptions BEFORE the row-count check, so the
                // domain WorkflowConflictException raised below isn't itself swept into the DB
                // exception mapper's catch-all path.
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_timers updateExecutionId")))
                .<Void>flatMap(rs -> {
                    // The UPDATE is guarded by status = 'SCHEDULED'; rowCount=0 means a concurrent
                    // mark{Cancelled,Fired,Failed} or CASCADE-delete won the race and the timer
                    // row is no longer eligible for re-binding. Failing the future rolls back the
                    // caller's tx — including the just-enqueued delayed_jobs row — so we don't
                    // strand a job whose timer row will never reach SCHEDULED again.
                    if (rs.rowCount() == 0) {
                        return Future.failedFuture(new WorkflowConflictException(
                                "Timer '" + timerId + "' is no longer SCHEDULED; updateExecutionId aborted to"
                                        + " avoid stranding the re-enqueued delayed job"));
                    }
                    return Future.succeededFuture();
                });
    }

    /**
     * Returns the timer ids of all {@link dev.vertique.workflow.timer.TimerStatus#SCHEDULED}
     * reminder timers ({@code purpose = 'TASK_REMINDER'}) that belong to the given task.
     *
     * @param taskId the task id
     * @param tx     the active transaction context
     * @return a {@link Future} resolving to the list of scheduled reminder timer ids
     */
    @Override
    public Future<List<UUID>> findScheduledRemindersForTask(UUID taskId, SqlClient tx) {
        String sql = "SELECT timer_id FROM workflow_timers"
                + " WHERE task_id = $1 AND purpose = 'TASK_REMINDER' AND status = 'SCHEDULED'";
        return tx.preparedQuery(sql)
                .execute(Tuple.of(taskId))
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_timers findScheduledRemindersForTask")))
                .map(rs -> {
                    List<UUID> ids = new ArrayList<>();
                    rs.forEach(row -> ids.add(row.getUUID("timer_id")));
                    return ids;
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns all {@link TimerRecord}s with {@code status = 'SCHEDULED'} and
     * {@code branch_token_id = $1}. Covers all timer purposes owned by the branch
     * (standalone, signal-timeout, task-due, task-reminder).
     */
    @Override
    public Future<List<TimerRecord>> findScheduledByBranchToken(UUID branchTokenId, SqlClient tx) {
        return tx.preparedQuery(SQL_FIND_SCHEDULED_BY_BRANCH_TOKEN)
                .execute(Tuple.of(branchTokenId))
                .map(rs -> {
                    List<TimerRecord> records = new ArrayList<>();
                    for (var row : rs) {
                        records.add(mapRow(row));
                    }
                    return records;
                })
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_timers findScheduledByBranchToken")));
    }

    // --- Internal helpers ---

    /**
     * Maps a result-set row from the {@code workflow_timers} table to a {@link TimerRecord}.
     *
     * <p>The {@code TIMESTAMPTZ} columns are extracted as {@link OffsetDateTime} and converted to
     * UTC {@link Instant}. The nullable columns {@code fired_at}, {@code cancelled_at},
     * {@code failed_at}, and {@code failure_reason} may be {@code null}.
     *
     * @param row the result row to map
     * @return the corresponding {@link TimerRecord}
     */
    private static TimerRecord mapRow(io.vertx.sqlclient.Row row) {
        UUID timerId = row.getUUID("timer_id");
        WorkflowInstanceId workflowId = new WorkflowInstanceId(row.getUUID("workflow_id"));
        String stepId = row.getString("step_id");
        TimerStatus status = TimerStatus.valueOf(row.getString("status"));
        Instant fireAt = toInstant(row.getOffsetDateTime("fire_at"));
        UUID delayedJobExecutionId = row.getUUID("delayed_job_execution_id");
        Instant scheduledAt = toInstant(row.getOffsetDateTime("scheduled_at"));
        Instant firedAt = toInstant(row.getOffsetDateTime("fired_at"));
        Instant cancelledAt = toInstant(row.getOffsetDateTime("cancelled_at"));
        Instant failedAt = toInstant(row.getOffsetDateTime("failed_at"));
        String failureReason = row.getString("failure_reason");
        TimerPurpose purpose = TimerPurpose.valueOf(row.getString("purpose"));
        UUID taskId = row.getUUID("task_id");
        UUID branchTokenId = row.getUUID("branch_token_id");
        String forkStepId = row.getString("fork_step_id");
        String branchId = row.getString("branch_id");
        DurableMetadata metadata = DurableMetadata.fromCarrier(row.getJsonObject("metadata"));
        return new TimerRecord(
                timerId,
                workflowId,
                stepId,
                fireAt,
                status,
                delayedJobExecutionId,
                scheduledAt,
                firedAt,
                cancelledAt,
                failedAt,
                failureReason,
                purpose,
                taskId,
                branchTokenId,
                forkStepId,
                branchId,
                metadata);
    }

    /**
     * Converts a {@link DurableMetadata} document to a {@link JsonObject} carrier for JSONB column
     * binding. Persists as {@code {"context": {...}}} shape. Returns {@code null} when the
     * document is {@code null} or empty (stores SQL {@code NULL} for empty context).
     *
     * @param metadata the durable metadata document, or {@code null}
     * @return the carrier {@link JsonObject}, or {@code null} when empty
     */
    private static JsonObject toJsonObject(DurableMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return metadata.toCarrier();
    }

    /**
     * Reads the current status of a timer row and converts it to the appropriate
     * {@link TimerStatusTransition#LOST_TO_FIRED}, {@link TimerStatusTransition#LOST_TO_CANCELLED},
     * or {@link TimerStatusTransition#LOST_TO_FAILED} constant.
     *
     * <p>Called when a conditional {@code UPDATE} matched zero rows, meaning another actor
     * already transitioned the timer to a terminal status.
     *
     * @param timerId the timer whose current status to read
     * @param tx      the active transaction context
     * @return a {@link Future} resolving to the appropriate {@code LOST_TO_*} transition
     */
    private Future<TimerStatusTransition> classifyLostTransition(UUID timerId, SqlClient tx) {
        return tx.preparedQuery(SQL_SELECT_STATUS).execute(Tuple.of(timerId)).map(rs -> {
            var it = rs.iterator();
            if (!it.hasNext()) {
                // Row no longer exists — treat as LOST_TO_FIRED (already past SCHEDULED).
                return TimerStatusTransition.LOST_TO_FIRED;
            }
            TimerStatus currentStatus = TimerStatus.valueOf(it.next().getString("status"));
            return switch (currentStatus) {
                case FIRED -> TimerStatusTransition.LOST_TO_FIRED;
                case CANCELLED -> TimerStatusTransition.LOST_TO_CANCELLED;
                case FAILED -> TimerStatusTransition.LOST_TO_FAILED;
                case SCHEDULED ->
                    // Should not happen: UPDATE matched 0 rows but SELECT shows SCHEDULED.
                    // Defensive fallback — treat as lost.
                    TimerStatusTransition.LOST_TO_FIRED;
            };
        });
    }

    /**
     * Converts an {@link Instant} to an {@link OffsetDateTime} at UTC offset for use as a
     * {@code TIMESTAMPTZ} parameter.
     *
     * @param instant the instant to convert; must not be {@code null}
     * @return the corresponding UTC {@link OffsetDateTime}
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Converts an {@link OffsetDateTime} (as returned by the Vert.x pg-client for
     * {@code TIMESTAMPTZ} columns) to a UTC {@link Instant}.
     *
     * @param odt the offset date-time, or {@code null}
     * @return the corresponding {@link Instant}, or {@code null} if {@code odt} is {@code null}
     */
    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.withOffsetSameInstant(ZoneOffset.UTC).toInstant() : null;
    }
}
