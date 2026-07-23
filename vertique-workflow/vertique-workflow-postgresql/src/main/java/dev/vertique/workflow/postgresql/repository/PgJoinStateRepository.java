// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
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

    private static final String SQL_INSERT =
            "INSERT INTO workflow_join_states (workflow_id, fork_step_id, join_step_id, policy, status,"
                    + " winning_branch_id, decided_at, version, created_at, updated_at)"
                    + " VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)";

    private static final String SQL_FIND_BY_KEY =
            "SELECT * FROM workflow_join_states WHERE workflow_id = $1 AND fork_step_id = $2 AND join_step_id = $3";

    private static final String SQL_FIND_FOR_UPDATE =
            "SELECT * FROM workflow_join_states WHERE workflow_id = $1 AND fork_step_id = $2 AND join_step_id = $3"
                    + " FOR UPDATE";

    private static final String SQL_DECIDE = "UPDATE workflow_join_states"
            + " SET status = $1, winning_branch_id = $2, decided_at = $3, version = $4, updated_at = $5"
            + " WHERE workflow_id = $6 AND fork_step_id = $7 AND join_step_id = $8 AND version = $9";

    private static final String SQL_FIND_OPEN_FOR_WORKFLOW =
            "SELECT * FROM workflow_join_states WHERE workflow_id = $1 AND status = 'OPEN'"
                    + " ORDER BY created_at ASC";

    /**
     * Creates a new repository.
     *
     * @param pool the connection pool
     * @param mapper exception mapper for translating SQL errors
     */
    @Inject
    public PgJoinStateRepository(Pool pool, PgDbExceptionMapper mapper) {
        super(pool, mapper);
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
        return tx.preparedQuery(SQL_INSERT)
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
        return tx.preparedQuery(SQL_FIND_BY_KEY)
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
        return tx.preparedQuery(SQL_FIND_FOR_UPDATE)
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
        return tx.preparedQuery(SQL_DECIDE)
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
        return tx.preparedQuery(SQL_FIND_OPEN_FOR_WORKFLOW)
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
