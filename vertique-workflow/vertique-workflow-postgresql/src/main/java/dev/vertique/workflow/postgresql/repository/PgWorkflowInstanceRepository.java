// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed repository for {@link WorkflowInstance} persistence.
 *
 * <p>Provides transactional insert, read, and update operations used by the workflow engine.
 * Optimistic concurrency is enforced via the {@code version} column: {@link #updateOptimistic}
 * increments the version and returns 0 if the expected version no longer matches (stale write).
 *
 * <p>All methods that accept a {@link SqlClient tx} parameter participate in the caller's
 * transaction. {@link #findById} and {@link #findFiltered} are read-only and execute against the
 * pool directly (no row locking).
 *
 * <p>SQL follows the direct {@code tx.preparedQuery(SQL).execute(Tuple)} style used by the
 * reference {@code PgInboxOutboxRepository} implementation.
 */
@Singleton
public final class PgWorkflowInstanceRepository extends PgSqlRepository
        implements WorkflowInstanceRepository<SqlClient> {

    // --- SQL constants ---

    private static final String SQL_INSERT = "INSERT INTO workflow_instances"
            + " (id, definition_id, definition_version, plan_hash, version, status,"
            + " business_key, subject_type, subject_id, subject_version,"
            + " current_step_id, wait_type, wait_key, wait_aux_id, state_json,"
            + " error_type, error_message, metadata, created_at, updated_at)"
            + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15::jsonb, $16, $17,"
            + " $18::jsonb, $19, $20)";

    private static final String SQL_SELECT_ALL_COLUMNS =
            "SELECT id, definition_id, definition_version, plan_hash, version, status,"
                    + " business_key, subject_type, subject_id, subject_version,"
                    + " current_step_id, wait_type, wait_key, wait_aux_id, state_json,"
                    + " error_type, error_message, metadata, created_at, updated_at, completed_at"
                    + " FROM workflow_instances";

    private static final String SQL_FIND_BY_ID = SQL_SELECT_ALL_COLUMNS + " WHERE id = $1";

    private static final String SQL_FIND_BY_ID_FOR_UPDATE = SQL_SELECT_ALL_COLUMNS + " WHERE id = $1 FOR UPDATE";

    private static final String SQL_FIND_BY_BUSINESS_KEY =
            SQL_SELECT_ALL_COLUMNS + " WHERE definition_id = $1 AND business_key = $2";

    /**
     * Optimistic-concurrency UPDATE. Sets {@code completed_at} to {@code NOW()} on terminal-status
     * transitions and to {@code NULL} on non-terminal transitions. Retry from {@code FAILED} →
     * {@code RUNNING} therefore clears the stale terminal timestamp, so the column always reflects
     * the instance's current completion state rather than the most recent terminal event.
     *
     * <p>Parameters: $1=id, $2=new version, $3=status, $4=current_step_id, $5=wait_type,
     * $6=wait_key, $7=wait_aux_id, $8=state_json, $9=error_type, $10=error_message,
     * $11=expected (old) version.
     */
    private static final String SQL_UPDATE_OPTIMISTIC = "UPDATE workflow_instances"
            + " SET version = $2, status = $3, current_step_id = $4,"
            + " wait_type = $5, wait_key = $6, wait_aux_id = $7, state_json = $8::jsonb,"
            + " error_type = $9, error_message = $10, updated_at = NOW(),"
            + " completed_at = CASE WHEN $3::varchar IN ('COMPLETED','FAILED','COMPENSATED','CANCELLED','EXPIRED')"
            + "   THEN NOW() ELSE NULL END"
            + " WHERE id = $1 AND version = $11";

    /**
     * Migration-specific optimistic UPDATE. Re-pins the instance to a new definition version and
     * plan hash, clears all wait fields and error fields, forces {@code status = 'RUNNING'} and
     * {@code completed_at = NULL} (post-migration is always non-terminal), and writes the migrated
     * state JSON.
     *
     * <p>Parameters: $1=id, $2=definition_version, $3=plan_hash, $4=new version,
     * $5=current_step_id, $6=state_json, $7=expected (old) version.
     */
    private static final String SQL_MIGRATE_PIN_AND_STATE = "UPDATE workflow_instances"
            + " SET definition_version = $2, plan_hash = $3, version = $4,"
            + " status = 'RUNNING', current_step_id = $5,"
            + " wait_type = NULL, wait_key = NULL, wait_aux_id = NULL,"
            + " state_json = $6::jsonb,"
            + " error_type = NULL, error_message = NULL,"
            + " updated_at = NOW(), completed_at = NULL"
            + " WHERE id = $1 AND version = $7";

    // --- Constructor ---

    /**
     * Creates a new repository with the given connection pool and exception mapper.
     *
     * @param pool   the PostgreSQL connection pool
     * @param mapper the exception mapper for translating SQL errors to domain exceptions
     */
    @Inject
    public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper) {
        super(pool, mapper);
    }

    // --- Write operations ---

    /**
     * Inserts a new workflow instance row within the given transaction.
     *
     * @param inst the workflow instance to persist
     * @param tx   the active transaction to use for the insert
     * @return a {@link Future} that completes when the row is inserted
     */
    @Override
    public Future<Void> insert(WorkflowInstance inst, SqlClient tx) {
        WorkflowSubjectRef ref = inst.subjectRef();
        Tuple params = Tuple.of(
                inst.id().value(),
                inst.definitionId(),
                inst.definitionVersion(),
                inst.planHash(),
                inst.version(),
                inst.status().name(),
                inst.businessKey(),
                ref != null ? ref.type() : null,
                ref != null ? ref.id() : null,
                ref != null ? ref.version() : null,
                inst.currentStepId(),
                inst.waitType(),
                inst.waitKey(),
                inst.waitAuxId(),
                inst.stateJson(),
                inst.errorType(),
                inst.errorMessage(),
                inst.metadata() == null ? null : inst.metadata().toCarrier(),
                toOffsetDateTime(inst.createdAt()),
                toOffsetDateTime(inst.updatedAt()));

        return tx.preparedQuery(SQL_INSERT)
                .execute(params)
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances insert")));
    }

    /**
     * Performs an optimistic-concurrency update of an existing workflow instance row.
     *
     * <p>The UPDATE is conditioned on {@code version = expectedVersion}. If the row's version has
     * already been incremented by a concurrent transaction, the UPDATE matches 0 rows and this
     * method returns {@code 0}. On success it returns {@code 1} and the row's {@code version} is
     * set to {@code updated.version()}.
     *
     * @param updated         the updated workflow instance snapshot to write
     * @param expectedVersion the version value that must be present in the DB row for the update
     *                        to succeed
     * @param tx              the active transaction to use
     * @return a {@link Future} containing {@code 1} if the update succeeded, {@code 0} if the
     *         version was stale
     */
    @Override
    public Future<Integer> updateOptimistic(WorkflowInstance updated, long expectedVersion, SqlClient tx) {
        Tuple params = Tuple.of(
                updated.id().value(),
                updated.version(),
                updated.status().name(),
                updated.currentStepId(),
                updated.waitType(),
                updated.waitKey(),
                updated.waitAuxId(),
                updated.stateJson(),
                updated.errorType(),
                updated.errorMessage(),
                expectedVersion);

        return tx.preparedQuery(SQL_UPDATE_OPTIMISTIC)
                .execute(params)
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances updateOptimistic")));
    }

    /**
     * Migration-specific optimistic UPDATE that re-pins the instance to a new definition version
     * and plan hash, clears all wait and error fields, forces {@code status = 'RUNNING'} and
     * {@code completed_at = NULL}, and writes the migrated state JSON.
     *
     * <p>Unlike {@link #updateOptimistic}, this method also updates {@code definition_version},
     * {@code plan_hash}, and unconditionally nulls the wait columns and {@code completed_at},
     * because a post-migration instance is by definition non-terminal and in a clean RUNNING
     * state regardless of what the source instance held.
     *
     * @param updated         the post-migration workflow instance snapshot; must carry the new
     *                        {@code definitionVersion}, {@code planHash}, {@code version},
     *                        {@code currentStepId}, and {@code stateJson}
     * @param expectedVersion the version value that must be present in the DB row for the update
     *                        to succeed (optimistic concurrency guard)
     * @param tx              the active transaction to use
     * @return a {@link Future} containing {@code 1} if the update succeeded, {@code 0} if the
     *         expected version no longer matches (stale write)
     */
    @Override
    public Future<Integer> migratePinAndState(WorkflowInstance updated, long expectedVersion, SqlClient tx) {
        Tuple params = Tuple.of(
                updated.id().value(),
                updated.definitionVersion(),
                updated.planHash(),
                updated.version(),
                updated.currentStepId(),
                updated.stateJson(),
                expectedVersion);

        return tx.preparedQuery(SQL_MIGRATE_PIN_AND_STATE)
                .execute(params)
                .map(rs -> rs.rowCount())
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_instances migratePinAndState")));
    }

    // --- Read operations ---

    /**
     * Loads a workflow instance by id without row locking. Uses the pool directly.
     *
     * @param id the workflow instance id to look up
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    @Override
    public Future<Optional<WorkflowInstance>> findById(WorkflowInstanceId id) {
        return pool.preparedQuery(SQL_FIND_BY_ID)
                .execute(Tuple.of(id.value()))
                .<Optional<WorkflowInstance>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext()
                            ? Optional.of(RowMappers.workflowInstance().map(it.next()))
                            : Optional.empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances findById")));
    }

    /**
     * Loads a workflow instance by id within the supplied transaction (no row lock). Used for
     * snapshot-consistent reads where the caller has already opened a transaction (e.g.,
     * {@code REPEATABLE READ} for {@code query()}); a plain pool-based read would take a fresh
     * snapshot per statement and could interleave with concurrent writes.
     *
     * @param id the workflow instance id to look up
     * @param tx the active transaction or pooled connection to use for the read
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    @Override
    public Future<Optional<WorkflowInstance>> findById(WorkflowInstanceId id, SqlClient tx) {
        return tx.preparedQuery(SQL_FIND_BY_ID)
                .execute(Tuple.of(id.value()))
                .<Optional<WorkflowInstance>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext()
                            ? Optional.of(RowMappers.workflowInstance().map(it.next()))
                            : Optional.empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances findById(tx)")));
    }

    /**
     * Loads a workflow instance by id and acquires a {@code SELECT FOR UPDATE} row lock within
     * the given transaction.
     *
     * @param id the workflow instance id to look up
     * @param tx the active transaction to use for the locking read
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    @Override
    public Future<Optional<WorkflowInstance>> findByIdForUpdate(WorkflowInstanceId id, SqlClient tx) {
        return tx.preparedQuery(SQL_FIND_BY_ID_FOR_UPDATE)
                .execute(Tuple.of(id.value()))
                .<Optional<WorkflowInstance>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext()
                            ? Optional.of(RowMappers.workflowInstance().map(it.next()))
                            : Optional.empty();
                })
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances findByIdForUpdate")));
    }

    /**
     * Loads a workflow instance by definition id and business key within the given transaction.
     *
     * <p>The business key uniqueness is enforced by a partial unique index on
     * {@code (definition_id, business_key) WHERE business_key IS NOT NULL}. This method returns
     * empty immediately when {@code bk} is {@code null}.
     *
     * @param defId the definition id scope for the business key
     * @param bk    the business key value to look up; may be {@code null}
     * @param tx    the active transaction to use
     * @return a {@link Future} containing the instance if found, or {@link Optional#empty()}
     */
    @Override
    public Future<Optional<WorkflowInstance>> findByBusinessKey(String defId, String bk, SqlClient tx) {
        if (bk == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return tx.preparedQuery(SQL_FIND_BY_BUSINESS_KEY)
                .execute(Tuple.of(defId, bk))
                .<Optional<WorkflowInstance>>map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext()
                            ? Optional.of(RowMappers.workflowInstance().map(it.next()))
                            : Optional.empty();
                })
                .recover(
                        t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances findByBusinessKey")));
    }

    /**
     * Returns a keyset-paginated, filtered list of workflow instances ordered by {@code updated_at
     * DESC, id ASC} (deterministic, with {@code id} as the unique tiebreaker). Executes against the
     * pool directly (read-only, no row locking).
     *
     * <p>The result is a {@link PagedResult} with cursor tokens for forward ({@link
     * PagedResult#nextCursorToken()}) and backward ({@link PagedResult#previousCursorToken()})
     * navigation. No total-row count is provided — this is keyset pagination, not offset.
     *
     * <p>When {@link WorkflowInstanceQuery#subjectVersion()} is non-null, the query adds a
     * {@code subject_version = $N} predicate and the partial composite index
     * {@code idx_workflow_instances_subject_versioned} will be used by the planner for
     * version-aware queries ({@code FR-WF-123}).
     *
     * @param query  the query criteria to apply; fields set to {@code null} are ignored
     * @param cursor the page cursor; pass {@link PageCursor#first(int)} for the first page and
     *               {@link PageCursor#fromToken(String)} for subsequent pages
     * @return a {@link Future} containing the keyset-paginated result
     * @throws NullPointerException if {@code cursor} is {@code null} (raised synchronously by the
     *                              underlying paged-query builder)
     */
    @Override
    public Future<PagedResult<WorkflowInstance>> findFiltered(WorkflowInstanceQuery query, PageCursor cursor) {
        StringBuilder sql = new StringBuilder(SQL_SELECT_ALL_COLUMNS);
        List<Object> paramValues = new ArrayList<>();

        // --- Dynamic WHERE clause ---
        boolean hasWhere = false;

        if (query.status() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.status().name());
            sql.append(" status = $").append(paramValues.size());
            hasWhere = true;
        }
        if (query.definitionId() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.definitionId());
            sql.append(" definition_id = $").append(paramValues.size());
            hasWhere = true;
        }
        if (query.businessKey() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.businessKey());
            sql.append(" business_key = $").append(paramValues.size());
            hasWhere = true;
        }
        if (query.subjectType() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.subjectType());
            sql.append(" subject_type = $").append(paramValues.size());
            hasWhere = true;
        }
        if (query.subjectId() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.subjectId());
            sql.append(" subject_id = $").append(paramValues.size());
            hasWhere = true;
        }
        if (query.subjectVersion() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.subjectVersion());
            sql.append(" subject_version = $").append(paramValues.size());
            hasWhere = true;
        }
        if (!query.includeArchived()) {
            // Exclude soft-deleted rows by default. Engine reads (findById, findByIdForUpdate)
            // bypass this filter so the engine sees archived rows for auditing and purge validation.
            sql.append(hasWhere ? " AND" : " WHERE");
            sql.append(" archived_at IS NULL");
            hasWhere = true;
        }

        return this.<WorkflowInstance>pagedQuery(sql.toString())
                .params(Tuple.from(paramValues))
                .mapping(RowMappers.workflowInstance())
                .orderBy(OrderKey.desc("updated_at"), OrderKey.asc("id"))
                .uniqueKey("id")
                .page(cursor);
    }

    // --- Internal helpers ---

    /**
     * Converts an {@link java.time.Instant} to an {@link OffsetDateTime} at UTC for use in
     * {@code TIMESTAMPTZ} parameters. The Vert.x pg-client requires {@link OffsetDateTime} for
     * timestamp-with-timezone columns. Returns {@code null} when {@code instant} is {@code null}.
     *
     * @param instant the instant to convert, or {@code null}
     * @return the UTC {@link OffsetDateTime}, or {@code null}
     */
    private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
