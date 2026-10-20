// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.DefinitionPinAdmission;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the workflow instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    private final String sqlInsert;
    private final String sqlSelectAllColumns;
    private final String sqlFindById;
    private final String sqlFindByIdForUpdate;

    /** Parameters: $1=definition id, $2=definition version. Archived rows still pin their version. */
    private final String sqlCountNonTerminal;

    private final String sqlFindByBusinessKey;

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
    private final String sqlUpdateOptimistic;

    /**
     * The optimistic UPDATE restricted to an instance that already is a counted pin: its persisted status
     * is running, waiting or compensating. It needs no definition admission. Parameters as for
     * {@link #sqlUpdateOptimistic}.
     */
    private final String sqlUpdateOptimisticLive;

    /** Parameters: $1=id. Reads the persisted pin and state that decide whether a finished instance may resume. */
    private final String sqlReadPin;

    private static final Set<WorkflowStatus> TERMINAL_STATUSES = Set.of(
            WorkflowStatus.COMPLETED,
            WorkflowStatus.FAILED,
            WorkflowStatus.COMPENSATED,
            WorkflowStatus.CANCELLED,
            WorkflowStatus.EXPIRED);

    /**
     * Migration-specific optimistic UPDATE. Re-pins the instance to a new definition version and
     * plan hash, clears all wait fields and error fields, forces {@code status = 'RUNNING'} and
     * {@code completed_at = NULL} (post-migration is always non-terminal), and writes the migrated
     * state JSON.
     *
     * <p>Parameters: $1=id, $2=definition_version, $3=plan_hash, $4=new version,
     * $5=current_step_id, $6=state_json, $7=expected (old) version.
     */
    private final String sqlMigratePinAndState;

    /**
     * The table the pin admission reads the stored definition rows from, qualified with the schema
     * mapped to the {@code workflow} instance when there is one.
     */
    private final String definitionsTable;

    // --- Constructor ---

    /**
     * Creates a new repository whose table is not schema-qualified, so it resolves through the
     * connection's default schema.
     *
     * @param pool   the PostgreSQL connection pool
     * @param mapper the exception mapper for translating SQL errors to domain exceptions
     */
    @Inject
    public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper) {
        this(pool, mapper, DbSchemas.empty());
    }

    /**
     * Creates a new repository that qualifies every table reference with the schema mapped to the
     * {@code workflow} instance, when {@code schemas} holds one. Without a mapping the statements
     * are identical to those of the two-argument constructor.
     *
     * @param pool    the PostgreSQL connection pool
     * @param mapper  the exception mapper for translating SQL errors to domain exceptions
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *                                  PostgreSQL identifier
     */
    public PgWorkflowInstanceRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas) {
        super(pool, mapper);

        String instances = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_instances");
        this.definitionsTable = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_definitions");

        this.sqlInsert = "INSERT INTO " + instances
                + " (id, definition_id, definition_version, plan_hash, version, status,"
                + " business_key, subject_type, subject_id, subject_version,"
                + " current_step_id, wait_type, wait_key, wait_aux_id, state_json,"
                + " error_type, error_message, metadata, created_at, updated_at, tenant_key)"
                + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15::jsonb, $16, $17,"
                + " $18::jsonb, $19, $20, $21)";

        this.sqlSelectAllColumns = "SELECT id, definition_id, definition_version, plan_hash, version, status,"
                + " business_key, subject_type, subject_id, subject_version,"
                + " current_step_id, wait_type, wait_key, wait_aux_id, state_json,"
                + " error_type, error_message, metadata, created_at, updated_at, completed_at, tenant_key"
                + " FROM " + instances;

        this.sqlFindById = sqlSelectAllColumns + " WHERE id = $1";

        this.sqlFindByIdForUpdate = sqlSelectAllColumns + " WHERE id = $1 FOR UPDATE";

        this.sqlCountNonTerminal = "SELECT count(*) AS total FROM " + instances
                + " WHERE definition_id = $1 AND definition_version = $2"
                + " AND status IN ('RUNNING','WAITING','COMPENSATING')";

        this.sqlFindByBusinessKey = sqlSelectAllColumns + " WHERE definition_id = $1 AND business_key = $2";

        this.sqlUpdateOptimistic = "UPDATE " + instances
                + " SET version = $2, status = $3, current_step_id = $4,"
                + " wait_type = $5, wait_key = $6, wait_aux_id = $7, state_json = $8::jsonb,"
                + " error_type = $9, error_message = $10, updated_at = NOW(),"
                + " completed_at = CASE WHEN $3::varchar IN ('COMPLETED','FAILED','COMPENSATED','CANCELLED','EXPIRED')"
                + "   THEN NOW() ELSE NULL END"
                + " WHERE id = $1 AND version = $11";

        this.sqlUpdateOptimisticLive = sqlUpdateOptimistic + " AND status IN ('RUNNING','WAITING','COMPENSATING')";

        this.sqlReadPin = "SELECT definition_id, definition_version, plan_hash, status, version FROM " + instances
                + " WHERE id = $1";

        this.sqlMigratePinAndState = "UPDATE " + instances
                + " SET definition_version = $2, plan_hash = $3, version = $4,"
                + " status = 'RUNNING', current_step_id = $5,"
                + " wait_type = NULL, wait_key = NULL, wait_aux_id = NULL,"
                + " state_json = $6::jsonb,"
                + " error_type = NULL, error_message = NULL,"
                + " updated_at = NOW(), completed_at = NULL"
                + " WHERE id = $1 AND version = $7";
    }

    // --- Write operations ---

    /**
     * Inserts a new workflow instance row within the given transaction.
     *
     * <p>The insert creates a pin on a definition version, so the version is admitted first (see
     * {@link DefinitionPinAdmission}). A managed definition that does not admit the version fails the
     * returned future and nothing is inserted. A definition id without stored rows is admitted under
     * READ COMMITTED.
     *
     * @param inst the workflow instance to persist
     * @param tx   the active transaction to use for the insert; must be a connection with a pending
     *             transaction begun through Vert.x, otherwise the future fails with
     *             {@link IllegalArgumentException} before any SQL
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
                toOffsetDateTime(inst.updatedAt()),
                inst.tenantKey());

        return DefinitionPinAdmission.admitPin(
                        tx,
                        definitionsTable,
                        inst.definitionId(),
                        inst.definitionVersion(),
                        inst.planHash(),
                        exceptionMapper)
                .compose(admitted -> tx.preparedQuery(sqlInsert)
                        .execute(params)
                        .<Void>mapEmpty()
                        .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances insert"))));
    }

    /**
     * Performs an optimistic-concurrency update of an existing workflow instance row.
     *
     * <p>The UPDATE is conditioned on {@code version = expectedVersion}. If the row's version has
     * already been incremented by a concurrent transaction, the UPDATE matches 0 rows and this
     * method returns {@code 0}. On success it returns {@code 1} and the row's {@code version} is
     * set to {@code updated.version()}.
     *
     * <p>An update to a terminal status is the original conditional update. An update to a running,
     * waiting or compensating status first runs that update restricted to an instance whose persisted
     * status already is running, waiting or compensating: such an instance is a counted pin, so no
     * definition read is needed. When that matches no row, the persisted row is read in the same
     * transaction. A missing row, a version other than {@code expectedVersion} or a persisted status that
     * is not terminal returns {@code 0}. A persisted terminal status means the update would resurrect a
     * finished instance: the pinned version is then admitted (see {@link DefinitionPinAdmission}; a
     * deprecated version still admits it, a retired, candidate or activating one, or a different plan
     * hash, does not) and the original update is run again. A refusal fails the returned future.
     *
     * @param updated         the updated workflow instance snapshot to write
     * @param expectedVersion the version value that must be present in the DB row for the update
     *                        to succeed
     * @param tx              the active transaction to use; for an update to a running, waiting or
     *                        compensating status it must be a connection with a pending transaction
     *                        begun through Vert.x, otherwise the future fails with
     *                        {@link IllegalArgumentException} before any SQL
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

        if (TERMINAL_STATUSES.contains(updated.status())) {
            return executeUpdate(sqlUpdateOptimistic, params, tx);
        }
        return DefinitionPinAdmission.requireTransaction(tx)
                .compose(valid -> executeUpdate(sqlUpdateOptimisticLive, params, tx))
                .compose(
                        rows -> rows > 0 ? Future.succeededFuture(rows) : resume(updated, expectedVersion, params, tx));
    }

    /** Runs the slow path of an update to a nonterminal status that matched no counted pin. */
    private Future<Integer> resume(WorkflowInstance updated, long expectedVersion, Tuple params, SqlClient tx) {
        return tx.preparedQuery(sqlReadPin)
                .execute(Tuple.of(updated.id().value()))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances updateOptimistic")))
                .compose(rs -> {
                    var it = rs.iterator();
                    if (!it.hasNext()) return Future.succeededFuture(0);
                    Row persisted = it.next();
                    if (persisted.getLong("version") != expectedVersion || !isTerminal(persisted.getString("status"))) {
                        return Future.succeededFuture(0);
                    }
                    return DefinitionPinAdmission.admitResurrection(
                                    tx,
                                    definitionsTable,
                                    persisted.getString("definition_id"),
                                    persisted.getLong("definition_version"),
                                    persisted.getString("plan_hash"),
                                    exceptionMapper)
                            .compose(admitted -> executeUpdate(sqlUpdateOptimistic, params, tx));
                });
    }

    private Future<Integer> executeUpdate(String sql, Tuple params, SqlClient tx) {
        return tx.preparedQuery(sql)
                .execute(params)
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances updateOptimistic")));
    }

    private static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(WorkflowStatus.valueOf(status));
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
     * <p>The target version is admitted before the update (see {@link DefinitionPinAdmission}).
     *
     * @param updated         the post-migration workflow instance snapshot; must carry the new
     *                        {@code definitionVersion}, {@code planHash}, {@code version},
     *                        {@code currentStepId}, and {@code stateJson}
     * @param expectedVersion the version value that must be present in the DB row for the update
     *                        to succeed (optimistic concurrency guard)
     * @param tx              the active transaction to use; must be a connection with a pending
     *                        transaction begun through Vert.x, otherwise the future fails with
     *                        {@link IllegalArgumentException} before any SQL
     * @return a {@link Future} containing {@code 1} if the update succeeded, {@code 0} if the
     *         expected version no longer matches (stale write); the future fails when the target
     *         version is not admitted
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

        return DefinitionPinAdmission.admitPin(
                        tx,
                        definitionsTable,
                        updated.definitionId(),
                        updated.definitionVersion(),
                        updated.planHash(),
                        exceptionMapper)
                .compose(admitted -> tx.preparedQuery(sqlMigratePinAndState)
                        .execute(params)
                        .map(rs -> rs.rowCount())
                        .recover(t -> Future.failedFuture(
                                exceptionMapper.translate(t, "workflow_instances migratePinAndState"))));
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
        return pool.preparedQuery(sqlFindById)
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
     * Counts the instances pinned to exactly one definition version that are running, waiting or
     * compensating, archived instances included. Uses the pool directly without row locking.
     *
     * @param definitionId the workflow definition id
     * @param version the exact definition version
     * @return a {@link Future} containing the number of non-terminal instances
     */
    public Future<Long> countNonTerminal(String definitionId, long version) {
        return pool.preparedQuery(sqlCountNonTerminal)
                .execute(Tuple.of(definitionId, version))
                .<Long>map(rs -> rs.iterator().next().getLong("total"))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_instances countNonTerminal")));
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
        return tx.preparedQuery(sqlFindById)
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
        return tx.preparedQuery(sqlFindByIdForUpdate)
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
        return tx.preparedQuery(sqlFindByBusinessKey)
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
        StringBuilder sql = new StringBuilder(sqlSelectAllColumns);
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
        if (query.tenantKey() != null) {
            sql.append(hasWhere ? " AND" : " WHERE");
            paramValues.add(query.tenantKey());
            sql.append(" tenant_key = $").append(paramValues.size());
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
