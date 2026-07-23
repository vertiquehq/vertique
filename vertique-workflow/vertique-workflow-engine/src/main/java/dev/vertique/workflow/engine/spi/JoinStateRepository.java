// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository SPI for {@link JoinState} rows (PRD-WF-002 fork/join).
 *
 * <p>Generic over the transaction-handle type {@code TX} so the workflow engine can depend on this
 * interface without binding to a specific database client.
 *
 * <p>The {@code (workflowId, forkStepId, joinStepId)} key plus the {@code version} CAS in
 * {@link #decide} guarantee that exactly one branch wins the race when multiple sibling branches
 * complete concurrently.
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface JoinStateRepository<TX> {

    /**
     * Inserts a new join-state row.
     *
     * @param state the join state to insert
     * @param tx    the active transaction
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> insert(JoinState state, TX tx);

    /**
     * Looks up the join state for the given {@code (workflowId, forkStepId, joinStepId)} triple.
     *
     * @param workflowId parent workflow instance
     * @param forkStepId fork step id
     * @param joinStepId join step id
     * @param tx         the active transaction
     * @return a {@link Future} of the row, or {@link Optional#empty()} when missing
     */
    Future<Optional<JoinState>> findByKey(WorkflowInstanceId workflowId, String forkStepId, String joinStepId, TX tx);

    /**
     * Looks up the join state with row locking ({@code SELECT ... FOR UPDATE}). Used when
     * serialising the race-join decision (winner election).
     *
     * @param workflowId parent workflow instance
     * @param forkStepId fork step id
     * @param joinStepId join step id
     * @param tx         the active transaction
     * @return a {@link Future} of the row, or {@link Optional#empty()} when missing
     */
    Future<Optional<JoinState>> findForUpdate(
            WorkflowInstanceId workflowId, String forkStepId, String joinStepId, TX tx);

    /**
     * CAS-updates the join state's decision (status, winning branch, decided-at, version).
     *
     * @param workflowId      parent workflow instance
     * @param forkStepId      fork step id
     * @param joinStepId      join step id
     * @param newStatus       the new status (COMPLETED or FAILED)
     * @param winningBranchId optional winning branch id (null for ALL_REQUIRED success)
     * @param decidedAt       decision timestamp
     * @param newVersion      the new version (caller-supplied; typically previous + 1)
     * @param expectedVersion the previously-observed version
     * @param tx              the active transaction
     * @return a {@link Future} of the affected row count: 1 on success, 0 on stale version
     */
    Future<Integer> decide(
            WorkflowInstanceId workflowId,
            String forkStepId,
            String joinStepId,
            JoinStateStatus newStatus,
            @Nullable String winningBranchId,
            Instant decidedAt,
            long newVersion,
            long expectedVersion,
            TX tx);

    /**
     * Returns all OPEN join states for the given workflow instance, ordered by creation time.
     *
     * @param workflowId parent workflow instance
     * @param tx         the active transaction (or read pool)
     * @return a {@link Future} of OPEN join states
     */
    Future<List<JoinState>> findOpenForWorkflow(WorkflowInstanceId workflowId, TX tx);
}
