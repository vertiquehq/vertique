// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.query;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.BranchTokenFilter;
import dev.vertique.workflow.state.JoinState;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

/**
 * Read-only query API for PRD-WF-002 branch tokens and join states (PRD §7.6 / NFR-WF-PAR-003).
 *
 * <p>Wraps {@link PgBranchTokenRepository} and {@link PgJoinStateRepository} with workflow-aware
 * filtering. All methods open a fresh read-pool transaction so callers don't need to manage
 * connection lifecycle. Filters are matched in-memory after the indexed read; the underlying
 * indexes (status_lookup partial on {@code (workflow_id, fork_step_id, status)} and the
 * wait partial) keep the read &lt; 200 ms under production scale.
 */
@Singleton
public final class PgBranchTokenQueryService {

    private final Pool pool;
    private final PgBranchTokenRepository branchTokens;
    private final PgJoinStateRepository joinStates;

    /**
     * Creates a new query service.
     *
     * @param pool the read-capable pool (transactions are opened per-call)
     * @param branchTokens branch-token repository
     * @param joinStates join-state repository
     */
    @Inject
    public PgBranchTokenQueryService(
            Pool pool, PgBranchTokenRepository branchTokens, PgJoinStateRepository joinStates) {
        this.pool = pool;
        this.branchTokens = branchTokens;
        this.joinStates = joinStates;
    }

    /**
     * Returns all branch tokens for the given workflow that satisfy {@code filter}.
     *
     * @param workflowId parent workflow instance id
     * @param filter optional filter; use {@link BranchTokenFilter#all()} for no filtering
     * @return a {@link Future} of the matching branch tokens, in creation order
     */
    public Future<List<BranchToken>> findByWorkflow(WorkflowInstanceId workflowId, BranchTokenFilter filter) {
        BranchTokenFilter effective = filter == null ? BranchTokenFilter.all() : filter;
        return pool.withConnection(conn -> {
            Future<List<BranchToken>> base;
            if (effective.forkStepId() != null) {
                base = branchTokens.findByWorkflowAndFork(workflowId, effective.forkStepId(), conn);
            } else {
                base = branchTokens.findByWorkflow(workflowId, conn);
            }
            return base.map(rows -> rows.stream()
                    .filter(t -> effective.status() == null || t.status() == effective.status())
                    .filter(t -> effective.waitType() == null || t.waitType() == effective.waitType())
                    .toList());
        });
    }

    /**
     * Looks up the join state for {@code (workflowId, forkStepId, joinStepId)}.
     *
     * @param workflowId parent workflow instance id
     * @param forkStepId fork step id
     * @param joinStepId join step id
     * @return a {@link Future} of the join state, or empty if none exists
     */
    public Future<Optional<JoinState>> findJoinState(
            WorkflowInstanceId workflowId, String forkStepId, String joinStepId) {
        return pool.withConnection(conn -> joinStates.findByKey(workflowId, forkStepId, joinStepId, conn));
    }

    /**
     * Returns all OPEN join states for the given workflow instance, ordered by creation time.
     *
     * @param workflowId parent workflow instance id
     * @return a {@link Future} of OPEN join states
     */
    public Future<List<JoinState>> findOpenJoinStates(WorkflowInstanceId workflowId) {
        return pool.withConnection(conn -> joinStates.findOpenForWorkflow(workflowId, conn));
    }
}
