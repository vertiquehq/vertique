// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed repository for {@link JoinState} rows in {@code workflow_join_states}
 * (PRD-WF-002).
 *
 * <p>The {@code (workflow_id, fork_step_id, join_step_id)} primary key plus the {@code version}
 * CAS guarantee that exactly one branch wins the race when multiple sibling branches complete
 * concurrently — see PRD-WF-002 §D7.
 */
@Singleton
public final class PgJoinStateRepository extends PgSqlRepository implements JoinStateRepository<SqlClient> {

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    // --- SQL statements ---
    //
    // The statements are built once at construction because every table reference is qualified with
    // the schema mapped for the workflow instance (when there is one), so the text depends on the
    // configured placement rather than being a compile-time constant.

    private final String sqlInsert;
    private final String sqlFindByKey;
    private final String sqlFindForUpdate;
    private final String sqlDecide;
    private final String sqlFindOpenForWorkflow;

    /**
     * Creates a new repository whose table is not schema-qualified, so it resolves through the
     * connection's default schema.
     *
     * @param pool the connection pool
     * @param mapper exception mapper for translating SQL errors
     */
    @Inject
    public PgJoinStateRepository(Pool pool, PgDbExceptionMapper mapper) {
        this(pool, mapper, DbSchemas.empty());
    }

    /**
     * Creates a new repository that qualifies every table reference with the schema mapped to the
     * {@code workflow} instance, when {@code schemas} holds one. Without a mapping the statements
     * are identical to those of the two-argument constructor.
     *
     * @param pool the connection pool
     * @param mapper exception mapper for translating SQL errors
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *     PostgreSQL identifier
     */
    public PgJoinStateRepository(Pool pool, PgDbExceptionMapper mapper, DbSchemas schemas) {
        super(pool, mapper);

        String joinStates = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_join_states");

        this.sqlInsert = "INSERT INTO " + joinStates + " (workflow_id, fork_step_id, join_step_id, policy, status,"
                + " winning_branch_id, decided_at, version, created_at, updated_at)"
                + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)";

        this.sqlFindByKey =
                "SELECT * FROM " + joinStates + " WHERE workflow_id = $1 AND fork_step_id = $2 AND join_step_id = $3";

        this.sqlFindForUpdate = "SELECT * FROM " + joinStates
                + " WHERE workflow_id = $1 AND fork_step_id = $2 AND join_step_id = $3"
                + " FOR UPDATE";

        this.sqlDecide = "UPDATE " + joinStates
                + " SET status = $1, winning_branch_id = $2, decided_at = $3, version = $4, updated_at = $5"
                + " WHERE workflow_id = $6 AND fork_step_id = $7 AND join_step_id = $8 AND version = $9";

        this.sqlFindOpenForWorkflow = "SELECT * FROM " + joinStates + " WHERE workflow_id = $1 AND status = 'OPEN'"
                + " ORDER BY created_at ASC";
    }

    /**
     * Inserts a new join-state row.
     *
     * @param state the join state to insert
     * @param tx the active transaction
     * @return a {@link Future} that completes when the row is inserted
     */
    @Override
    public Future<Void> insert(JoinState state, SqlClient tx) {
        return tx.preparedQuery(sqlInsert)
                .execute(Tuple.from(Arrays.asList(
                        state.workflowId().value(),
                        state.forkStepId(),
                        state.joinStepId(),
                        state.policy().name(),
                        state.status().name(),
                        state.winningBranchId(),
                        state.decidedAt() != null ? toOdt(state.decidedAt()) : null,
                        state.version(),
                        toOdt(state.createdAt()),
                        toOdt(state.updatedAt()))))
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_join_states insert")));
    }

    /**
     * Looks up the join state for the given (workflowId, forkStepId, joinStepId) triple.
     *
     * @param workflowId parent workflow instance
     * @param forkStepId fork step id
     * @param joinStepId join step id
     * @param tx the active transaction
     * @return a {@link Future} of the row, or {@link Optional#empty()} when missing
     */
    @Override
    public Future<Optional<JoinState>> findByKey(
            WorkflowInstanceId workflowId, String forkStepId, String joinStepId, SqlClient tx) {
        return tx.preparedQuery(sqlFindByKey)
                .execute(Tuple.of(workflowId.value(), forkStepId, joinStepId))
                .map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext()
                            ? Optional.of(RowMappers.joinState().map(it.next()))
                            : Optional.<JoinState>empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_join_states findByKey")));
    }

    /**
     * Looks up the join state with {@code FOR UPDATE} row locking. Used when serialising the
     * race-join decision (PRD-WF-002 §A.4.6 winner election).
     *
     * @param workflowId parent workflow instance
     * @param forkStepId fork step id
     * @param joinStepId join step id
     * @param tx the active transaction
     * @return a {@link Future} of the row, or {@link Optional#empty()} when missing
     */
    @Override
    public Future<Optional<JoinState>> findForUpdate(
            WorkflowInstanceId workflowId, String forkStepId, String joinStepId, SqlClient tx) {
        return tx.preparedQuery(sqlFindForUpdate)
                .execute(Tuple.of(workflowId.value(), forkStepId, joinStepId))
                .map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext()
                            ? Optional.of(RowMappers.joinState().map(it.next()))
                            : Optional.<JoinState>empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_join_states findForUpdate")));
    }

    /**
     * CAS-updates the join state's decision (status, winning branch, decided_at, version).
     *
     * @param workflowId parent workflow instance
     * @param forkStepId fork step id
     * @param joinStepId join step id
     * @param newStatus the new status (COMPLETED or FAILED)
     * @param winningBranchId optional winning branch id (null for ALL_REQUIRED success)
     * @param decidedAt decision timestamp
     * @param newVersion the new version (caller-supplied; typically previous + 1)
     * @param expectedVersion the previously-observed version
     * @param tx the active transaction
     * @return a {@link Future} of the affected row count: 1 on success, 0 on stale version
     */
    @Override
    public Future<Integer> decide(
            WorkflowInstanceId workflowId,
            String forkStepId,
            String joinStepId,
            JoinStateStatus newStatus,
            @Nullable String winningBranchId,
            Instant decidedAt,
            long newVersion,
            long expectedVersion,
            SqlClient tx) {
        return tx.preparedQuery(sqlDecide)
                .execute(Tuple.from(Arrays.asList(
                        newStatus.name(),
                        winningBranchId,
                        toOdt(decidedAt),
                        newVersion,
                        toOdt(decidedAt),
                        workflowId.value(),
                        forkStepId,
                        joinStepId,
                        expectedVersion)))
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_join_states decide")));
    }

    /**
     * Returns all OPEN join states for the given workflow instance, ordered by creation time.
     *
     * @param workflowId parent workflow instance
     * @param tx the active transaction (or read pool)
     * @return a {@link Future} of OPEN join states
     */
    @Override
    public Future<List<JoinState>> findOpenForWorkflow(WorkflowInstanceId workflowId, SqlClient tx) {
        return tx.preparedQuery(sqlFindOpenForWorkflow)
                .execute(Tuple.of(workflowId.value()))
                .map(rs -> {
                    java.util.List<JoinState> out = new java.util.ArrayList<>();
                    rs.forEach(row -> out.add(RowMappers.joinState().map(row)));
                    return List.copyOf(out);
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_join_states findOpen")));
    }

    private static OffsetDateTime toOdt(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
