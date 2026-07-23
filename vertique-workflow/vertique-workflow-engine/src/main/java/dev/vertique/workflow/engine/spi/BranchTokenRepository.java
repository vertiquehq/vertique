// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository SPI for {@link BranchToken} rows (PRD-WF-002 fork/join).
 *
 * <p>Generic over the transaction-handle type {@code TX} so the workflow engine can depend on this
 * interface without binding to a specific database client.
 *
 * <p>Branch transitions use optimistic concurrency on {@code version}; {@link #updateOptimistic}
 * returns the affected row count so callers can detect a stale snapshot. Recovery queries surface
 * RETRY_SCHEDULED branches whose retry is due and stale RUNNING branches.
 *
 * @param <TX> the transaction-handle type threaded through transactional operations
 */
public interface BranchTokenRepository<TX> {

    // --- Mutations ---

    /**
     * Inserts a new branch token in the active transaction.
     *
     * @param token the branch token to insert
     * @param tx    the active transaction
     * @return a {@link Future} that completes when the row is inserted
     */
    Future<Void> insert(BranchToken token, TX tx);

    /**
     * Applies an optimistic-concurrency update; returns the affected row count (0 means stale).
     *
     * @param updated         the desired post-update token (its {@link BranchToken#version()} is the
     *                        new version to write)
     * @param expectedVersion the caller's previously-observed version
     * @param tx              the active transaction
     * @return a {@link Future} of the affected row count: 1 on success, 0 on stale version
     */
    Future<Integer> updateOptimistic(BranchToken updated, long expectedVersion, TX tx);

    // --- Reads ---

    /**
     * Finds a branch token by primary key.
     *
     * @param id the branch token id
     * @param tx the active transaction (or read pool)
     * @return a {@link Future} containing the token, or {@link Optional#empty()} when missing
     */
    Future<Optional<BranchToken>> findById(UUID id, TX tx);

    /**
     * Finds a branch token by primary key, taking a row lock for the active transaction.
     *
     * @param id the branch token id
     * @param tx the active transaction
     * @return a {@link Future} containing the token, or {@link Optional#empty()} when missing
     */
    Future<Optional<BranchToken>> findByIdForUpdate(UUID id, TX tx);

    /**
     * Returns all branch tokens belonging to the given workflow instance, ordered by creation time
     * then branch id.
     *
     * @param workflowId the parent workflow instance
     * @param tx         the active transaction (or read pool)
     * @return a {@link Future} of the branch tokens
     */
    Future<List<BranchToken>> findByWorkflow(WorkflowInstanceId workflowId, TX tx);

    /**
     * Returns all non-terminal branch tokens belonging to the given workflow instance, ordered by
     * creation time then branch id.
     *
     * @param workflowId the parent workflow instance
     * @param tx         the active transaction
     * @return a {@link Future} of the active branch tokens
     */
    Future<List<BranchToken>> findActiveByWorkflow(WorkflowInstanceId workflowId, TX tx);

    /**
     * Returns all branch tokens belonging to a specific fork group, ordered by creation time then
     * branch id.
     *
     * @param workflowId the parent workflow instance
     * @param forkStepId the fork step id
     * @param tx         the active transaction (or read pool)
     * @return a {@link Future} of the branch tokens
     */
    Future<List<BranchToken>> findByWorkflowAndFork(WorkflowInstanceId workflowId, String forkStepId, TX tx);

    /**
     * Looks up a single branch token by its {@code (workflowId, forkStepId, branchId)} and active
     * wait {@code (waitType, waitKey)}. Used by the signal/timer/task callback path to resolve which
     * branch token owns an incoming event.
     *
     * @param workflowId the parent workflow instance
     * @param forkStepId the fork step id
     * @param branchId   the branch id
     * @param waitType   the active wait type (must match the row's {@code wait_type})
     * @param waitKey    the active wait key (must match the row's {@code wait_key})
     * @param tx         the active transaction
     * @return a {@link Future} containing the branch token if it exists with the matching wait
     */
    Future<Optional<BranchToken>> findByWaitForBranch(
            WorkflowInstanceId workflowId, String forkStepId, String branchId, String waitType, String waitKey, TX tx);

    /**
     * Returns RETRY_SCHEDULED branch tokens whose retry time is at or before {@code now}, ordered by
     * retry time ascending.
     *
     * @param now   the current time
     * @param limit maximum rows to return
     * @param tx    the active transaction
     * @return a {@link Future} of due branch tokens
     */
    Future<List<BranchToken>> findRecoverable(Instant now, int limit, TX tx);

    /**
     * Returns RUNNING branch tokens whose last update is older than {@code before}, ordered by
     * update time ascending.
     *
     * @param before stale-threshold timestamp
     * @param limit  maximum rows to return
     * @param tx     the active transaction
     * @return a {@link Future} of stale RUNNING branch tokens
     */
    Future<List<BranchToken>> findStaleRunning(Instant before, int limit, TX tx);

    /**
     * Returns branch tokens with the given status, ordered by creation time, up to {@code limit}
     * rows. A convenience query that selects by status without timestamp filtering.
     *
     * @param status the branch status to select
     * @param limit  maximum rows to return
     * @param tx     the active transaction
     * @return a {@link Future} of matching branch tokens
     */
    Future<List<BranchToken>> findByStatus(BranchStatus status, int limit, TX tx);
}
