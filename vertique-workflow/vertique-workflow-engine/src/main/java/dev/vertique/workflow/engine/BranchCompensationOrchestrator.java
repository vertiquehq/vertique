// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Branch-scoped compensation orchestrator (PRD-WF-002 §7.5 / §A.5.1).
 *
 * <p>When an {@code ALL_REQUIRED} fork takes the failure route, branches that completed forward
 * dispatches with {@code compensationStepId} declarations need to be compensated in reverse
 * declaration order across siblings, and within each branch in reverse logical order
 * (FR-WF-PAR-064/065).
 *
 * <p>For each branch this helper:
 * <ol>
 *   <li>Walks the parent instance's history to find {@code SIDE_EFFECT_RECORDED} entries scoped
 *       to that branch (via {@code branchTokenId}) whose corresponding {@link ServiceDispatchNode}
 *       declares a compensation step.</li>
 *   <li>Routes a compensating side-effect intent for each, in reverse arrival order
 *       (FR-WF-PAR-064).</li>
 *   <li>Appends {@code BRANCH_COMPENSATING_START} / {@code BRANCH_COMPENSATING_STEP} /
 *       {@code BRANCH_COMPENSATED} history entries.</li>
 * </ol>
 *
 * <p>Branches that did not record a successful forward intent are skipped (FR-WF-PAR-063). Race
 * joins are guarded against this orchestrator by the validator (no compensable steps allowed in
 * race branches; FR-WF-PAR-054).
 */
@Singleton
final class BranchCompensationOrchestrator {

    private final WorkflowHistoryRepository<SqlClient> history;
    private final RecorderRouter recorders;
    private final Clock clock;

    @Inject
    BranchCompensationOrchestrator(
            WorkflowHistoryRepository<SqlClient> history, RecorderRouter recorders, Clock clock) {
        this.history = history;
        this.recorders = recorders;
        this.clock = clock;
    }

    /**
     * Runs branch compensation for the failed fork group (PRD-WF-002 §7.5).
     *
     * @param inst the parent workflow instance
     * @param fork the failed fork node (used to enumerate branches in declaration order)
     * @param branches the current branch tokens for this fork (status snapshot)
     * @param rw the runtime workflow (for callback resolution and node lookup)
     * @param tx the active transaction
     * @return a {@link Future} that completes when every eligible branch has been compensated
     *     (or skipped because it had no successful forward intents)
     */
    Future<Void> compensateFailedFork(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            List<BranchToken> branches,
            RuntimeWorkflow rw,
            SqlClient tx) {
        return history.listByInstance(inst.id(), tx).compose(allEntries -> {
            // Reverse declaration order across sibling branches (FR-WF-PAR-065).
            List<dev.vertique.workflow.plan.BranchStart> reversed = new ArrayList<>(fork.branches());
            Collections.reverse(reversed);
            Future<Void> chain = Future.succeededFuture();
            for (var bs : reversed) {
                BranchToken branchToken = branches.stream()
                        .filter(b -> b.branchId().equals(bs.branchId()))
                        .findFirst()
                        .orElse(null);
                if (branchToken == null) continue;
                // Codex review fix M3: SUPERSEDED branches don't reach here (race policies
                // forbid compensable steps via the validator). Other terminal statuses might
                // have committed forward intents before failing — let compensateBranch's
                // history walk filter on `SIDE_EFFECT_RECORDED` entries scoped by branchTokenId
                // so we compensate any committed forward intent regardless of the branch's
                // final terminal status (FR-WF-PAR-063 explicitly allows this).
                if (branchToken.status() == BranchStatus.SUPERSEDED) continue;
                chain = chain.compose(v -> compensateBranch(inst, branchToken, allEntries, rw, tx));
            }
            return chain;
        });
    }

    private Future<Void> compensateBranch(
            WorkflowInstance inst,
            BranchToken branchToken,
            List<WorkflowHistoryEntry> allEntries,
            RuntimeWorkflow rw,
            SqlClient tx) {
        // Collect this branch's dispatches in reverse arrival order (FR-WF-PAR-064).
        List<SideEffectRecordedHistoryPayload> branchDispatches = new ArrayList<>();
        for (WorkflowHistoryEntry e : allEntries) {
            if (e.entryType() != WorkflowEntryType.SIDE_EFFECT_RECORDED) continue;
            SideEffectRecordedHistoryPayload p =
                    Json.decodeValue(e.payloadJson(), SideEffectRecordedHistoryPayload.class);
            if (p.branchTokenId() == null || !p.branchTokenId().equals(branchToken.id())) {
                continue;
            }
            var node = rw.nodeById().get(p.stepId());
            if (node instanceof ServiceDispatchNode sdn && sdn.compensationStepId() != null) {
                branchDispatches.add(p);
            }
        }
        if (branchDispatches.isEmpty()) {
            return Future.succeededFuture();
        }
        Collections.reverse(branchDispatches);

        return appendBranchHistory(inst, branchToken, WorkflowEntryType.BRANCH_COMPENSATING_START, tx)
                .compose(v -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (var p : branchDispatches) {
                        chain = chain.compose(vv -> emitOneCompensation(inst, branchToken, p, rw, tx));
                    }
                    return chain;
                })
                .compose(v -> appendBranchHistory(inst, branchToken, WorkflowEntryType.BRANCH_COMPENSATED, tx));
    }

    private Future<Void> emitOneCompensation(
            WorkflowInstance inst,
            BranchToken branchToken,
            SideEffectRecordedHistoryPayload forwardPayload,
            RuntimeWorkflow rw,
            SqlClient tx) {
        var node = rw.nodeById().get(forwardPayload.stepId());
        if (!(node instanceof ServiceDispatchNode sdn) || sdn.compensationStepId() == null) {
            return Future.succeededFuture();
        }
        var compNode = rw.nodeById().get(sdn.compensationStepId());
        if (!(compNode instanceof dev.vertique.workflow.plan.CompensationNode cn)) {
            return Future.succeededFuture();
        }
        // Build the compensation payload from the parent state. Branch-local payload factories are
        // out of scope until branches carry their own state slice.
        Object stateObj = Json.decodeValue(io.vertx.core.buffer.Buffer.buffer(inst.stateJson()), rw.stateType());
        Object payload;
        try {
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<Object, Object>)
                    (java.util.function.Function<?, ?>) rw.callbacks().payloadFactory(cn.payloadCallbackId());
            payload = factory.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new dev.vertique.workflow.exception.WorkflowDefinitionException(
                    "compensation payloadFactory threw for branch '" + branchToken.branchId() + "' step '" + cn.stepId()
                            + "': " + e.getMessage()));
        }
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.SERVICE,
                    cn.targetId(),
                    payload,
                    java.util.Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            inst.id(),
                            seq,
                            inst.definitionId(),
                            cn.stepId(),
                            branchToken.id(),
                            branchToken.forkStepId(),
                            branchToken.branchId()));
            return recorders
                    .route(intent, tx)
                    .compose(r -> {
                        JsonObject hp = new JsonObject()
                                .put("branchTokenId", branchToken.id().toString())
                                .put("forkStepId", branchToken.forkStepId())
                                .put("branchId", branchToken.branchId())
                                .put("forwardStepId", sdn.stepId())
                                .put("compensationStepId", cn.stepId());
                        return history.append(
                                new WorkflowHistoryEntry(
                                        inst.id(),
                                        seq,
                                        WorkflowEntryType.BRANCH_COMPENSATING_STEP,
                                        hp.encode(),
                                        clock.instant()),
                                tx);
                    })
                    .mapEmpty();
        });
    }

    private Future<Void> appendBranchHistory(
            WorkflowInstance inst, BranchToken branchToken, WorkflowEntryType entryType, SqlClient tx) {
        JsonObject payload = new JsonObject()
                .put("branchTokenId", branchToken.id().toString())
                .put("forkStepId", branchToken.forkStepId())
                .put("branchId", branchToken.branchId());
        return history.nextSequence(inst.id(), tx)
                .compose(seq -> history.append(
                        new WorkflowHistoryEntry(inst.id(), seq, entryType, payload.encode(), clock.instant()), tx))
                .mapEmpty();
    }

    @SuppressWarnings("unused")
    private Instant now() {
        return clock.instant();
    }
}
