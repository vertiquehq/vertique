// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.executor.ClaimLostException;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.DeadlineGuard;
import dev.vertique.workflow.engine.executor.EndedInstanceOutcomes;
import dev.vertique.workflow.engine.executor.ParkedSignals;
import dev.vertique.workflow.engine.executor.Savepoints;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.history.DecisionLog;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The public {@code signal(...)} entrypoints on {@link WorkflowEngine} only record a {@code SIGNAL}
 * command. This handler runs inside an executor segment: {@link #runSegment} decides what becomes of a
 * claimed signal command (apply, park, reject, stale) and applies it under a savepoint through
 * {@link #doApplySignal} or {@link #applyBranchSignal}; the parked signals of the waits a segment
 * reached are applied by {@link #parkedSignals()}.
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

    private static final Logger log = LoggerFactory.getLogger(SignalHandler.class);

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
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowContextBinder contextBinder;
    private final WorkflowHistoryRecorder historyRecorder;
    private final DeadlineGuard deadlineGuard;
    private final ParkedSignals parkedSignals;
    private final DecisionLog decisions;
    private final RecorderRouter recorderRouter;

    /** Deletes the record of a wait the handler leaves; {@code null} in legacy test constructors. */
    @Nullable
    private final SignalWaits signalWaits;

    /**
     * Constructs a signal handler that deletes no wait record, for tests that never reach a wait.
     *
     * @param registry the workflow registry used to resolve the pinned {@link RuntimeWorkflow}
     * @param instances the instance repository
     * @param history the history repository
     * @param branchTokens the branch-token repository; may be {@code null}
     * @param timerStore the timer store
     * @param branchEngine the branch transition engine; may be {@code null}
     * @param forkJoin the fork/join coordinator
     * @param driver the transition driver
     * @param timerLifecycle the timer-lifecycle service
     * @param clock the clock
     * @param commands the command repository
     * @param contextBinder the binder that installs the durable context of a drive
     * @param historyRecorder the recorder of the history entries of command outcomes
     * @param recorderRouter the router that attributes the intents of a drained parked signal; may be {@code null}
     */
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
            Clock clock,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowContextBinder contextBinder,
            WorkflowHistoryRecorder historyRecorder,
            RecorderRouter recorderRouter) {
        this(
                registry,
                instances,
                history,
                branchTokens,
                timerStore,
                branchEngine,
                forkJoin,
                driver,
                timerLifecycle,
                clock,
                commands,
                contextBinder,
                historyRecorder,
                recorderRouter,
                null);
    }

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
     * @param commands the command repository the signal segments settle their commands through
     * @param contextBinder the binder that installs the durable context of a drive
     * @param historyRecorder the recorder of the history entries of command outcomes
     * @param recorderRouter the router that attributes the intents of a drained parked signal to that signal's command;
     *     may be {@code null} in tests that do not route intents
     * @param signalWaits deletes the record of the wait a signal leaves, in the transaction that applies it; may be
     *     {@code null} in legacy test constructors
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
            Clock clock,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowContextBinder contextBinder,
            WorkflowHistoryRecorder historyRecorder,
            RecorderRouter recorderRouter,
            @Nullable SignalWaits signalWaits) {
        this.signalWaits = signalWaits;
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
        this.commands = commands;
        this.contextBinder = contextBinder;
        this.historyRecorder = historyRecorder;
        this.recorderRouter = recorderRouter;
        this.decisions = historyRecorder == null ? null : historyRecorder.decisions();
        this.deadlineGuard = new DeadlineGuard(commands);
        this.parkedSignals = new ParkedSignals(commands, deadlineGuard, new ParkedHost());
        if (driver != null) {
            driver.attachParkedSignals(parkedSignals);
        }
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
        return applyBranchSignal(inst, waiting, rw, signalName, payload, tx, effectiveBaseOverride, 0L);
    }

    /**
     * Applies a recorded signal to a waiting branch, binding the branch's durable context as an executor drive: the
     * identity-snapshot namespace is stripped from the bound document and the drive is refused when a security context
     * or a deferred-execution origin is already bound.
     *
     * @param inst the parent workflow instance whose state the updater mutates
     * @param waiting the branch token currently waiting for the signal
     * @param rw the resolved runtime workflow
     * @param signalName the name of the signal being delivered
     * @param payload the incoming signal payload; coerced to the declared type
     * @param tx the active transaction
     * @param effectiveBaseOverride the merged explicit carrier and instance fill to bind instead of the branch token's
     *     own carrier, or {@code null}
     * @param commandId the id of the signal command, for the failure of a refused bind
     * @return a {@link Future} that completes when the branch resumption (and any join) is applied
     */
    Future<Void> applyBranchSignal(
            WorkflowInstance inst,
            BranchToken waiting,
            RuntimeWorkflow rw,
            String signalName,
            Object payload,
            SqlClient tx,
            @Nullable DurableMetadata effectiveBaseOverride,
            long commandId) {
        WorkflowNode node = rw.nodeById().get(waiting.currentStepId());
        if (!(node instanceof WaitSignalNode wsn) || !signalName.equals(wsn.signalName())) {
            return Future.failedFuture(new WorkflowDefinitionException("Branch '" + waiting.branchId()
                    + "' currentStepId '" + waiting.currentStepId() + "' is not a WaitSignalNode for signal '"
                    + EchoedNames.bounded(signalName) + "'"));
        }
        // Coerce the payload to the declared type.
        Class<?> payloadClass = rw.signalPayloadTypes().get(signalName);
        Object coercedPayload;
        try {
            coercedPayload = coerce(payload, payloadClass, inst, signalName);
        } catch (WorkflowInvalidPayloadException e) {
            return Future.failedFuture(e);
        }
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
                                return decisions
                                        .record(inst, updatedInst, waiting.currentStepId(), "signal-applied", tx)
                                        // The wait is left: its record goes before the drive, which may enter a wait
                                        // again.
                                        .compose(logged -> closeWait(
                                                inst.id(),
                                                waiting.currentStepId(),
                                                waiting.forkStepId(),
                                                waiting.branchId(),
                                                commandId > 0L ? commandId : null,
                                                tx))
                                        .compose(closed -> branchEngine
                                                .driveExecutorBranchTransitions(
                                                        cleared,
                                                        updatedInst.stateJson(),
                                                        parentSubjectVersionForBranch,
                                                        rw,
                                                        BranchTransitionEngine.InstanceScope.of(updatedInst),
                                                        tx,
                                                        effectiveBaseOverride,
                                                        commandId)
                                                .compose(advanced -> {
                                                    if (ForkJoinCoordinator.isBranchTerminal(advanced.status())) {
                                                        WorkflowNode forkPlanNode =
                                                                rw.nodeById().get(advanced.forkStepId());
                                                        if (!(forkPlanNode
                                                                instanceof dev.vertique.workflow.plan.ForkNode fork)) {
                                                            return Future.<Void>failedFuture(
                                                                    new WorkflowDefinitionException(
                                                                            "Branch references fork '"
                                                                                    + advanced.forkStepId()
                                                                                    + "' which is not a ForkNode"));
                                                        }
                                                        WorkflowNode joinPlanNode =
                                                                rw.nodeById().get(fork.joinStepId());
                                                        if (!(joinPlanNode
                                                                instanceof
                                                                dev.vertique.workflow.plan.JoinNode joinNode)) {
                                                            return Future.<Void>failedFuture(
                                                                    new WorkflowDefinitionException(
                                                                            "ForkNode '" + fork.stepId()
                                                                                    + "' references joinStepId '"
                                                                                    + fork.joinStepId()
                                                                                    + "' which is not a JoinNode"));
                                                        }
                                                        return forkJoin.evaluateJoin(
                                                                updatedInst, fork, joinNode, advanced, rw, tx);
                                                    }
                                                    return Future.<Void>succeededFuture();
                                                }));
                            });
                });
    }

    // --- Instance-level signal ---

    /**
     * Applies a validated signal to the given instance after dedup check. Resolves the pinned
     * runtime workflow, refuses a caller of another contract and a signal the pinned plan never waits
     * for, validates the waiting state, coerces the payload, updates state, persists, and drives
     * transitions.
     *
     * @param inst the locked instance
     * @param ctx the bundled signal name, payload, and dedup key
     * @param tx the active transaction
     * @return a {@link Future} that completes when the signal is fully processed
     */
    Future<Void> doApplySignal(WorkflowInstance inst, SignalDeliveryContext ctx, SqlClient tx) {
        RuntimeWorkflow rw;
        try {
            rw = PinnedPlans.resolve(registry, inst);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        try {
            WorkflowPayloads.requirePlanHashMatches(inst, rw);
        } catch (WorkflowPlanHashDriftException e) {
            return Future.failedFuture(e);
        }

        // A client of another contract than the pinned definition's is refused before anything else.
        WorkflowDefinitionContractMismatchException contractRefusal =
                ContractMismatch.refusal(inst, rw, ctx.callerContractName(), "signal");
        if (contractRefusal != null) {
            return Future.failedFuture(contractRefusal);
        }

        // A signal the pinned plan never waits for is refused naming the pinned version.
        if (!waitsForSignal(rw, ctx.signalName())) {
            return Future.failedFuture(
                    new WorkflowSignalRejectedException("Instance '" + inst.id().value()
                            + "' cannot receive signal '" + EchoedNames.bounded(ctx.signalName()) + "': '"
                            + rw.plan().definitionId() + "' v"
                            + rw.plan().definitionVersion() + " does not wait for signal '"
                            + EchoedNames.bounded(ctx.signalName()) + "'"));
        }

        // Validate instance is in WAITING status for this signal.
        if (inst.status() != WorkflowStatus.WAITING
                || inst.waitType() != WaitType.SIGNAL
                || !ctx.signalName().equals(inst.waitKey())) {
            return Future.failedFuture(
                    new WorkflowSignalRejectedException("Instance '" + inst.id().value() + "' cannot receive signal '"
                            + EchoedNames.bounded(ctx.signalName()) + "': status=" + inst.status() + ", waitType="
                            + inst.waitType()
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
        Object coercedPayload;
        try {
            coercedPayload = coerce(ctx.payload(), payloadClass, inst, ctx.signalName());
        } catch (WorkflowInvalidPayloadException e) {
            return Future.failedFuture(e);
        }

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
                    return decisions.record(inst, updated, inst.currentStepId(), "signal-applied", tx);
                })
                .compose(v -> history.nextSequence(inst.id(), tx))
                .compose(seq -> {
                    DefinitionKey definition =
                            new DefinitionKey(inst.definitionScopeKey(), inst.definitionId(), inst.definitionVersion());
                    String histPayload = Json.encode(new SignalReceivedHistoryPayload(
                            ctx.signalName(),
                            ctx.dedupKey(),
                            decisions.redactedCopy(definition, coercedPayload, tx),
                            ctx.commandId()));
                    return history.append(
                            new WorkflowHistoryEntry(
                                    inst.id(), seq, WorkflowEntryType.SIGNAL_RECEIVED, histPayload, clock.instant()),
                            tx);
                })
                // The wait is left: its record goes before the drive, which may enter the same wait again.
                .compose(v -> closeWait(inst.id(), inst.currentStepId(), null, null, ctx.commandId(), tx))
                .compose(v -> driver.driveTransitions(updated, rw, tx));
    }

    /**
     * Deletes the record of the wait a signal leaves, which settles the other signals parked on that entry as stale.
     * Nothing is deleted where no wait record collaborator is present.
     *
     * @param applyingCommandId the command whose application leaves the wait, which is not settled, or {@code null}
     */
    private Future<Void> closeWait(
            WorkflowInstanceId instance,
            String stepId,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable Long applyingCommandId,
            SqlClient tx) {
        if (signalWaits == null) {
            return Future.succeededFuture();
        }
        return signalWaits
                .close(instance, stepId, forkStepId, branchId, applyingCommandId, tx)
                .mapEmpty();
    }

    // --- Recorded signals in a segment ---

    /**
     * The application of parked signals at the waits a segment has reached.
     *
     * @return the parked-signal application
     */
    ParkedSignals parkedSignals() {
        return parkedSignals;
    }

    /**
     * Runs the work of a claimed {@code SIGNAL} command inside the segment's transaction and settles it, except when
     * its application succeeds: then the executor completes it.
     *
     * <ul>
     *   <li>The instance has ended: the command is rejected, or stale when it addresses a branch.</li>
     *   <li>The pinned plan never waits for the name: the command is rejected.</li>
     *   <li>The instance is not at a wait that accepts the signal, is {@code FAILED}, or the signal is
     *       deadline-blocked there: the command is parked and the attempt its claim spent is restored.</li>
     *   <li>A branch-addressed signal whose branch was superseded or ended is stale.</li>
     *   <li>Otherwise the signal is applied under a savepoint. A deterministic failure or a step-limit overrun rolls
     *       back to the savepoint and the command is rejected; the instance is unchanged. After a success the parked
     *       signals of the waits the instance has reached are applied.</li>
     * </ul>
     *
     * @param head the peeked head the claim came from
     * @param claim the claim
     * @param command the fenced command
     * @param tx the segment's transaction handle
     * @return the outcome the executor acts on
     */
    Future<WorkflowSegments.Outcome> runSegment(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        SignalTarget signal;
        try {
            signal = SignalTarget.of(command);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return instances.findById(command.workflowId(), tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.<WorkflowSegments.Outcome>failedFuture(
                        new WorkflowInstanceNotFoundException(command.workflowId()));
            }
            WorkflowInstance inst = found.get();
            if (EndedInstanceOutcomes.isEnded(inst.status())) {
                return metInstance(claim, command, signal, inst, false, tx);
            }
            if (!inst.definitionScopeKey().equals(head.definitionScopeKey())
                    || !inst.definitionId().equals(head.definitionId())
                    || inst.definitionVersion() != head.definitionVersion()
                    || !java.util.Objects.equals(inst.planHash(), head.planHash())) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            }
            RuntimeWorkflow rw;
            try {
                rw = PinnedPlans.resolve(registry, inst);
                WorkflowPayloads.requirePlanHashMatches(inst, rw);
            } catch (WorkflowVersionNotRunnableException | WorkflowPlanHashDriftException e) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            if (!waitsForSignal(rw, signal.name())) {
                return reject(
                        claim,
                        command,
                        signal,
                        CommandErrors.signalUndeclared(signal.name(), inst.definitionVersion()),
                        tx);
            }
            return signal.branchAddressed()
                    ? decideBranch(claim, command, signal, inst, rw, tx)
                    : decideInstance(claim, command, signal, inst, rw, tx);
        });
    }

    private Future<WorkflowSegments.Outcome> decideInstance(
            ClaimedCommand claim,
            SegmentCommand command,
            SignalTarget signal,
            WorkflowInstance inst,
            RuntimeWorkflow rw,
            SqlClient tx) {
        // The entry the signal matched is compared before the instance's status: an entry that is not open is stale
        // whether the instance is live or FAILED, and the FAILED parking applies only while the entry is still open.
        return entryOpen(inst, signal, tx).compose(open -> {
            if (!open) {
                return stale(claim, command, signal, CommandErrors.signalWaitEntryClosed(signal.name()), tx);
            }
            if (inst.status() == WorkflowStatus.FAILED) {
                return metInstance(claim, command, signal, inst, false, tx);
            }
            boolean awaited = inst.status() == WorkflowStatus.WAITING
                    && inst.waitType() == WaitType.SIGNAL
                    && signal.name().equals(inst.waitKey());
            if (!awaited) {
                return park(claim, command, signal, tx);
            }
            return deadlineGuard
                    .blocked(inst.id(), inst.currentStepId(), null, command.commandId(), tx)
                    .compose(blocked -> blocked
                            ? park(claim, command, signal, tx)
                            : apply(claim, command, signal, inst, null, rw, tx));
        });
    }

    /**
     * Tells whether the wait entry a signal matched is still open for the instance. A signal that matched no entry has
     * none to compare, and is open as far as this check goes.
     */
    private Future<Boolean> entryOpen(WorkflowInstance inst, SignalTarget signal, SqlClient tx) {
        if (!signal.correlated() || signalWaits == null) {
            return Future.succeededFuture(true);
        }
        return signalWaits.isOpen(inst.id(), signal.waitId(), tx);
    }

    private Future<WorkflowSegments.Outcome> decideBranch(
            ClaimedCommand claim,
            SegmentCommand command,
            SignalTarget signal,
            WorkflowInstance inst,
            RuntimeWorkflow rw,
            SqlClient tx) {
        return branchTokens
                .findByWorkflowAndFork(inst.id(), signal.forkStepId(), tx)
                .compose(tokens -> {
                    Optional<BranchToken> branch = tokens.stream()
                            .filter(token -> signal.branchId().equals(token.branchId()))
                            .findFirst();
                    boolean branchEnded =
                            branch.isPresent() && isFinished(branch.get().status());
                    // The entry the signal matched is compared before the instance's status, as for an instance wait.
                    return entryOpen(inst, signal, tx).compose(open -> {
                        if (!open) {
                            return stale(
                                    claim,
                                    command,
                                    signal,
                                    branchEnded
                                            ? CommandErrors.signalBranchEnded(signal.name())
                                            : CommandErrors.signalWaitEntryClosed(signal.name()),
                                    tx);
                        }
                        if (inst.status() == WorkflowStatus.FAILED) {
                            return metInstance(claim, command, signal, inst, branchEnded, tx);
                        }
                        if (branchEnded) {
                            return stale(claim, command, signal, CommandErrors.signalBranchEnded(signal.name()), tx);
                        }
                        boolean awaited = branch.isPresent()
                                && branch.get().status() == BranchStatus.WAITING
                                && branch.get().waitType() == WaitType.SIGNAL
                                && signal.name().equals(branch.get().waitKey());
                        if (!awaited) {
                            return park(claim, command, signal, tx);
                        }
                        BranchToken waiting = branch.get();
                        return deadlineGuard
                                .blocked(inst.id(), waiting.currentStepId(), signal.branchId(), command.commandId(), tx)
                                .compose(blocked -> blocked
                                        ? park(claim, command, signal, tx)
                                        : apply(claim, command, signal, inst, waiting, rw, tx));
                    });
                });
    }

    /** A branch that can never receive a signal again: superseded, ended, or being compensated. */
    private static boolean isFinished(BranchStatus status) {
        return switch (status) {
            case SUPERSEDED, COMPLETED, FAILED, CANCELLED, EXPIRED, COMPENSATING, COMPENSATED -> true;
            default -> false;
        };
    }

    private Future<WorkflowSegments.Outcome> apply(
            ClaimedCommand claim,
            SegmentCommand command,
            SignalTarget signal,
            WorkflowInstance inst,
            @Nullable BranchToken waiting,
            RuntimeWorkflow rw,
            SqlClient tx) {
        return commands.wasParked(inst.id(), command.commandId(), tx).compose(parkedBefore -> historyRecorder
                .appendCommandReceived(inst, command, tx)
                .compose(
                        received -> Savepoints.attempt(tx, () -> applyRecorded(inst, waiting, command, signal, rw, tx)))
                .compose(attempt -> switch (attempt) {
                    case Savepoints.Attempt.Applied<Void> applied ->
                        // A signal that was parked and set back to pending is applied from the parked state.
                        appliedHistory(command, signal.name(), parkedBefore, tx)
                                .compose(done -> parkedSignals.drain(inst.id(), tx))
                                .map(done -> WorkflowSegments.Outcome.APPLIED);
                    case Savepoints.Attempt.Contained<Void> contained -> {
                        log.warn(
                                "Signal '{}' (command {}) of instance {} failed and was rejected: {}",
                                signal.name(),
                                command.commandId(),
                                inst.id().value(),
                                contained.cause().getClass().getName());
                        String reason = contained.kind() == Savepoints.Containment.STEP_LIMIT
                                ? CommandErrors.signalStepLimit(signal.name(), driver.stepCap(tx))
                                : CommandErrors.signalFailure(signal.name());
                        yield reject(claim, command, signal, reason, tx);
                    }
                }));
    }

    /**
     * Writes the history of an applied signal command: {@code SIGNAL_APPLIED} when it was parked first, then
     * {@code COMMAND_COMPLETED}.
     */
    private Future<Void> appliedHistory(SegmentCommand command, String signalName, boolean parkedBefore, SqlClient tx) {
        Future<Void> signalApplied = parkedBefore
                ? historyRecorder.appendSignalCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.SIGNAL_APPLIED,
                        command.commandId(),
                        signalName,
                        null,
                        tx)
                : Future.succeededFuture();
        return signalApplied.compose(done -> historyRecorder.appendCommandHistory(
                command.workflowId(), WorkflowEntryType.COMMAND_COMPLETED, command.commandId(), command.kind(), tx));
    }

    /**
     * Applies a recorded signal to the instance: state update, history and the drive to the next wait. Runs inside the
     * savepoint of the signal, whether it executes as a command of its own or was parked.
     */
    private Future<Void> applyRecorded(
            WorkflowInstance inst,
            @Nullable BranchToken waiting,
            SegmentCommand command,
            SignalTarget signal,
            RuntimeWorkflow rw,
            SqlClient tx) {
        Object raw;
        DurableMetadata carrier;
        DurableMetadata recorded;
        try {
            raw = command.payloadJson() == null ? null : Json.decodeValue(command.payloadJson());
            carrier = WorkflowContextBinder.decodeCarrier(command.explicitCarrierJson());
            recorded = WorkflowContextBinder.decodeCarrier(command.contextJson());
        } catch (RuntimeException e) {
            return Future.failedFuture(new WorkflowInvalidPayloadException(
                    "The recorded signal '" + EchoedNames.bounded(signal.name()) + "' cannot be read", List.of()));
        }
        // The binder strips the identity snapshot from every source it binds, so no drive binds an identity from the
        // explicit carrier, the recorded context or an instance carrier recorded before the snapshot was dropped.
        if (!signal.branchAddressed()) {
            return contextBinder.withRecordedBase(
                    inst,
                    command.commandId(),
                    carrier,
                    recorded,
                    () -> doApplySignal(
                            inst,
                            new SignalDeliveryContext(
                                    signal.name(), raw, command.dedupKey(), null, command.commandId()),
                            tx));
        }
        if (waiting == null) {
            return Future.failedFuture(
                    new WorkflowSignalRejectedException("Instance '" + inst.id().value()
                            + "' has no branch waiting for signal '" + EchoedNames.bounded(signal.name()) + "'"));
        }
        // A branch drive never binds the recorded context: the explicit carrier with the instance fill, else the
        // branch token's own carrier.
        DurableMetadata effectiveBase = carrier == null
                ? null
                : inst.metadata() == null
                        ? carrier
                        : carrier.merge(inst.metadata(), DurableMetadata.MergePolicy.CALLER_WINS);
        return applyBranchSignal(inst, waiting, rw, signal.name(), raw, tx, effectiveBase, command.commandId());
    }

    /**
     * Settles the command as the outcome table states for a signal that meets an instance that has ended or failed.
     *
     * @param branchEnded whether the branch a branch-addressed signal addresses has ended or was superseded
     */
    private Future<WorkflowSegments.Outcome> metInstance(
            ClaimedCommand claim,
            SegmentCommand command,
            SignalTarget signal,
            WorkflowInstance inst,
            boolean branchEnded,
            SqlClient tx) {
        EndedInstanceOutcomes.Outcome outcome = EndedInstanceOutcomes.of(
                        command.kind(),
                        inst.status().name(),
                        EndedInstanceOutcomes.Target.signal(signal.name(), signal.branchAddressed(), branchEnded))
                .orElseThrow();
        return switch (outcome.settlement()) {
            case REJECTED -> reject(claim, command, signal, outcome.reason(), tx);
            case STALE -> stale(claim, command, signal, outcome.reason(), tx);
            case CANCELLED -> cancel(claim, command, signal, outcome.reason(), tx);
            case PARKED, EXECUTES -> park(claim, command, signal, tx);
        };
    }

    private Future<WorkflowSegments.Outcome> park(
            ClaimedCommand claim, SegmentCommand command, SignalTarget signal, SqlClient tx) {
        return commands.park(command.commandId(), claim.token(), tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendSignalCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.SIGNAL_PARKED,
                        command.commandId(),
                        signal.name(),
                        null,
                        tx))
                .map(done -> WorkflowSegments.Outcome.PARKED);
    }

    private Future<WorkflowSegments.Outcome> reject(
            ClaimedCommand claim, SegmentCommand command, SignalTarget signal, String reason, SqlClient tx) {
        return commands.reject(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendSignalCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_REJECTED,
                        command.commandId(),
                        signal.name(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_REJECTED);
    }

    private Future<WorkflowSegments.Outcome> stale(
            ClaimedCommand claim, SegmentCommand command, SignalTarget signal, String reason, SqlClient tx) {
        log.info(
                "Signal '{}' (command {}) of instance {} settled stale",
                EchoedNames.bounded(signal.name()),
                command.commandId(),
                command.workflowId().value());
        return commands.stale(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendSignalCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_STALE,
                        command.commandId(),
                        signal.name(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_STALE);
    }

    private Future<WorkflowSegments.Outcome> cancel(
            ClaimedCommand claim, SegmentCommand command, SignalTarget signal, String reason, SqlClient tx) {
        return commands.cancel(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendSignalCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_CANCELLED,
                        command.commandId(),
                        signal.name(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_CANCELLED);
    }

    /**
     * Rejects the parked signals the plan an instance was migrated to does not wait for, with their history, in the
     * segment's transaction. The rest stay parked for the drain of the wait the instance reached.
     *
     * @param instance the migrated instance
     * @param target   the plan the instance is pinned to now
     * @param tx       the segment's transaction handle
     * @return a future that completes when the undeclared signals are rejected
     */
    Future<Void> rejectUndeclaredParked(WorkflowInstanceId instance, RuntimeWorkflow target, SqlClient tx) {
        return commands.parkedSignals(instance, tx).compose(parked -> {
            Future<Void> chain = Future.succeededFuture();
            for (var signal : parked) {
                if (waitsForSignal(target, signal.signalName())) {
                    continue;
                }
                String reason = CommandErrors.signalUndeclared(
                        signal.signalName(), target.plan().definitionVersion());
                chain = chain.compose(done -> commands.settleParked(signal.commandId(), "REJECTED", reason, tx))
                        .compose(ClaimLostException::requireRow)
                        .compose(done -> historyRecorder.appendSignalCommandHistory(
                                instance,
                                WorkflowEntryType.COMMAND_REJECTED,
                                signal.commandId(),
                                signal.signalName(),
                                reason,
                                tx));
            }
            return chain;
        });
    }

    /** The engine side of the parked-signal application. */
    private final class ParkedHost implements ParkedSignals.Host {

        @Override
        public Future<ParkedSignals.Waits> waits(WorkflowInstanceId instance, SqlClient tx) {
            return instances.findById(instance, tx).compose(found -> {
                if (found.isEmpty()) {
                    return Future.failedFuture(new WorkflowInstanceNotFoundException(instance));
                }
                WorkflowInstance inst = found.get();
                if (EndedInstanceOutcomes.isEnded(inst.status())) {
                    return Future.succeededFuture(
                            new ParkedSignals.Waits(inst.status().name(), false, List.of()));
                }
                boolean failed = inst.status() == WorkflowStatus.FAILED;
                List<ParkedSignals.WaitAddress> open = new ArrayList<>();
                if (inst.status() == WorkflowStatus.WAITING && inst.waitType() == WaitType.SIGNAL) {
                    open.add(new ParkedSignals.WaitAddress(inst.currentStepId(), inst.waitKey(), null, null, null));
                }
                return branchTokens
                        .findActiveByWorkflow(instance, tx)
                        .compose(tokens -> {
                            for (BranchToken token : tokens) {
                                if (token.status() == BranchStatus.WAITING && token.waitType() == WaitType.SIGNAL) {
                                    open.add(new ParkedSignals.WaitAddress(
                                            token.currentStepId(),
                                            token.waitKey(),
                                            token.forkStepId(),
                                            token.branchId(),
                                            null));
                                }
                            }
                            return withEntryIds(instance, open, tx);
                        })
                        .map(addressed -> new ParkedSignals.Waits(null, failed, addressed));
            });
        }

        /**
         * Gives each open wait the id of its entry: the id the parked signals that matched an entry of it carry, when
         * that entry's record is held and addresses this wait. A wait no parked signal matched keeps no id.
         */
        private Future<List<ParkedSignals.WaitAddress>> withEntryIds(
                WorkflowInstanceId instance, List<ParkedSignals.WaitAddress> open, SqlClient tx) {
            if (signalWaits == null || open.isEmpty()) {
                return Future.succeededFuture(List.copyOf(open));
            }
            return commands.parkedSignals(instance, tx).compose(parked -> {
                List<String> waitIds = parked.stream()
                        .map(ParkedSignal::waitId)
                        .filter(Objects::nonNull)
                        .toList();
                if (waitIds.isEmpty()) {
                    return Future.succeededFuture(List.copyOf(open));
                }
                return signalWaits.openEntries(instance, waitIds, tx).map(entries -> {
                    List<ParkedSignals.WaitAddress> addressed = new ArrayList<>(open.size());
                    for (ParkedSignals.WaitAddress wait : open) {
                        String entryId = entries.entrySet().stream()
                                .filter(entry -> addresses(entry.getValue(), wait))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                        addressed.add(new ParkedSignals.WaitAddress(
                                wait.stepId(), wait.signalName(), wait.forkStepId(), wait.branchId(), entryId));
                    }
                    return List.copyOf(addressed);
                });
            });
        }

        private boolean addresses(SignalWaitRecord record, ParkedSignals.WaitAddress wait) {
            return record.stepId().equals(wait.stepId())
                    && record.forkStepId().equals(wait.forkStepId() != null ? wait.forkStepId() : "")
                    && record.branchId().equals(wait.branchId() != null ? wait.branchId() : "");
        }

        @Override
        public Future<Void> apply(SegmentCommand command, SqlClient tx) {
            SignalTarget signal = SignalTarget.of(command);
            // the intents the signal's handler writes belong to the signal's recorder, not to the segment's command
            return attributedTo(
                    command,
                    tx,
                    () -> decisions.forCommand(tx, command, () -> instances
                            .findById(command.workflowId(), tx)
                            .compose(found -> {
                                if (found.isEmpty()) {
                                    return Future.failedFuture(
                                            new WorkflowInstanceNotFoundException(command.workflowId()));
                                }
                                WorkflowInstance inst = found.get();
                                RuntimeWorkflow rw;
                                try {
                                    rw = PinnedPlans.resolve(registry, inst);
                                    WorkflowPayloads.requirePlanHashMatches(inst, rw);
                                } catch (Exception e) {
                                    // This node cannot run the pinned plan: the whole segment fails rather than
                                    // rejecting a
                                    // signal.
                                    return Future.failedFuture(e);
                                }
                                if (!signal.branchAddressed()) {
                                    return applyRecorded(inst, null, command, signal, rw, tx);
                                }
                                return branchTokens
                                        .findByWaitForBranch(
                                                inst.id(),
                                                signal.forkStepId(),
                                                signal.branchId(),
                                                WaitType.SIGNAL.name(),
                                                signal.name(),
                                                tx)
                                        .compose(waiting ->
                                                applyRecorded(inst, waiting.orElse(null), command, signal, rw, tx));
                            })));
        }

        /** Runs work whose routed intents belong to the command; without a router the work runs as it is. */
        private Future<Void> attributedTo(SegmentCommand command, SqlClient tx, Supplier<Future<Void>> work) {
            return recorderRouter == null ? work.get() : recorderRouter.executing(command, tx, work);
        }

        @Override
        public Future<Void> applied(SegmentCommand command, SqlClient tx) {
            return appliedHistory(command, SignalTarget.of(command).name(), true, tx);
        }

        @Override
        public Future<Void> rejected(
                WorkflowInstanceId instance, long commandId, String signalName, String reason, SqlClient tx) {
            return historyRecorder.appendSignalCommandHistory(
                    instance, WorkflowEntryType.COMMAND_REJECTED, commandId, signalName, reason, tx);
        }

        @Override
        public int stepsUsed(SqlClient tx) {
            return driver.stepsUsed(tx);
        }

        @Override
        public int stepCap(SqlClient tx) {
            return driver.stepCap(tx);
        }
    }

    /**
     * Binds a recorded payload to the declared signal type. A payload that does not bind is a deterministic failure of
     * the signal; the refusal names the instance and the signal, never a value or a binder message.
     */
    private static Object coerce(Object payload, Class<?> payloadClass, WorkflowInstance inst, String signalName) {
        try {
            return WorkflowPayloads.coercePayload(payload, payloadClass);
        } catch (RuntimeException e) {
            throw new WorkflowInvalidPayloadException(
                    "The payload of signal '" + EchoedNames.bounded(signalName) + "' for instance '"
                            + inst.id().value() + "' does not bind to the declared signal type",
                    List.of());
        }
    }

    // --- Internal helpers ---

    /**
     * Returns whether any wait node of the pinned plan, including the waits inside fork branches and
     * the waits guarded by a timeout, waits for the named signal.
     */
    private static boolean waitsForSignal(RuntimeWorkflow rw, String signalName) {
        for (WorkflowNode node : rw.plan().nodes()) {
            if (node instanceof WaitSignalNode wait && signalName.equals(wait.signalName())) {
                return true;
            }
        }
        return false;
    }

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
