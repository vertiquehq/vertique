// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.tasks;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.tasks.TaskTransition;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
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
 * PostgreSQL-backed implementation of {@link TaskStore} for the {@code workflow_tasks} table.
 *
 * <p>All methods operate within the caller-supplied {@link SqlClient} transaction — no pool field
 * is used for mutations, and no internal transaction is opened. The pool is retained only for
 * {@link #findByFilter}, which uses the keyset-paged query builder that accepts the pool for
 * read-only access.
 *
 * <p>This implementation is storage-only: it does not perform dedup, does not append history, and
 * does not read or update the {@code workflow_instances} table. The engine
 * ({@link dev.vertique.workflow.postgresql.engine.PgWorkflowEngine}) owns those concerns.
 *
 * <p>The {@code mark*} methods follow the same compare-and-swap pattern as
 * {@link dev.vertique.workflow.postgresql.timer.PgTimerStore}: an {@code UPDATE … WHERE status='OPEN'}
 * is issued; if zero rows are updated a follow-up {@code SELECT status} classifies which terminal
 * state was already present and returns the appropriate {@code LOST_TO_*} constant.
 *
 * <p>SQL follows the direct {@code tx.preparedQuery(SQL).execute(Tuple)} pattern used by all other
 * repositories in this module.
 */
@Singleton
public final class PgTaskStore extends PgSqlRepository implements TaskStore<SqlClient> {

    // --- SQL constants ---

    private static final String SQL_INSERT = "INSERT INTO workflow_tasks"
            + " (task_id, workflow_id, step_id, status, assignee_type, assignee_key,"
            + " decisions_snapshot_json, due_at, due_date_timer_id, subject_version_at_creation,"
            + " created_at, updated_at, branch_token_id, fork_step_id, branch_id)"
            + " VALUES ($1, $2, $3, 'OPEN', $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)";

    private static final String SQL_SELECT_ALL_COLUMNS =
            "SELECT task_id, workflow_id, step_id, status, assignee_type, assignee_key,"
                    + " decisions_snapshot_json, decision_name, decision_payload_json,"
                    + " completed_by, cancelled_by, reassigned_by, reassignment_reason,"
                    + " due_at, due_date_timer_id,"
                    + " completed_at, cancelled_at, expired_at, cancellation_reason,"
                    + " subject_version_at_creation,"
                    + " branch_token_id, fork_step_id, branch_id,"
                    + " created_at, updated_at"
                    + " FROM workflow_tasks";

    private static final String SQL_FIND_BY_ID = SQL_SELECT_ALL_COLUMNS + " WHERE task_id = $1";

    private static final String SQL_LOCK_FOR_COMPLETION = SQL_SELECT_ALL_COLUMNS + " WHERE task_id = $1 FOR UPDATE";

    private static final String SQL_MARK_COMPLETED = "UPDATE workflow_tasks"
            + " SET status = 'COMPLETED', decision_name = $2, decision_payload_json = $3::jsonb,"
            + " completed_at = $4, completed_by = $5, updated_at = $6"
            + " WHERE task_id = $1 AND status = 'OPEN'";

    private static final String SQL_MARK_CANCELLED = "UPDATE workflow_tasks"
            + " SET status = 'CANCELLED', cancellation_reason = $2,"
            + " cancelled_at = $3, cancelled_by = $4, updated_at = $5"
            + " WHERE task_id = $1 AND status = 'OPEN'";

    private static final String SQL_MARK_EXPIRED =
            "UPDATE workflow_tasks SET status = 'EXPIRED', expired_at = $2, updated_at = $3"
                    + " WHERE task_id = $1 AND status = 'OPEN'";

    /**
     * Race-safe atomic increment of {@code reminders_fired_count}, gated on the task still being
     * OPEN. Returns the new (post-increment) count via {@code RETURNING}, or no rows when the task
     * has already terminated. The single UPDATE acquires a row lock per fire, naturally serializing
     * concurrent reminder fires for the same task without an external lock.
     */
    private static final String SQL_INCREMENT_REMINDERS_FIRED_COUNT =
            "UPDATE workflow_tasks SET reminders_fired_count = reminders_fired_count + 1, updated_at = $2"
                    + " WHERE task_id = $1 AND status = 'OPEN'"
                    + " RETURNING reminders_fired_count";

    private static final String SQL_SELECT_STATUS = "SELECT status FROM workflow_tasks WHERE task_id = $1";

    private static final String SQL_FIND_OPEN_BY_BRANCH_TOKEN =
            SQL_SELECT_ALL_COLUMNS + " WHERE branch_token_id = $1 AND status = 'OPEN'";

    /**
     * Single-round-trip reassignment: returns old assignment + new assignment + FK references so
     * the engine can build the {@link TaskReassignmentResult.Applied} record without a separate
     * read.
     *
     * <p>Uses a CTE to capture the pre-update state and exposes it via subquery in {@code RETURNING}.
     */
    private static final String SQL_REASSIGN =
            "WITH old AS (SELECT assignee_type, assignee_key FROM workflow_tasks WHERE task_id = $1)"
                    + " UPDATE workflow_tasks SET"
                    + " assignee_type = $2, assignee_key = $3,"
                    + " reassigned_by = $4,"
                    + " reassignment_reason = $5, updated_at = $6"
                    + " WHERE task_id = $1 AND status = 'OPEN'"
                    + " RETURNING workflow_id, step_id,"
                    + " (SELECT assignee_type FROM old) AS old_kind,"
                    + " (SELECT assignee_key FROM old) AS old_value";

    // --- Constructor ---

    /**
     * Creates a new store with the given pool and exception mapper.
     *
     * <p>JSONB columns ({@code decisions_snapshot_json}, {@code completed_by}, {@code cancelled_by},
     * {@code reassigned_by}) are read and written as Vert.x {@link JsonObject} / {@link JsonArray},
     * which the pg-client driver handles natively. No {@code ObjectMapper} binding is required from
     * the application Dagger graph.
     *
     * @param pool            the PostgreSQL connection pool (used by the paged-query builder for
     *                        {@link #findByFilter})
     * @param exceptionMapper the mapper used to translate SQL errors into typed domain exceptions
     */
    @Inject
    public PgTaskStore(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    // --- TaskStore implementation ---

    /**
     * {@inheritDoc}
     *
     * <p>Inserts with {@code status='OPEN'} (literal in SQL). The decisions list is serialized to
     * a {@link JsonArray} of {@link JsonObject}s and bound directly to the JSONB column.
     */
    @Override
    public Future<Void> insertOpen(TaskRecord record, SqlClient tx) {
        // Build the decisions snapshot as a JsonArray so the pg-client driver binds it to JSONB
        // natively. Field names match what parseDecisions reads back.
        JsonArray decisionsJson = new JsonArray();
        for (TaskDecisionDescriptor d : record.decisions()) {
            decisionsJson.add(new JsonObject()
                    .put("name", d.name())
                    .put("payloadTypeName", d.payloadTypeName())
                    .put("nextStepId", d.nextStepId()));
        }

        TaskAssignment assignment = record.assignment();
        String assignmentKind = assignmentKindString(assignment);
        String assignmentValue = assignmentValueString(assignment);
        Instant now = record.updatedAt() != null ? record.updatedAt() : Instant.now();

        Tuple params = Tuple.of(
                record.taskId(),
                record.workflowId().value(),
                record.stepId(),
                assignmentKind,
                assignmentValue,
                decisionsJson,
                toOffsetDateTime(record.dueAt()),
                record.dueDateTimerId(),
                record.subjectVersionAtCreation(),
                toOffsetDateTime(now),
                toOffsetDateTime(now),
                record.branchTokenId(),
                record.forkStepId(),
                record.branchId());

        return tx.preparedQuery(SQL_INSERT)
                .execute(params)
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks insertOpen")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes {@code SELECT … FOR UPDATE}. Returns {@link Optional#empty()} when the row does
     * not exist or is already in a terminal status (the FOR UPDATE lock is only acquired for OPEN
     * rows since the WHERE clause includes all statuses — any caller should check the returned
     * record's status).
     */
    @Override
    public Future<Optional<TaskRecord>> lockForCompletion(UUID taskId, SqlClient tx) {
        return tx.preparedQuery(SQL_LOCK_FOR_COMPLETION)
                .execute(Tuple.of(taskId))
                .<Optional<TaskRecord>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(mapRow(it.next())) : Optional.empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks lockForCompletion")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a conditional {@code UPDATE … WHERE status='OPEN'} and, on zero rows updated,
     * executes a follow-up {@code SELECT status} to classify the winning terminal state.
     */
    @Override
    public Future<TaskTransition> markCompleted(
            UUID taskId,
            String decisionName,
            String payloadJson,
            Instant completedAt,
            WorkflowActor completedBy,
            SqlClient tx) {
        JsonObject actorJson = actorJson(completedBy);
        Instant now = completedAt != null ? completedAt : Instant.now();
        Tuple params =
                Tuple.of(taskId, decisionName, payloadJson, toOffsetDateTime(now), actorJson, toOffsetDateTime(now));
        return tx.preparedQuery(SQL_MARK_COMPLETED)
                .execute(params)
                .compose(rs -> rs.rowCount() == 1
                        ? Future.succeededFuture(TaskTransition.APPLIED)
                        : classifyLostTransition(taskId, tx))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks markCompleted")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a conditional {@code UPDATE … WHERE status='OPEN'} and, on zero rows updated,
     * executes a follow-up {@code SELECT status} to classify the winning terminal state.
     */
    @Override
    public Future<TaskTransition> markCancelled(
            UUID taskId, String reason, Instant cancelledAt, WorkflowActor cancelledBy, SqlClient tx) {
        JsonObject actorJson = actorJson(cancelledBy);
        Instant now = cancelledAt != null ? cancelledAt : Instant.now();
        Tuple params = Tuple.of(taskId, reason, toOffsetDateTime(now), actorJson, toOffsetDateTime(now));
        return tx.preparedQuery(SQL_MARK_CANCELLED)
                .execute(params)
                .compose(rs -> rs.rowCount() == 1
                        ? Future.succeededFuture(TaskTransition.APPLIED)
                        : classifyLostTransition(taskId, tx))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks markCancelled")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a conditional {@code UPDATE … WHERE status='OPEN'} and, on zero rows updated,
     * executes a follow-up {@code SELECT status} to classify the winning terminal state.
     */
    @Override
    public Future<TaskTransition> markExpired(UUID taskId, Instant expiredAt, SqlClient tx) {
        Instant now = expiredAt != null ? expiredAt : Instant.now();
        Tuple params = Tuple.of(taskId, toOffsetDateTime(now), toOffsetDateTime(now));
        return tx.preparedQuery(SQL_MARK_EXPIRED)
                .execute(params)
                .compose(rs -> rs.rowCount() == 1
                        ? Future.succeededFuture(TaskTransition.APPLIED)
                        : classifyLostTransition(taskId, tx))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks markExpired")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a single conditional {@code UPDATE … WHERE status='OPEN' RETURNING …}. The old
     * assignment is captured via a CTE so the result carries before/after assignment for history.
     * Blank {@code reason} strings are stored as {@code NULL}.
     */
    @Override
    public Future<TaskReassignmentResult> reassign(
            UUID taskId,
            TaskAssignment newAssignment,
            WorkflowActor reassignedBy,
            @Nullable String reason,
            Instant updatedAt,
            SqlClient tx) {
        String newKind = assignmentKindString(newAssignment);
        String newValue = assignmentValueString(newAssignment);
        JsonObject actorJson = actorJson(reassignedBy);
        // Normalize blank reason to null, matching FingerprintCanonicalizer contract.
        String normalizedReason = (reason != null && reason.isBlank()) ? null : reason;
        Instant now = updatedAt != null ? updatedAt : Instant.now();
        Tuple params = Tuple.of(taskId, newKind, newValue, actorJson, normalizedReason, toOffsetDateTime(now));

        return tx.preparedQuery(SQL_REASSIGN)
                .execute(params)
                .compose(rs -> {
                    if (rs.rowCount() == 0) {
                        // Task was not OPEN — classify the terminal state.
                        return classifyLostTransition(taskId, tx).map(transition ->
                                (TaskReassignmentResult) new TaskReassignmentResult.LostToTerminal(transition));
                    }
                    var row = rs.iterator().next();
                    UUID workflowUuid = row.getUUID("workflow_id");
                    String stepId = row.getString("step_id");
                    String oldKind = row.getString("old_kind");
                    String oldValue = row.getString("old_value");
                    TaskAssignment oldAssignment = parseAssignment(oldKind, oldValue);
                    return Future.<TaskReassignmentResult>succeededFuture(new TaskReassignmentResult.Applied(
                            taskId,
                            new WorkflowInstanceId(workflowUuid),
                            stepId,
                            oldAssignment,
                            newAssignment,
                            reassignedBy,
                            normalizedReason,
                            now));
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks reassign")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a non-locking SELECT with an optional JOIN to {@code workflow_instances} for
     * subject filters. The keyset pagination uses {@code (updated_at DESC, task_id ASC)}.
     */
    @Override
    public Future<PagedResult<TaskRecord>> findByFilter(TaskFilter filter, PageCursor cursor, SqlClient tx) {
        // The subject filters (subjectType, subjectId) need a JOIN to workflow_instances.
        // The archived-filter is handled with a NOT EXISTS subquery (not a JOIN) to avoid the
        // "column reference is ambiguous" error that arises when both workflow_tasks.updated_at
        // and workflow_instances.updated_at are visible in a JOIN and the keyset WHERE predicate
        // references "updated_at" without a table qualifier.
        boolean needsInstanceJoin = filter.subjectType() != null || filter.subjectId() != null;

        StringBuilder sql = new StringBuilder(SQL_SELECT_ALL_COLUMNS);
        if (needsInstanceJoin) {
            sql = new StringBuilder("SELECT wt.task_id, wt.workflow_id, wt.step_id, wt.status,"
                    + " wt.assignee_type, wt.assignee_key,"
                    + " wt.decisions_snapshot_json, wt.decision_name, wt.decision_payload_json,"
                    + " wt.completed_by, wt.cancelled_by, wt.reassigned_by, wt.reassignment_reason,"
                    + " wt.due_at, wt.due_date_timer_id,"
                    + " wt.completed_at, wt.cancelled_at, wt.expired_at, wt.cancellation_reason,"
                    + " wt.subject_version_at_creation,"
                    + " wt.branch_token_id, wt.fork_step_id, wt.branch_id,"
                    + " wt.created_at, wt.updated_at"
                    + " FROM workflow_tasks wt"
                    + " JOIN workflow_instances wi ON wt.workflow_id = wi.id");
        }

        List<Object> paramValues = new ArrayList<>();
        boolean hasWhere = false;

        // Prefix for column names (with or without alias)
        String colPrefix = needsInstanceJoin ? "wt." : "";

        if (filter.workflowId() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(filter.workflowId().value());
            sql.append(" ").append(colPrefix).append("workflow_id = $").append(paramValues.size());
            hasWhere = true;
        }
        if (filter.status() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(filter.status().name());
            sql.append(" ").append(colPrefix).append("status = $").append(paramValues.size());
            hasWhere = true;
        }
        if (filter.assigneeUser() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add("USER");
            sql.append(" ").append(colPrefix).append("assignee_type = $").append(paramValues.size());
            paramValues.add(filter.assigneeUser());
            sql.append(" AND ").append(colPrefix).append("assignee_key = $").append(paramValues.size());
            hasWhere = true;
        } else if (filter.assigneeRole() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add("ROLE");
            sql.append(" ").append(colPrefix).append("assignee_type = $").append(paramValues.size());
            paramValues.add(filter.assigneeRole());
            sql.append(" AND ").append(colPrefix).append("assignee_key = $").append(paramValues.size());
            hasWhere = true;
        } else if (filter.assigneeQueue() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add("QUEUE");
            sql.append(" ").append(colPrefix).append("assignee_type = $").append(paramValues.size());
            paramValues.add(filter.assigneeQueue());
            sql.append(" AND ").append(colPrefix).append("assignee_key = $").append(paramValues.size());
            hasWhere = true;
        }
        if (filter.dueBefore() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(toOffsetDateTime(filter.dueBefore()));
            sql.append(" ").append(colPrefix).append("due_at < $").append(paramValues.size());
            hasWhere = true;
        }
        if (filter.subjectType() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(filter.subjectType());
            sql.append(" wi.subject_type = $").append(paramValues.size());
            hasWhere = true;
        }
        if (filter.subjectId() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(filter.subjectId());
            sql.append(" wi.subject_id = $").append(paramValues.size());
            hasWhere = true;
        }
        if (!filter.includeArchived()) {
            // Exclude tasks whose owning workflow instance has been soft-deleted (archived).
            // When a subject-filter JOIN is present, reuse the already-joined wi alias.
            // Otherwise, use a correlated NOT EXISTS subquery so the keyset pagination columns
            // (updated_at, task_id) remain unambiguous — a JOIN would introduce a second
            // updated_at column from workflow_instances, causing a "column reference is
            // ambiguous" error in the keyset WHERE predicate.
            sql.append(hasWhere ? " AND" : " WHERE");
            if (needsInstanceJoin) {
                sql.append(" wi.archived_at IS NULL");
            } else {
                sql.append(" NOT EXISTS (SELECT 1 FROM workflow_instances wi2 WHERE wi2.id = ")
                        .append(colPrefix)
                        .append("workflow_id AND wi2.archived_at IS NOT NULL)");
            }
        }

        String orderCol = "updated_at";
        String uniqueCol = "task_id";

        // Pin execution to the caller's tx so that read-your-own-writes works inside a single
        // withTransaction(...) block. The inherited pagedQuery() helper defaults to the pool;
        // overriding via .on(tx) routes the read through the active SqlConnection.
        return this.<TaskRecord>pagedQuery(sql.toString())
                .on(tx)
                .params(Tuple.from(paramValues))
                .mapping(this::mapRow)
                .orderBy(OrderKey.desc(orderCol), OrderKey.asc(uniqueCol))
                .uniqueKey(uniqueCol)
                .page(cursor);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes a non-locking SELECT. Returns {@link Optional#empty()} when the row does not
     * exist.
     */
    @Override
    public Future<Optional<TaskRecord>> findById(UUID taskId, SqlClient tx) {
        return tx.preparedQuery(SQL_FIND_BY_ID)
                .execute(Tuple.of(taskId))
                .<Optional<TaskRecord>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(mapRow(it.next())) : Optional.empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks findById")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns all {@link TaskRecord}s with {@code status = 'OPEN'} and
     * {@code branch_token_id = $1}. Used by the branch-owned wait cancellation cascade.
     */
    @Override
    public Future<List<TaskRecord>> findOpenByBranchToken(UUID branchTokenId, SqlClient tx) {
        return tx.preparedQuery(SQL_FIND_OPEN_BY_BRANCH_TOKEN)
                .execute(Tuple.of(branchTokenId))
                .<List<TaskRecord>>map(rs -> {
                    List<TaskRecord> records = new ArrayList<>();
                    for (var row : rs) {
                        records.add(mapRow(row));
                    }
                    return records;
                })
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_tasks findOpenByBranchToken")));
    }

    @Override
    public Future<Optional<Integer>> incrementRemindersFiredCount(UUID taskId, SqlClient tx) {
        Tuple params = Tuple.of(taskId, toOffsetDateTime(Instant.now()));
        return tx.preparedQuery(SQL_INCREMENT_REMINDERS_FIRED_COUNT)
                .execute(params)
                .<Optional<Integer>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? Optional.of(it.next().getInteger("reminders_fired_count")) : Optional.empty();
                })
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_tasks incrementRemindersFiredCount")));
    }

    // --- Internal helpers ---

    /**
     * Reads the current status of a task row and converts it to the appropriate
     * {@link TaskTransition#LOST_TO_COMPLETED}, {@link TaskTransition#LOST_TO_CANCELLED}, or
     * {@link TaskTransition#LOST_TO_EXPIRED} constant.
     *
     * <p>Called when a conditional {@code UPDATE} matched zero rows, meaning another actor already
     * transitioned the task to a terminal status.
     *
     * @param taskId the task whose current status to read
     * @param tx     the active transaction context
     * @return a {@link Future} resolving to the appropriate {@code LOST_TO_*} transition
     */
    private Future<TaskTransition> classifyLostTransition(UUID taskId, SqlClient tx) {
        return tx.preparedQuery(SQL_SELECT_STATUS).execute(Tuple.of(taskId)).map(rs -> {
            var it = rs.iterator();
            if (!it.hasNext()) {
                // Row no longer exists — treat as LOST_TO_COMPLETED (already past OPEN).
                return TaskTransition.LOST_TO_COMPLETED;
            }
            TaskStatus currentStatus = TaskStatus.valueOf(it.next().getString("status"));
            return switch (currentStatus) {
                case COMPLETED -> TaskTransition.LOST_TO_COMPLETED;
                case CANCELLED -> TaskTransition.LOST_TO_CANCELLED;
                case EXPIRED -> TaskTransition.LOST_TO_EXPIRED;
                case OPEN ->
                    // Should not happen: UPDATE matched 0 rows but SELECT shows OPEN.
                    // Defensive fallback.
                    TaskTransition.LOST_TO_COMPLETED;
            };
        });
    }

    /**
     * Maps a result-set row from the {@code workflow_tasks} table to a {@link TaskRecord}.
     *
     * <p>The {@code decisions_snapshot_json} and {@code decision_payload_json} JSONB columns are
     * extracted as strings via {@link Row#getValue}. Nullable columns are handled defensively.
     * Actor columns ({@code *_by_kind}, {@code *_by_value}) are parsed back to
     * {@link WorkflowActor} instances.
     *
     * @param row the result row to map
     * @return the corresponding {@link TaskRecord}
     */
    private TaskRecord mapRow(Row row) {
        UUID taskId = row.getUUID("task_id");
        WorkflowInstanceId workflowId = new WorkflowInstanceId(row.getUUID("workflow_id"));
        String stepId = row.getString("step_id");
        TaskStatus status = TaskStatus.valueOf(row.getString("status"));
        String assignmentKind = row.getString("assignee_type");
        String assignmentValue = row.getString("assignee_key");
        TaskAssignment assignment = parseAssignment(assignmentKind, assignmentValue);

        // The pg-client driver maps JSONB columns natively to JsonObject/JsonArray when read.
        JsonArray decisionsJson = row.getJsonArray("decisions_snapshot_json");
        List<TaskDecisionDescriptor> decisions = parseDecisions(decisionsJson);

        String decisionName = row.getString("decision_name");
        String decisionPayloadJson = extractJsonb(row, "decision_payload_json");

        WorkflowActor completedBy = parseActorJson(row.getJsonObject("completed_by"));
        WorkflowActor cancelledBy = parseActorJson(row.getJsonObject("cancelled_by"));
        WorkflowActor reassignedBy = parseActorJson(row.getJsonObject("reassigned_by"));

        Instant dueAt = toInstant(row.getOffsetDateTime("due_at"));
        UUID dueDateTimerId = row.getUUID("due_date_timer_id");
        Instant completedAt = toInstant(row.getOffsetDateTime("completed_at"));
        Instant cancelledAt = toInstant(row.getOffsetDateTime("cancelled_at"));
        Instant expiredAt = toInstant(row.getOffsetDateTime("expired_at"));
        Instant updatedAt = toInstant(row.getOffsetDateTime("updated_at"));
        String cancellationReason = row.getString("cancellation_reason");
        String reassignmentReason = row.getString("reassignment_reason");
        String subjectVersionAtCreation = row.getString("subject_version_at_creation");

        UUID branchTokenId = row.getUUID("branch_token_id");
        String forkStepId = row.getString("fork_step_id");
        String branchId = row.getString("branch_id");
        return new TaskRecord(
                taskId,
                workflowId,
                stepId,
                assignment,
                status,
                decisions,
                dueAt,
                dueDateTimerId,
                completedAt,
                cancelledAt,
                expiredAt,
                updatedAt,
                decisionName,
                decisionPayloadJson,
                completedBy,
                cancelledBy,
                reassignedBy,
                cancellationReason,
                reassignmentReason,
                subjectVersionAtCreation,
                branchTokenId,
                forkStepId,
                branchId);
    }

    /**
     * Parses a {@link TaskAssignment} from the persisted {@code (kind, value)} pair.
     *
     * @param kind  the {@code assignee_type} column value ({@code USER}, {@code ROLE}, {@code QUEUE})
     * @param value the {@code assignee_key} column value
     * @return the corresponding {@link TaskAssignment}
     * @throws IllegalArgumentException if {@code kind} is unrecognised
     */
    private static TaskAssignment parseAssignment(String kind, String value) {
        return switch (kind) {
            case "USER" -> new TaskAssignment.User(value);
            case "ROLE" -> new TaskAssignment.Role(value);
            case "QUEUE" -> new TaskAssignment.Queue(value);
            default -> throw new IllegalArgumentException("Unknown assignee_type: " + kind);
        };
    }

    /**
     * Builds the canonical {@code {"kind": "...", "value": "..."}} JSON object for a
     * {@link WorkflowActor}. Bound to the JSONB column via {@link Tuple} as a {@link JsonObject};
     * the pg-client driver handles native JSONB encoding without an explicit cast.
     *
     * @param actor the actor to serialize; must not be null
     * @return a 2-field {@link JsonObject}
     */
    private static JsonObject actorJson(WorkflowActor actor) {
        return switch (actor) {
            case WorkflowActor.User u -> new JsonObject().put("kind", "USER").put("value", u.userId());
            case WorkflowActor.Service s ->
                new JsonObject().put("kind", "SERVICE").put("value", s.serviceId());
            case WorkflowActor.System sys ->
                new JsonObject().put("kind", "SYSTEM").put("value", sys.reason());
        };
    }

    /**
     * Parses a {@link WorkflowActor} from the JSONB column value (canonical
     * {@code {"kind": "...", "value": "..."}} shape).
     *
     * @param json the {@link JsonObject} read from the column, or {@code null} if no actor was
     *             recorded
     * @return the corresponding {@link WorkflowActor}, or {@code null} if {@code json} is null
     */
    @Nullable
    private static WorkflowActor parseActorJson(@Nullable JsonObject json) {
        if (json == null) {
            return null;
        }
        String kind = json.getString("kind");
        String value = json.getString("value");
        if (kind == null || value == null) {
            throw new IllegalStateException("workflow_tasks actor JSONB missing kind/value field: " + json.encode());
        }
        return switch (kind) {
            case "USER" -> new WorkflowActor.User(value);
            case "SERVICE" -> new WorkflowActor.Service(value);
            case "SYSTEM" -> new WorkflowActor.System(value);
            default -> throw new IllegalStateException("Unknown actor kind in workflow_tasks JSONB: " + kind);
        };
    }

    /**
     * Deserializes the {@code decisions_snapshot_json} JSONB column into a list of
     * {@link TaskDecisionDescriptor} records, using the Vert.x static {@link Json} codec.
     *
     * @param json the {@link JsonArray} read from the column; must not be {@code null}
     * @return the list of decision descriptors
     * @throws IllegalStateException if decoding fails
     */
    private static List<TaskDecisionDescriptor> parseDecisions(JsonArray json) {
        try {
            List<TaskDecisionDescriptor> out = new ArrayList<>(json.size());
            for (int i = 0; i < json.size(); i++) {
                JsonObject obj = json.getJsonObject(i);
                out.add(new TaskDecisionDescriptor(
                        obj.getString("name"), obj.getString("payloadTypeName"), obj.getString("nextStepId")));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize decisions_snapshot_json: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the persistence kind string for a {@link TaskAssignment}.
     *
     * @param assignment the assignment; must not be null
     * @return one of {@code "USER"}, {@code "ROLE"}, or {@code "QUEUE"}
     */
    private static String assignmentKindString(TaskAssignment assignment) {
        return switch (assignment) {
            case TaskAssignment.User u -> "USER";
            case TaskAssignment.Role r -> "ROLE";
            case TaskAssignment.Queue q -> "QUEUE";
        };
    }

    /**
     * Returns the persistence value string for a {@link TaskAssignment}.
     *
     * @param assignment the assignment; must not be null
     * @return the userId, roleId, or queueName depending on the permit
     */
    private static String assignmentValueString(TaskAssignment assignment) {
        return switch (assignment) {
            case TaskAssignment.User u -> u.userId();
            case TaskAssignment.Role r -> r.roleId();
            case TaskAssignment.Queue q -> q.queueName();
        };
    }

    /**
     * Extracts a JSONB column value as a {@code String}.
     *
     * @param row        the result row
     * @param columnName the JSONB column to extract
     * @return the JSON string, or {@code null} if the column value is null
     */
    @Nullable
    private static String extractJsonb(Row row, String columnName) {
        Object value = row.getValue(columnName);
        return value != null ? value.toString() : null;
    }

    /**
     * Converts an {@link Instant} to an {@link OffsetDateTime} at UTC offset for use as a
     * {@code TIMESTAMPTZ} parameter. Returns {@code null} when {@code instant} is {@code null}.
     *
     * @param instant the instant to convert, or {@code null}
     * @return the corresponding UTC {@link OffsetDateTime}, or {@code null}
     */
    @Nullable
    private static OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    /**
     * Converts an {@link OffsetDateTime} (as returned by the Vert.x pg-client for
     * {@code TIMESTAMPTZ} columns) to a UTC {@link Instant}.
     *
     * @param odt the offset date-time, or {@code null}
     * @return the corresponding {@link Instant}, or {@code null} if {@code odt} is {@code null}
     */
    @Nullable
    private static Instant toInstant(@Nullable OffsetDateTime odt) {
        return odt != null ? odt.withOffsetSameInstant(ZoneOffset.UTC).toInstant() : null;
    }
}
