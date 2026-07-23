// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Package-private implementation of {@link WorkflowRecoveryBridge}.
 *
 * <p>Delegates each recovery use case to the package-private engine collaborators it injects —
 * {@link BranchTransitionEngine} for the branch drive, {@link ForkJoinCoordinator} for the terminal
 * join evaluation, and the static {@link WorkflowPayloads#requirePlanHashMatchesForRecovery} guard.
 * Because those collaborators live in this package, the bridge can reach them while the public SPI
 * keeps them invisible to dialect modules.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}, matching the
 * scope of the collaborators it injects.
 */
@Singleton
final class WorkflowRecoveryBridgeImpl implements WorkflowRecoveryBridge {

    private final BranchTransitionEngine branchEngine;
    private final ForkJoinCoordinator forkJoin;

    /**
     * Constructs a new recovery bridge.
     *
     * @param branchEngine the branch transition engine used to drive a recovered branch
     * @param forkJoin the fork/join coordinator used to evaluate the parent join when a recovered
     *     branch reaches a terminal state
     */
    @Inject
    WorkflowRecoveryBridgeImpl(BranchTransitionEngine branchEngine, ForkJoinCoordinator forkJoin) {
        this.branchEngine = branchEngine;
        this.forkJoin = forkJoin;
    }

    @Override
    public Future<BranchToken> driveRecoveredBranch(
            BranchToken token,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx,
            @Nullable dev.vertique.core.context.DurableMetadata effectiveBaseOverride) {
        return branchEngine.driveBranchTransitions(
                token, parentStateJson, parentSubjectVersion, rw, tx, effectiveBaseOverride);
    }

    @Override
    public Future<Void> evaluateRecoveredBranchIfTerminal(
            WorkflowInstance inst, BranchToken branch, RuntimeWorkflow rw, SqlClient tx) {
        if (!ForkJoinCoordinator.isBranchTerminal(branch.status())) {
            return Future.succeededFuture();
        }
        return forkJoin.evaluateJoinForRecoveredBranch(inst, branch, rw, tx);
    }

    @Override
    public void validateRecoveryPlan(WorkflowInstance inst, RuntimeWorkflow rw) {
        WorkflowPayloads.requirePlanHashMatchesForRecovery(inst, rw);
    }
}
