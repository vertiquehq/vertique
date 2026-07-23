// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Package-private collaborator owning the workflow-signal delivery paths.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C6). It absorbs:
 * <ul>
 *   <li>{@link #applyBranchSignal} — branch-scoped signal resumption: clears the branch wait, runs
 *       the {@link WaitSignalNode} state updater, drives the branch forward via
 *       {@link BranchTransitionEngine#driveBranchTransitions}, and triggers
 *       {@link ForkJoinCoordinator#evaluateJoin} when the branch terminates.</li>
 *   <li>{@link #doApplySignal} — instance-level signal application: validates the waiting state,
 *       cancels any signal-timeout timer, coerces the payload, runs the state updater, persists the
 *       optimistic-version update plus a {@code SIGNAL_RECEIVED} history entry, and drives transitions
 *       via {@link WorkflowTransitionDriver#driveTransitions}.</li>
 *   <li>{@link #findWaitSignalNode} — the static {@link WaitSignalNode} lookup helper.</li>
 * </ul>
 *
 * <p>The public {@code signal(...)} transaction entrypoints stay on {@link WorkflowEngine}; they
 * open the transaction via the runner and delegate their bodies to {@link #applyBranchSignal} and
 * {@link #doApplySignal} on this handler.
 *
 * <p><b>No Dagger cycle.</b> This handler is injected eagerly by the engine facade. It depends on the
 * {@link WorkflowTransitionDriver} (for the post-signal continuation), the {@link ForkJoinCoordinator}
 * (for the branch-join continuation), and {@link TimerLifecycleService} (for the canonical
 * {@code appendTimerCancelledHistory}), all injected eagerly. None of those reference this handler,
 * so no constructor cycle forms — the existing driver↔fork/join cycle is already broken by a
 * {@code Provider} on the coordinator side, and the driver↔timer cycle by a {@code Provider} on the
 * driver side.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class SignalHandler {

    // --- Dependencies ---

    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final BranchTokenRepository<SqlClient> branchTokens;
    private final TimerStore<SqlClient> timerStore;
    private final BranchTransitionEngine branchEngine;
    private final ForkJoinCoordinator forkJoin;
    private final WorkflowTransitionDriver driver;
    private final TimerLifecycleService timerLifecycle;
    private final Clock clock;

    /**
     * Constructs a new signal handler.
     *
     * @param registry the workflow registry used to resolve the pinned {@link RuntimeWorkflow}
     * @param instances the instance repository used for optimistic-concurrency state transitions
     * @param history the history repository used for sequence numbers and history entries
     * @param branchTokens the branch-token repository used by the branch-signal path; may be
     *     {@code null} in legacy test constructors that omit the fork/join collaborators
     * @param timerStore the timer store used to cancel signal-timeout timers
     * @param branchEngine the branch transition engine driven forward after a branch signal; may be
     *     {@code null} in legacy test constructors that omit the fork/join collaborators
     * @param forkJoin the fork/join coordinator whose {@code evaluateJoin} is invoked when a branch
     *     terminates after a signal
     * @param driver the transition driver invoked as the post-signal continuation
     * @param timerLifecycle the timer-lifecycle service owning the canonical
     *     {@code appendTimerCancelledHistory} used when a signal arrives before its timeout timer
     * @param clock the clock used to timestamp history entries and state updates
     */
    @Inject
    SignalHandler(
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            BranchTokenRepository<SqlClient> branchTokens,
            TimerStore<SqlClient> timerStore,
            BranchTransitionEngine branchEngine,
            ForkJoinCoordinator forkJoin,
            WorkflowTransitionDriver driver,
            TimerLifecycleService timerLifecycle,
            Clock clock) {
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.branchTokens = branchTokens;
        this.timerStore = timerStore;
        this.branchEngine = branchEngine;
        this.forkJoin = forkJoin;
        this.driver = driver;
        this.timerLifecycle = timerLifecycle;
        this.clock = clock;
    }

    // --- Branch-scoped signal ---

    /**
     * Applies a signal payload to the branch's resumption — clears the wait, runs the node's
     * state updater with the parent instance state, drives the branch forward, and triggers
     * {@link ForkJoinCoordinator#evaluateJoin} when the branch terminates.
     *
     * @param inst the parent workflow instance whose state the updater mutates
     * @param waiting the branch token currently waiting for the signal
     * @param rw the resolved runtime workflow
     * @param signalName the name of the signal being delivered
     * @param payload the incoming signal payload; coerced to the declared type
     * @param tx the active transaction
     * @param effectiveBaseOverride when non-null, the already-computed effective durable context
     *     document ({@code explicitCarrier.merge(instance.metadata(), MergePolicy.CALLER_WINS)},
     *     PRD-WF-007 Contract Appendix C3) to bind for the branch drive instead of the branch
     *     token's own {@code metadata()}; {@code null} preserves today's behavior
     * @return a {@link Future} that completes when the branch resumption (and any join) is applied
     */
    Future<Void> applyBranchSignal(
            WorkflowInstance inst,
            BranchToken waiting,
            RuntimeWorkflow rw,
            String signalName,
            Object payload,
            SqlClient tx,
            @Nullable DurableMetadata effectiveBaseOverride) {
        WorkflowNode node = rw.nodeById().get(waiting.currentStepId());
        if (!(node instanceof WaitSignalNode wsn) || !signalName.equals(wsn.signalName())) {
            return Future.failedFuture(new WorkflowDefinitionException("Branch '" + waiting.branchId()
                    + "' currentStepId '" + waiting.currentStepId() + "' is not a WaitSignalNode for signal '"
                    + signalName + "'"));
        }
        // Coerce the payload to the declared type.
        Class<?> payloadClass = rw.signalPayloadTypes().get(signalName);
        Object coercedPayload = WorkflowPayloads.coercePayload(payload, payloadClass);
        // Run the state updater on the parent instance state. (Branch-local state is recorded in
        // result_json at branch completion; mid-branch state-mutation is propagated through the
        // parent instance state for V1.)
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
        Object newState;
        try {
            @SuppressWarnings("unchecked")
            var updater = (BiFunction<Object, Object, Object>)
                    (BiFunction<?, ?, ?>) rw.callbacks().stateUpdater(wsn.stateUpdaterCallbackId());
            newState = updater.apply(stateObj, coercedPayload);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException("stateUpdater threw for branch '"
                    + waiting.branchId() + "' signal '" + signalName + "': " + e.getMessage()));
        }
        // Lock-order rule: timers → branch token → instance.
        // If the branch has a signal-timeout timer (wait_aux_id set), cancel it FIRST before
        // touching the branch token — timers must be locked before branch tokens.
        UUID timeoutTimerId = waiting.waitAuxId();
        Future<Void> cancelTimeout = Future.succeededFuture();
        if (timeoutTimerId != null) {
            cancelTimeout = timerStore
                    .markCancelled(timeoutTimerId, clock.instant(), tx)
                    .mapEmpty();
        }

        // Clear the branch's wait and advance to nextStepId.
        BranchToken cleared = waiting.withClearedWait(BranchStatus.RUNNING, wsn.nextStepId(), clock.instant());
        // Branch token update second (lock order: timers → branch token → instance).
        return cancelTimeout
                .compose(v -> branchTokens.updateOptimistic(cleared, waiting.version(), tx))
                .compose(rc -> {
                    if (rc == 0) {
                        return Future.<Void>failedFuture(new WorkflowConflictException(
                                "Optimistic concurrency conflict on branch token during signal resumption"));
                    }
                    // Instance update second (lock order: branch token → instance).
                    long instVersion = inst.version();
                    WorkflowInstance updatedInst = inst.withVersion(instVersion + 1)
                            .withState(Json.encode(newState))
                            .withUpdatedAt(clock.instant());
                    return instances
                            .updateOptimistic(updatedInst, instVersion, tx)
                            .compose(rowCount -> {
                                if (rowCount == 0) {
                                    return Future.<Void>failedFuture(
                                            new WorkflowConflictException(
                                                    "Optimistic concurrency conflict on instance during branch signal application"));
                                }
                                String parentSubjectVersionForBranch = updatedInst.subjectRef() == null
                                        ? null
                                        : updatedInst.subjectRef().version();
                                return branchEngine
                                        .driveBranchTransitions(
                                                cleared,
                                                updatedInst.stateJson(),
                                                parentSubjectVersionForBranch,
                                                rw,
                                                tx,
                                                effectiveBaseOverride)
                                        .compose(advanced -> {
                                            if (ForkJoinCoordinator.isBranchTerminal(advanced.status())) {
                                                WorkflowNode forkPlanNode =
                                                        rw.nodeById().get(advanced.forkStepId());
                                                if (!(forkPlanNode
                                                        instanceof dev.vertique.workflow.plan.ForkNode fork)) {
                                                    return Future.<Void>failedFuture(new WorkflowDefinitionException(
                                                            "Branch references fork '" + advanced.forkStepId()
                                                                    + "' which is not a ForkNode"));
                                                }
                                                WorkflowNode joinPlanNode =
                                                        rw.nodeById().get(fork.joinStepId());
                                                if (!(joinPlanNode
                                                        instanceof dev.vertique.workflow.plan.JoinNode joinNode)) {
                                                    return Future.<Void>failedFuture(new WorkflowDefinitionException(
                                                            "ForkNode '" + fork.stepId() + "' references joinStepId '"
                                                                    + fork.joinStepId() + "' which is not a JoinNode"));
                                                }
                                                return forkJoin.evaluateJoin(
                                                        updatedInst, fork, joinNode, advanced, rw, tx);
                                            }
                                            return Future.<Void>succeededFuture();
                                        });
                            });
                });
    }

    // --- Instance-level signal ---

    /**
     * Applies a validated signal to the given instance after dedup check. Resolves the pinned
     * runtime workflow, validates the waiting state, coerces the payload, updates state, persists,
     * and drives transitions.
     *
     * @param inst the locked instance
     * @param ctx the bundled signal name, payload, and dedup key
     * @param tx the active transaction
     * @return a {@link Future} that completes when the signal is fully processed
     */
    Future<Void> doApplySignal(WorkflowInstance inst, SignalDeliveryContext ctx, SqlClient tx) {
        RuntimeWorkflow rw;
        try {
            rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
        } catch (WorkflowVersionPinUnavailableException e) {
            // Enrich with the actual instance id.
            return Future.failedFuture(
                    new WorkflowVersionPinUnavailableException(e.definitionId(), e.version(), inst.id()));
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        try {
            WorkflowPayloads.requirePlanHashMatches(inst, rw);
        } catch (WorkflowPlanHashDriftException e) {
            return Future.failedFuture(e);
        }

        // Validate instance is in WAITING status for this signal.
        if (inst.status() != WorkflowStatus.WAITING
                || inst.waitType() != WaitType.SIGNAL
                || !ctx.signalName().equals(inst.waitKey())) {
            return Future.failedFuture(new WorkflowSignalRejectedException(
                    "Instance '" + inst.id().value() + "' cannot receive signal '" + ctx.signalName()
                            + "': status=" + inst.status() + ", waitType=" + inst.waitType()
                            + ", waitKey=" + inst.waitKey()));
        }

        // Locate the WaitSignalNode for the current step.
        WaitSignalNode waitNode = findWaitSignalNode(rw, inst.currentStepId(), ctx.signalName());
        if (waitNode == null) {
            return Future.failedFuture(new WorkflowDefinitionException("No WaitSignalNode for step '"
                    + inst.currentStepId() + "' / signal '" + ctx.signalName() + "' in plan '"
                    + inst.definitionId() + "'"));
        }

        // Cancel the timeout timer if one was scheduled alongside the signal wait.
        UUID timeoutTimerId = inst.waitAuxId();
        Future<Void> cancelTimerFuture = Future.succeededFuture();
        if (timeoutTimerId != null) {
            cancelTimerFuture = timerStore
                    .markCancelled(timeoutTimerId, clock.instant(), tx)
                    .compose(transition -> timerLifecycle.appendTimerCancelledHistory(
                            inst, timeoutTimerId, TimerCancelledCause.SIGNAL_ARRIVED, tx));
        }

        // Coerce payload to the declared signal payload type.
        Class<?> payloadClass = rw.signalPayloadTypes().get(ctx.signalName());
        Object coercedPayload = WorkflowPayloads.coercePayload(ctx.payload(), payloadClass);

        // Decode current state.
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());

        // Apply state updater.
        Object newState;
        try {
            @SuppressWarnings("unchecked")
            var updater = (BiFunction<Object, Object, Object>)
                    (BiFunction<?, ?, ?>) rw.callbacks().stateUpdater(waitNode.stateUpdaterCallbackId());
            newState = updater.apply(stateObj, coercedPayload);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "stateUpdater threw for signal '" + ctx.signalName() + "': " + e.getMessage()));
        }

        long prevVersion = inst.version();
        Object finalNewState = newState;
        WorkflowInstance updated = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.RUNNING)
                .withCurrentStepId(waitNode.nextStepId())
                .withWait(null, null)
                .withState(Json.encode(finalNewState))
                .withError(null, null)
                .withUpdatedAt(clock.instant());

        return cancelTimerFuture
                .compose(v -> instances.updateOptimistic(updated, prevVersion, tx))
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        return Future.<Void>failedFuture(
                                new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                        + inst.id().value() + "' at version " + prevVersion));
                    }
                    // Dedup row was already inserted by claimOrResolveSignal at the top of signal();
                    // no follow-up insertSignal call needed.
                    return Future.<Void>succeededFuture();
                })
                .compose(v -> history.nextSequence(inst.id(), tx))
                .compose(seq -> {
                    String histPayload = Json.encode(
                            new SignalReceivedHistoryPayload(ctx.signalName(), ctx.dedupKey(), coercedPayload));
                    return history.append(
                            new WorkflowHistoryEntry(
                                    inst.id(), seq, WorkflowEntryType.SIGNAL_RECEIVED, histPayload, clock.instant()),
                            tx);
                })
                .compose(v -> driver.driveTransitions(updated, rw, tx));
    }

    // --- Internal helpers ---

    /**
     * Finds the {@link WaitSignalNode} for the given step id and signal name using the O(1)
     * {@link RuntimeWorkflow#nodeById()} map.
     *
     * @param rw the resolved runtime workflow
     * @param stepId the current step id
     * @param signalName the expected signal name
     * @return the matching node, or {@code null} if not found or the node is not a WaitSignalNode
     *     with the given signal name
     */
    private static WaitSignalNode findWaitSignalNode(RuntimeWorkflow rw, String stepId, String signalName) {
        WorkflowNode n = rw.nodeById().get(stepId);
        if (n instanceof WaitSignalNode wsn && wsn.signalName().equals(signalName)) {
            return wsn;
        }
        return null;
    }
}
