// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException;
import dev.vertique.workflow.plan.CompensationNode;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.DecisionNode;
import dev.vertique.workflow.plan.FailNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private collaborator owning the core state-machine transition driver.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C5). It absorbs the mutually recursive transition loop and the per-node
 * handlers for the single-path plan nodes:
 * <ul>
 *   <li>{@link #driveTransitions} / {@link #driveTransitionsStep} — the {@code Future.compose}
 *       recursion that advances {@code currentStepId} until a wait, terminal, or error state.</li>
 *   <li>{@link #handleServiceDispatch}, {@link #handleWaitSignal} (+timeout),
 *       {@link #handleDecision}, {@link #handleComplete}, {@link #handleFail},
 *       {@link #handleTimerNode}, {@link #handleHumanTaskNode} (+due-date) — the per-node
 *       handlers.</li>
 * </ul>
 *
 * <p><b>Mutual recursion / cycle-break (driver ↔ fork/join).</b> The driver dispatches a
 * {@link dev.vertique.workflow.plan.ForkNode} to {@link ForkJoinCoordinator#handleForkNode}, and the
 * coordinator drives the parent instance back into {@link #driveTransitionsStep} after a join
 * decides. To break the Dagger constructor cycle, the driver injects the coordinator
 * <em>eagerly</em> while the coordinator injects a {@link Provider}{@code <}{@link
 * WorkflowTransitionDriver}{@code >} <em>lazily</em>. This mirrors the C4 wiring (the lazy
 * {@code Provider} is on the {@link ForkJoinCoordinator} side; the driver is eager).
 *
 * <p><b>Task back-edge (C7).</b> {@link #handleHumanTaskNode} (+due-date) needs the task-creation
 * helpers {@code resolveAssignment} and {@code appendTaskCreatedHistory}, which slice C7 placed on
 * {@link TaskLifecycleService}. The driver reaches them through a lazy
 * {@link Provider}{@code <}{@link TaskLifecycleService}{@code >}; this lazy {@code Provider} also
 * breaks the driver↔task constructor cycle (the service injects the driver eagerly). The static
 * {@link TaskLifecycleService#buildTaskRecord} helper is invoked statically (no back-edge needed).
 *
 * <p><b>Timer back-edge (C8).</b> The timer-bearing handlers
 * ({@link #handleTimerNode}, {@link #handleWaitSignalWithTimeout}, {@link #handleHumanTaskNodeWithDueDate})
 * depend on {@link TimerLifecycleService#resolveFireAt}, which PRD-WF-006 §4 assigns to
 * {@link TimerLifecycleService} (C8). The driver reaches it through a lazy
 * {@link Provider}{@code <}{@link TimerLifecycleService}{@code >}; this lazy {@code Provider} breaks
 * the driver↔timer constructor cycle (the timer service injects the driver eagerly to drive
 * transitions after a timer fires). This replaced the transient C5
 * {@code Provider<WorkflowEngine>} back-edge — the driver no longer references the engine facade.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class WorkflowTransitionDriver {

    // --- Dependencies ---

    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final RecorderRouter recorders;
    private final TaskStore<SqlClient> taskStore;
    private final WorkflowEventEmitter eventEmitter;
    private final ReminderScheduler reminderScheduler;
    private final CompensationService compensationService;
    private final ForkJoinCoordinator forkJoin;
    private final Clock clock;

    /**
     * Lazy back-edge to {@link TimerLifecycleService} for the {@code resolveFireAt} helper used by
     * the timer-bearing node handlers ({@link #handleTimerNode},
     * {@link #handleWaitSignalWithTimeout}, {@link #handleHumanTaskNodeWithDueDate}). The
     * {@code Provider} is lazy so the driver↔timer constructor cycle does not form (the timer
     * service injects the driver eagerly); it is dereferenced only at runtime, inside the node
     * handlers, by which point construction is complete.
     */
    private final Provider<TimerLifecycleService> timerPvd;

    /**
     * Lazy back-edge to {@link TaskLifecycleService} for the task-creation helpers
     * ({@code resolveAssignment}, {@code appendTaskCreatedHistory}) used by
     * {@link #handleHumanTaskNode}. The {@code Provider} is lazy so the driver↔task constructor cycle
     * does not form (the service injects the driver eagerly); it is dereferenced only at runtime,
     * inside the node handlers, by which point construction is complete.
     */
    private final Provider<TaskLifecycleService> taskPvd;

    /**
     * Constructs a new transition driver.
     *
     * @param instances the instance repository used for optimistic-concurrency state transitions
     * @param history the history repository used to read prior entries and append lifecycle entries
     * @param recorders the recorder router used to route side-effect intents (service, timer)
     * @param taskStore the task store used to insert task rows for human-task nodes
     * @param eventEmitter the event emitter used to emit lifecycle events on state transitions
     * @param reminderScheduler the reminder scheduler used to schedule task-reminder timers
     * @param compensationService the single-path compensation service invoked from the fail handler
     * @param forkJoin the fork/join coordinator dispatched to for {@code ForkNode}; injected eagerly
     *     (the coordinator holds the lazy {@code Provider<WorkflowTransitionDriver>} back-edge)
     * @param clock the clock used to timestamp entries and state updates
     * @param timerPvd lazy provider of the timer-lifecycle service, used for the {@code resolveFireAt}
     *     helper when handling a timer node, a wait-signal node with a timeout, or a human-task node
     *     with a due date; lazy to break the driver↔timer constructor cycle, dereferenced only at
     *     runtime
     * @param taskPvd lazy provider of the task-lifecycle service, used for the task-creation helpers
     *     ({@code resolveAssignment}, {@code appendTaskCreatedHistory}) when handling a human-task
     *     node; lazy to break the driver↔task constructor cycle, dereferenced only at runtime
     */
    @Inject
    WorkflowTransitionDriver(
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            RecorderRouter recorders,
            TaskStore<SqlClient> taskStore,
            WorkflowEventEmitter eventEmitter,
            ReminderScheduler reminderScheduler,
            CompensationService compensationService,
            ForkJoinCoordinator forkJoin,
            Clock clock,
            Provider<TimerLifecycleService> timerPvd,
            Provider<TaskLifecycleService> taskPvd) {
        this.instances = instances;
        this.history = history;
        this.recorders = recorders;
        this.taskStore = taskStore;
        this.eventEmitter = eventEmitter;
        this.reminderScheduler = reminderScheduler;
        this.compensationService = compensationService;
        this.forkJoin = forkJoin;
        this.clock = clock;
        this.timerPvd = timerPvd;
        this.taskPvd = taskPvd;
    }

    // --- State machine driver ---

    /**
     * Drives the state machine forward from the instance's {@code currentStepId} until a wait,
     * terminal, or error state is reached.
     *
     * <p>Implemented as mutual recursion via {@code Future.compose} to avoid blocking.
     *
     * @param inst the current instance snapshot (version already incremented before this call)
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the engine reaches a stable state
     */
    Future<Void> driveTransitions(WorkflowInstance inst, RuntimeWorkflow rw, SqlClient tx) {
        return driveTransitionsStep(inst, rw, tx);
    }

    /**
     * Executes one step of the state machine, then recurses via {@code Future.compose} for
     * non-terminal, non-waiting transitions.
     *
     * <p>{@link ForkJoinCoordinator#advanceInstanceAfterJoin} continues the parent instance through
     * this method after a join decides, via the coordinator's lazy
     * {@code Provider<WorkflowTransitionDriver>} back-edge.
     *
     * @param inst the current instance snapshot
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the engine settles
     */
    Future<Void> driveTransitionsStep(WorkflowInstance inst, RuntimeWorkflow rw, SqlClient tx) {
        WorkflowNode node = rw.nodeById().get(inst.currentStepId());
        if (node == null) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Step '" + inst.currentStepId() + "' not found in plan '" + inst.definitionId() + "'"));
        }

        return switch (node) {
            case ServiceDispatchNode sdn -> handleServiceDispatch(inst, sdn, rw, tx);
            case WaitSignalNode wsn -> handleWaitSignal(inst, wsn, rw, tx);
            case DecisionNode dn -> handleDecision(inst, dn, rw, tx);
            case CompleteNode cn -> handleComplete(inst, cn, tx);
            case FailNode fn -> handleFail(inst, fn, rw, tx);
            case CompensationNode cn ->
                Future.failedFuture(new WorkflowDefinitionException("CompensationNode '" + cn.stepId()
                        + "' reached via forward flow; compensation nodes only run from compensate(...)"));
            case TimerNode tn -> handleTimerNode(inst, tn, rw, tx);
            case HumanTaskNode hn -> handleHumanTaskNode(inst, hn, rw, tx);
            case dev.vertique.workflow.plan.ForkNode fn -> forkJoin.handleForkNode(inst, fn, rw, tx);
            // JoinNode is reached only via evaluateJoin; direct execution is a definition error.
            case dev.vertique.workflow.plan.JoinNode jn ->
                Future.failedFuture(new WorkflowDefinitionException("JoinNode '" + jn.stepId()
                        + "' reached via forward flow; joins advance via branch-token completion only"));
        };
    }

    /**
     * Handles a {@link ServiceDispatchNode}: builds and routes the intent, persists history, then
     * advances {@code currentStepId} and continues the state machine.
     *
     * @param inst the current instance
     * @param sdn the node to process
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that continues the state machine after the intent is recorded
     */
    private Future<Void> handleServiceDispatch(
            WorkflowInstance inst, ServiceDispatchNode sdn, RuntimeWorkflow rw, SqlClient tx) {
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
        Object payload;
        try {
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<Object, Object>)
                    (java.util.function.Function<?, ?>) rw.callbacks().payloadFactory(sdn.payloadCallbackId());
            payload = factory.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "payloadFactory threw for step '" + sdn.stepId() + "': " + e.getMessage()));
        }

        return history.nextSequence(inst.id(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.SERVICE,
                    sdn.targetId(),
                    payload,
                    Map.of(),
                    WorkflowSideEffectIntent.Correlation.singlePath(inst.id(), seq, inst.definitionId(), sdn.stepId()));

            return recorders
                    .route(intent, tx)
                    .compose(result -> {
                        String histPayload =
                                Json.encode(SideEffectRecordedHistoryPayload.of(sdn.stepId(), sdn.targetId()));
                        return history.append(
                                new WorkflowHistoryEntry(
                                        inst.id(),
                                        seq,
                                        WorkflowEntryType.SIDE_EFFECT_RECORDED,
                                        histPayload,
                                        clock.instant()),
                                tx);
                    })
                    .compose(v -> {
                        long prevVersion = inst.version();
                        WorkflowInstance advanced = inst.withVersion(prevVersion + 1)
                                .withCurrentStepId(sdn.nextStepId())
                                .withWait(null, null)
                                .withError(null, null)
                                .withUpdatedAt(clock.instant());
                        return instances
                                .updateOptimistic(advanced, prevVersion, tx)
                                .compose(rowCount -> {
                                    if (rowCount == 0) {
                                        return Future.failedFuture(new WorkflowConflictException(
                                                "Optimistic concurrency conflict for instance '"
                                                        + inst.id().value() + "' at step '" + sdn.stepId() + "'"));
                                    }
                                    return driveTransitionsStep(advanced, rw, tx);
                                });
                    });
        });
    }

    /**
     * Handles a {@link WaitSignalNode}: transitions the instance to {@code WAITING} and returns.
     *
     * <p>When the node has a {@link WaitSignalNode.TimeoutBranch}, a {@code WORKFLOW_TIMER} intent
     * is routed first to schedule a durable timer. The timer id is stored in {@code waitAuxId} so
     * the engine can cancel the timer if the expected signal arrives before the deadline.
     *
     * @param inst the current instance
     * @param wsn the node to process
     * @param rw the resolved runtime workflow (needed for timer-resolver callbacks)
     * @param tx the active transaction
     * @return a {@link Future} that completes after the WAITING state is persisted
     */
    private Future<Void> handleWaitSignal(WorkflowInstance inst, WaitSignalNode wsn, RuntimeWorkflow rw, SqlClient tx) {
        if (wsn.timeout() != null) {
            return handleWaitSignalWithTimeout(inst, wsn, rw, tx);
        }
        // No timeout — simple SIGNAL wait (cycle-1 path).
        long prevVersion = inst.version();
        WorkflowInstance waiting = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.WAITING)
                .withCurrentStepId(wsn.stepId())
                .withWait(WaitType.SIGNAL, wsn.signalName())
                .withError(null, null)
                .withUpdatedAt(clock.instant());

        return instances.updateOptimistic(waiting, prevVersion, tx).compose(rowCount -> {
            if (rowCount == 0) {
                return Future.failedFuture(
                        new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                + inst.id().value() + "' at WaitSignalNode '" + wsn.stepId() + "'"));
            }
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Handles a {@link WaitSignalNode} that has a {@link WaitSignalNode.TimeoutBranch}: schedules a
     * durable timer via the {@code WORKFLOW_TIMER} recorder, then transitions the instance to
     * {@code WAITING} with {@code wait_type=SIGNAL}, {@code wait_key=signalName}, and
     * {@code wait_aux_id=timerId}.
     *
     * @param inst the current instance
     * @param wsn the node to process; must have a non-null {@code timeout()} branch
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes after the WAITING state is persisted
     */
    private Future<Void> handleWaitSignalWithTimeout(
            WorkflowInstance inst, WaitSignalNode wsn, RuntimeWorkflow rw, SqlClient tx) {
        Instant fireAt = timerPvd.get().resolveFireAt(wsn.timeout().timeout(), inst, rw);

        return history.nextSequence(inst.id(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    wsn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.SIGNAL_TIMEOUT, null),
                    Map.of(),
                    WorkflowSideEffectIntent.Correlation.singlePath(inst.id(), seq, inst.definitionId(), wsn.stepId()));

            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();

                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(wsn.stepId(), timerId, fireAt, TimerPurpose.SIGNAL_TIMEOUT));
                return history.append(
                                new WorkflowHistoryEntry(
                                        inst.id(),
                                        seq,
                                        WorkflowEntryType.TIMER_SCHEDULED,
                                        histPayload,
                                        clock.instant()),
                                tx)
                        .compose(v -> {
                            long prevVersion = inst.version();
                            WorkflowInstance waiting = inst.withVersion(prevVersion + 1)
                                    .withStatus(WorkflowStatus.WAITING)
                                    .withCurrentStepId(wsn.stepId())
                                    .withWait(WaitType.SIGNAL, wsn.signalName(), timerId)
                                    .withError(null, null)
                                    .withUpdatedAt(clock.instant());

                            return instances
                                    .updateOptimistic(waiting, prevVersion, tx)
                                    .compose(rowCount -> {
                                        if (rowCount == 0) {
                                            return Future.failedFuture(new WorkflowConflictException(
                                                    "Optimistic concurrency conflict for instance '"
                                                            + inst.id().value()
                                                            + "' at WaitSignalNode '"
                                                            + wsn.stepId() + "' (timeout branch)"));
                                        }
                                        return Future.<Void>succeededFuture();
                                    });
                        });
            });
        });
    }

    /**
     * Handles a {@link DecisionNode}: resolves the next step via the registered callback and
     * continues the state machine.
     *
     * @param inst the current instance
     * @param dn the node to process
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that continues the state machine after the decision is resolved
     */
    private Future<Void> handleDecision(WorkflowInstance inst, DecisionNode dn, RuntimeWorkflow rw, SqlClient tx) {
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
        String nextStepId;
        try {
            @SuppressWarnings("unchecked")
            var resolver = (java.util.function.Function<Object, String>) (java.util.function.Function<?, ?>)
                    rw.callbacks().decisionResolver(dn.nextStepResolverCallbackId());
            nextStepId = resolver.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "decisionResolver threw for step '" + dn.stepId() + "': " + e.getMessage()));
        }

        long prevVersion = inst.version();
        WorkflowInstance advanced = inst.withVersion(prevVersion + 1)
                .withCurrentStepId(nextStepId)
                .withWait(null, null)
                .withError(null, null)
                .withUpdatedAt(clock.instant());

        return instances.updateOptimistic(advanced, prevVersion, tx).compose(rowCount -> {
            if (rowCount == 0) {
                return Future.failedFuture(
                        new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                + inst.id().value() + "' at DecisionNode '" + dn.stepId() + "'"));
            }
            return driveTransitionsStep(advanced, rw, tx);
        });
    }

    /**
     * Handles a {@link CompleteNode}: transitions the instance to {@code COMPLETED} (terminal).
     *
     * @param inst the current instance
     * @param cn the node to process
     * @param tx the active transaction
     * @return a {@link Future} that completes after the COMPLETED state is persisted
     */
    private Future<Void> handleComplete(WorkflowInstance inst, CompleteNode cn, SqlClient tx) {
        long prevVersion = inst.version();
        WorkflowInstance completed = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.COMPLETED)
                .withCurrentStepId(cn.stepId())
                .withWait(null, null)
                .withError(null, null)
                .withUpdatedAt(clock.instant());

        return instances
                .updateOptimistic(completed, prevVersion, tx)
                .compose(rowCount -> WorkflowPayloads.requireRowUpdated(
                        rowCount, "instance '" + inst.id().value() + "' at CompleteNode '" + cn.stepId() + "'"))
                .compose(v -> history.nextSequence(inst.id(), tx))
                .compose(seq -> {
                    String histPayload = Json.encode(new CompletedHistoryPayload(cn.stepId()));
                    return history.append(
                                    new WorkflowHistoryEntry(
                                            inst.id(), seq, WorkflowEntryType.COMPLETED, histPayload, clock.instant()),
                                    tx)
                            .compose(v2 -> eventEmitter.emitEvent(
                                    inst, WorkflowEventType.WORKFLOW_COMPLETED, seq, null, null, Map.of(), tx));
                });
    }

    /**
     * Handles a {@link FailNode}: transitions the instance to {@code FAILED}, appends history, then
     * initiates compensation.
     *
     * @param inst the current instance
     * @param fn the node to process
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes after the FAILED state and compensation are persisted
     */
    private Future<Void> handleFail(WorkflowInstance inst, FailNode fn, RuntimeWorkflow rw, SqlClient tx) {
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
        String errorMessage;
        try {
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<Object, String>) (java.util.function.Function<?, ?>)
                    rw.callbacks().failMessageFactory(fn.messageFactoryCallbackId());
            errorMessage = factory.apply(stateObj);
        } catch (Exception e) {
            errorMessage = "failMessageFactory threw: " + e.getMessage();
        }

        long prevVersion = inst.version();
        String finalErrorMessage = errorMessage;
        WorkflowInstance failed = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.FAILED)
                .withCurrentStepId(fn.stepId())
                .withWait(null, null)
                .withError(fn.errorType(), errorMessage)
                .withUpdatedAt(clock.instant());

        return instances
                .updateOptimistic(failed, prevVersion, tx)
                .compose(rowCount -> WorkflowPayloads.requireRowUpdated(
                        rowCount, "instance '" + inst.id().value() + "' at FailNode '" + fn.stepId() + "'"))
                .compose(v -> history.nextSequence(inst.id(), tx))
                .compose(seq -> {
                    String histPayload =
                            Json.encode(new FailedHistoryPayload(fn.stepId(), fn.errorType(), finalErrorMessage));
                    return history.append(
                                    new WorkflowHistoryEntry(
                                            inst.id(), seq, WorkflowEntryType.FAILED, histPayload, clock.instant()),
                                    tx)
                            .compose(v2 -> eventEmitter.emitEvent(
                                    inst,
                                    WorkflowEventType.WORKFLOW_FAILED,
                                    seq,
                                    null,
                                    null,
                                    WorkflowEventEmitter.attrs(
                                            "errorType", failed.errorType(), "errorMessage", failed.errorMessage()),
                                    tx));
                })
                .compose(v -> compensationService.compensate(failed, rw, tx));
    }

    // --- Timer node handler ---

    /**
     * Handles a {@link TimerNode}: schedules a durable timer via the {@code WORKFLOW_TIMER} recorder,
     * appends a {@code TIMER_SCHEDULED} history entry with {@link TimerPurpose#STANDALONE}, and
     * transitions the instance to {@code WAITING} with {@code wait_type=TIMER} and
     * {@code wait_key=timerId.toString()}.
     *
     * @param inst the current instance
     * @param tn the timer node to process
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes after the WAITING state is persisted
     */
    private Future<Void> handleTimerNode(WorkflowInstance inst, TimerNode tn, RuntimeWorkflow rw, SqlClient tx) {
        Instant fireAt = timerPvd.get().resolveFireAt(tn.spec(), inst, rw);

        return history.nextSequence(inst.id(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    tn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.STANDALONE, null),
                    Map.of(),
                    WorkflowSideEffectIntent.Correlation.singlePath(inst.id(), seq, inst.definitionId(), tn.stepId()));

            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();

                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(tn.stepId(), timerId, fireAt, TimerPurpose.STANDALONE));
                return history.append(
                                new WorkflowHistoryEntry(
                                        inst.id(),
                                        seq,
                                        WorkflowEntryType.TIMER_SCHEDULED,
                                        histPayload,
                                        clock.instant()),
                                tx)
                        .compose(v -> {
                            long prevVersion = inst.version();
                            WorkflowInstance waiting = inst.withVersion(prevVersion + 1)
                                    .withStatus(WorkflowStatus.WAITING)
                                    .withCurrentStepId(tn.stepId())
                                    .withWait(WaitType.TIMER, timerId.toString())
                                    .withError(null, null)
                                    .withUpdatedAt(clock.instant());

                            return instances
                                    .updateOptimistic(waiting, prevVersion, tx)
                                    .compose(rowCount -> {
                                        if (rowCount == 0) {
                                            return Future.failedFuture(new WorkflowConflictException(
                                                    "Optimistic concurrency conflict for instance '"
                                                            + inst.id().value()
                                                            + "' at TimerNode '"
                                                            + tn.stepId() + "'"));
                                        }
                                        return Future.<Void>succeededFuture();
                                    });
                        });
            });
        });
    }

    // --- HumanTaskNode handler ---

    /**
     * Handles a {@link HumanTaskNode}: resolves the assignment spec, optionally schedules a
     * due-date timer, inserts the task row, transitions the instance to {@code WAITING} with
     * {@code wait_type=TASK}, and appends a {@code TASK_CREATED} history entry.
     *
     * @param inst the current instance
     * @param htn  the node to process
     * @param rw   the resolved runtime workflow
     * @param tx   the active transaction
     * @return a {@link Future} that completes after the WAITING state is persisted
     */
    private Future<Void> handleHumanTaskNode(
            WorkflowInstance inst, HumanTaskNode htn, RuntimeWorkflow rw, SqlClient tx) {
        // Resolve AssignmentSpec to a literal TaskAssignment.
        TaskAssignment assignment;
        try {
            assignment = taskPvd.get().resolveAssignment(htn.assignment(), inst, rw);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        UUID taskId = UUID.randomUUID();
        List<TaskDecisionDescriptor> descriptors = htn.decisions().stream()
                .map(d -> new TaskDecisionDescriptor(d.name(), d.payloadTypeName(), d.nextStepId()))
                .toList();

        // Snapshot subject version for every task regardless of requireVersionStability (audit value).
        String subjectVersionAtCreation =
                (inst.subjectRef() == null) ? null : inst.subjectRef().version();

        // Per-task stability guard: fail early so the tx rolls back before any row mutations.
        if (htn.requireVersionStability() && subjectVersionAtCreation == null) {
            return Future.failedFuture(new WorkflowSubjectVersionUnavailableException("Workflow '" + inst.definitionId()
                    + "' reaches stability-required task '" + htn.stepId() + "' without a versioned subject ref"));
        }

        // Optionally schedule a due-date timer (reusing the WORKFLOW_TIMER recorder path).
        if (htn.dueDate() != null) {
            return handleHumanTaskNodeWithDueDate(
                    inst, htn, rw, tx, assignment, taskId, descriptors, subjectVersionAtCreation);
        }

        // No due-date — simple task wait.
        Instant now = clock.instant();
        TaskRecord record = TaskLifecycleService.buildTaskRecord(
                taskId, inst, htn, assignment, descriptors, null, null, subjectVersionAtCreation, now);

        return taskStore.insertOpen(record, tx).compose(v -> {
            long prevVersion = inst.version();
            WorkflowInstance waiting = inst.withVersion(prevVersion + 1)
                    .withStatus(WorkflowStatus.WAITING)
                    .withCurrentStepId(htn.stepId())
                    .withWait(WaitType.TASK, taskId.toString())
                    .withError(null, null)
                    .withUpdatedAt(now);
            return instances.updateOptimistic(waiting, prevVersion, tx).compose(rowCount -> {
                if (rowCount == 0) {
                    return Future.<Void>failedFuture(
                            new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                    + inst.id().value() + "' at HumanTaskNode '" + htn.stepId() + "'"));
                }
                return taskPvd.get()
                        .appendTaskCreatedHistory(inst, htn.stepId(), taskId, assignment, null, tx)
                        // Anchor initial reminders to the same `now` Instant captured at task-row
                        // construction (the row's created_at and updated_at also use this instant),
                        // so OneShotOffsets fire at exactly `created_at + offset` and the first
                        // recurring fire is at exactly `created_at + interval` — no drift from
                        // intermediate work between task insert and reminder scheduling.
                        .compose(v2 -> reminderScheduler.scheduleReminderTimers(inst, htn, taskId, now, tx));
            });
        });
    }

    /**
     * Variant of {@link #handleHumanTaskNode} that also schedules a due-date timer via the
     * {@code WORKFLOW_TIMER} recorder before inserting the task row.
     *
     * @param inst                     the current instance
     * @param htn                      the node with a non-null {@code dueDate()} triplet
     * @param rw                       the resolved runtime workflow
     * @param tx                       the active transaction
     * @param assignment               the resolved literal task assignment
     * @param taskId                   the new task UUID
     * @param descriptors              the decision descriptors to snapshot
     * @param subjectVersionAtCreation snapshot of the subject version at task creation, or
     *                                 {@code null} when the instance has no versioned subject ref
     * @return a {@link Future} that completes after the WAITING state is persisted
     */
    private Future<Void> handleHumanTaskNodeWithDueDate(
            WorkflowInstance inst,
            HumanTaskNode htn,
            RuntimeWorkflow rw,
            SqlClient tx,
            TaskAssignment assignment,
            UUID taskId,
            List<TaskDecisionDescriptor> descriptors,
            @Nullable String subjectVersionAtCreation) {
        Instant fireAt = timerPvd.get().resolveFireAt(htn.dueDate(), inst, rw);

        return history.nextSequence(inst.id(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    htn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.TASK_DUE, taskId),
                    Map.of(),
                    WorkflowSideEffectIntent.Correlation.singlePath(inst.id(), seq, inst.definitionId(), htn.stepId()));

            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID dueDateTimerId = timerResult.timerId();
                Instant now = clock.instant();

                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(htn.stepId(), dueDateTimerId, fireAt, TimerPurpose.TASK_DUE));
                return history.append(
                                new WorkflowHistoryEntry(
                                        inst.id(), seq, WorkflowEntryType.TIMER_SCHEDULED, histPayload, now),
                                tx)
                        .compose(v -> {
                            TaskRecord record = TaskLifecycleService.buildTaskRecord(
                                    taskId,
                                    inst,
                                    htn,
                                    assignment,
                                    descriptors,
                                    fireAt,
                                    dueDateTimerId,
                                    subjectVersionAtCreation,
                                    now);
                            return taskStore.insertOpen(record, tx);
                        })
                        .compose(v -> {
                            long prevVersion = inst.version();
                            WorkflowInstance waiting = inst.withVersion(prevVersion + 1)
                                    .withStatus(WorkflowStatus.WAITING)
                                    .withCurrentStepId(htn.stepId())
                                    .withWait(WaitType.TASK, taskId.toString(), dueDateTimerId)
                                    .withError(null, null)
                                    .withUpdatedAt(now);
                            return instances
                                    .updateOptimistic(waiting, prevVersion, tx)
                                    .compose(rowCount -> {
                                        if (rowCount == 0) {
                                            return Future.<Void>failedFuture(new WorkflowConflictException(
                                                    "Optimistic concurrency conflict for instance '"
                                                            + inst.id().value()
                                                            + "' at HumanTaskNode '"
                                                            + htn.stepId() + "' (due-date branch)"));
                                        }
                                        return taskPvd.get()
                                                .appendTaskCreatedHistory(
                                                        inst, htn.stepId(), taskId, assignment, fireAt, tx)
                                                // Anchor initial reminders to the same `now` Instant
                                                // captured at task-row construction time (used as
                                                // both created_at and updated_at on the task row).
                                                .compose(v2 -> reminderScheduler.scheduleReminderTimers(
                                                        inst, htn, taskId, now, tx));
                                    });
                        });
            });
        });
    }
}
