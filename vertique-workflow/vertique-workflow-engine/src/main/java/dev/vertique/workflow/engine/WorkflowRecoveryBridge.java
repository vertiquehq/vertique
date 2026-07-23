// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;

/**
 * Public SPI that exposes the small set of package-private engine internals a dialect-side branch
 * recovery sweep needs, without widening the visibility of the engine collaborators themselves
 * (FR-WFE-009).
 *
 * <p>The portable engine keeps {@code BranchTransitionEngine}, {@code ForkJoinCoordinator}, and
 * {@code WorkflowPayloads} package-private. A dialect recovery service lives in another module and
 * therefore cannot reach those internals directly.
 * This bridge is the use-case-shaped seam: each method maps onto exactly one recovery action the
 * service performs while holding the transaction open. The implementation is package-private and
 * bound in {@link WorkflowEngineModule}; the recovery service injects this interface in place of
 * the former direct field access and static calls.
 *
 * <p>The bridge is pure orchestration: it performs no transaction management and owns no durable
 * context scope. The dialect recovery service retains ownership of the transaction boundary, the
 * durable-context bind, and the optimistic-concurrency plumbing — the bridge simply runs the engine
 * logic against the {@link SqlClient} transaction handle it is given.
 */
public interface WorkflowRecoveryBridge {

    /**
     * Drives a recovered branch token through its transitions, mirroring the inline drive performed
     * by the engine's own fork dispatch. Used by the recovery service's {@code resumeOne} path after
     * it has promoted a {@code RETRY_SCHEDULED} branch back to {@code RUNNING}.
     *
     * @param token the branch token to drive (already promoted to {@code RUNNING} by the caller)
     * @param parentStateJson the parent instance's {@code state_json} at dispatch time
     * @param parentSubjectVersion the parent instance's subject-ref version at dispatch time, or
     *     {@code null} if the instance has no subject ref
     * @param rw the resolved runtime workflow (defines the plan + callbacks); the caller must have
     *     already validated the plan hash via {@link #validateRecoveryPlan}
     * @param tx the active transaction handle
     * @param effectiveBaseOverride the recovery service's pre-computed merged durable-context
     *     document ({@code token.metadata().merge(instance.metadata(), MergePolicy.CALLER_WINS)}),
     *     or {@code null} to bind {@code token.metadata()} unchanged (Contract Appendix C3)
     * @return a {@link Future} of the post-drive {@link BranchToken} snapshot
     */
    Future<BranchToken> driveRecoveredBranch(
            BranchToken token,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx,
            @Nullable dev.vertique.core.context.DurableMetadata effectiveBaseOverride);

    /**
     * Evaluates the parent join for a recovered branch only when that branch has reached a terminal
     * state. Used by both the {@code resumeOne} path (after driving the branch) and the
     * {@code demoteOne} path (after a stale {@code RUNNING} branch with no retry budget is failed),
     * so the parent instance does not stay parked at the join forever.
     *
     * @param inst the parent workflow instance already loaded by the recovery service
     * @param branch the branch token that may have terminated during recovery
     * @param rw the resolved runtime workflow (must already be plan-hash-checked by the caller)
     * @param tx the active transaction handle
     * @return a {@link Future} that completes when join evaluation finishes, or immediately when the
     *     branch is not terminal
     */
    Future<Void> evaluateRecoveredBranchIfTerminal(
            WorkflowInstance inst, BranchToken branch, RuntimeWorkflow rw, SqlClient tx);

    /**
     * Applies the plan-hash drift guard so recovery never silently runs new callbacks against a
     * persisted instance after a redeployment. Used by the recovery service's
     * {@code loadInstanceAndRuntime} path immediately after resolving the pinned runtime workflow.
     *
     * @param inst the workflow instance whose stored {@code planHash} is to be checked
     * @param rw the resolved runtime workflow whose {@code planHash} is compared to the stored hash
     * @throws dev.vertique.workflow.exception.WorkflowPlanHashDriftException if the hashes do not
     *     match
     */
    void validateRecoveryPlan(WorkflowInstance inst, RuntimeWorkflow rw);
}
