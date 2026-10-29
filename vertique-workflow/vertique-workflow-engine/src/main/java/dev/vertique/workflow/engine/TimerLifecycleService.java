// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.executor.ClaimLostException;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.EndedInstanceOutcomes;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.history.DecisionLog;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
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
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Package-private collaborator owning the timer-fire paths and timer helpers.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C8). The workflow executor runs a recorded {@code TIMER} command through
 * {@link #runTimer}; the service also owns the supporting helpers:
 * <ul>
 *   <li>{@link #runTimer} — the executor entry: validates the pin, locks the timer row and fires it.</li>
 *   <li>{@link #handleTimerFiredStandalone} / {@link #handleTimerFiredTimeout} — the single-path
 *       standalone-timer and signal-timeout fire flows.</li>
 *   <li>{@link #doBranchTimerFired} / {@link #doBranchStandaloneTimerFired} /
 *       {@link #doBranchSignalTimeoutFired} — the branch-owned timer-fire flows.</li>
 *   <li>The static helper {@link #resolveActiveTimerId}, the
 *       canonical {@link #appendTimerCancelledHistory}, and {@link #resolveFireAt}.</li>
 * </ul>
 *
 * <p>Plan-hash checks reuse the
 * shared {@link WorkflowPayloads} utilities.
 *
 * <p><b>Driver↔timer cycle-break.</b> This service drives the state machine forward after a timer
 * fires via {@link WorkflowTransitionDriver#driveTransitions} (single-path) and
 * {@link ForkJoinCoordinator#driveAndMaybeJoin} (branch-path), so it injects both <em>eagerly</em>.
 * The {@link WorkflowTransitionDriver} in turn needs this service's {@link #resolveFireAt} helper
 * when it handles a {@link TimerNode}, a {@link WaitSignalNode} with a timeout, or a
 * {@link dev.vertique.workflow.plan.HumanTaskNode} with a due date; it reaches it through a lazy
 * {@code Provider<TimerLifecycleService>}. The lazy {@code Provider} on the driver side is what
 * breaks the Dagger constructor cycle (replacing the transient C5 {@code Provider<WorkflowEngine>}
 * back-edge the driver previously used for {@code resolveFireAt}).
 *
 * <p><b>Timer→task edge.</b> {@link #runTimer} runs a {@code TASK_DUE} or {@code TASK_REMINDER}
 * timer through {@link TaskLifecycleService}, so this service injects
 * {@link TaskLifecycleService} <em>eagerly</em>. {@link TaskLifecycleService} therefore must not
 * depend back on this service (it keeps its own private {@code appendTimerCancelledHistory} copy for
 * its task-completion due-date cancel path, to avoid a {@code timer → task → timer} cycle).
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class TimerLifecycleService {

    // --- Dependencies ---

    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final BranchTokenRepository<SqlClient> branchTokens;
    private final TimerStore<SqlClient> timerStore;
    private final ForkJoinCoordinator forkJoin;
    private final WorkflowTransitionDriver driver;
    private final TaskLifecycleService taskLifecycle;
    private final Clock clock;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final WorkflowHistoryRecorder historyRecorder;
    private final DecisionLog decisions;

    /**
     * Binder-row seam for the instance-owned (non-branch) timer drives (Contract Appendix C2): the
     * single-path branch of {@link #fired}. The branch-owned fire path
     * ({@link #doBranchTimerFired}) MUST NOT use this binder — the bind-once routing rule reserves
     * branch-owned drives for the {@link BranchTransitionEngine} carrier seam. Never {@code null} —
     * the engine assembly seam substitutes {@link WorkflowContextBinder#noop()} when the propagator
     * is {@code null}, so every instance-owned timer drive calls
     * {@link WorkflowContextBinder#withBound} directly.
     */
    private final WorkflowContextBinder contextBinder;

    /**
     * Constructs a new timer-lifecycle service.
     *
     * @param registry the workflow registry used to resolve the pinned {@link RuntimeWorkflow}
     * @param instances the instance repository used for optimistic-concurrency state transitions
     * @param history the history repository used for sequence numbers and history entries
     * @param branchTokens the branch-token repository used by the branch-owned timer paths; may be
     *     {@code null} in legacy test constructors that omit the fork/join collaborators
     * @param timerStore the timer store used to lock, mark, and read timer rows
     * @param forkJoin the fork/join coordinator whose {@code driveAndMaybeJoin} continues a branch
     *     after a branch-owned timer fires; may be {@code null} in legacy test constructors
     * @param driver the transition driver invoked as the single-path post-fire continuation
     * @param taskLifecycle the task-lifecycle service that runs the due date and the reminders of a
     *     task's timers; injected eagerly (the timer→task edge has no back-edge)
     * @param clock the clock used to timestamp history entries and state updates
     * @param contextBinder the binder-row seam for the instance-owned timer drives; never
     *     {@code null} (the engine assembly seam substitutes {@link WorkflowContextBinder#noop()}
     *     when durable-context wiring is not exercised)
     * @param commands the command repository that settles the {@code TIMER} command of a stale timer
     * @param historyRecorder the history recorder that logs what became of a {@code TIMER} command
     */
    @Inject
    TimerLifecycleService(
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            BranchTokenRepository<SqlClient> branchTokens,
            TimerStore<SqlClient> timerStore,
            ForkJoinCoordinator forkJoin,
            WorkflowTransitionDriver driver,
            TaskLifecycleService taskLifecycle,
            Clock clock,
            WorkflowContextBinder contextBinder,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowHistoryRecorder historyRecorder) {
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.branchTokens = branchTokens;
        this.timerStore = timerStore;
        this.forkJoin = forkJoin;
        this.driver = driver;
        this.taskLifecycle = taskLifecycle;
        this.clock = clock;
        this.contextBinder = contextBinder;
        this.commands = commands;
        this.historyRecorder = historyRecorder;
        this.decisions = historyRecorder == null ? null : historyRecorder.decisions();
    }

    // --- Recorded timers in a segment ---

    /**
     * Runs the work of a claimed {@code TIMER} command inside the segment's transaction and settles it, except when
     * the timer fires: then the executor completes it.
     *
     * <ul>
     *   <li>The instance has ended or failed, the timer row is no longer scheduled, or the wait the timer belongs to is
     *       gone (the instance moved on, or the task is not open): the command is stale and nothing else changes; a
     *       timer row that is still scheduled is cancelled.</li>
     *   <li>Otherwise the timer fires: a timer node advances the instance, a timeout applies the wait's timeout branch,
     *       a due date expires the task and a reminder records its history, the next reminder of a recurring spec and
     *       its event. The timer row is {@code FIRED}. The durable context bound for the drive is the context the timer
     *       recorded, without an identity.</li>
     * </ul>
     *
     * @param head    the peeked head the claim came from
     * @param claim   the claim
     * @param command the fenced command
     * @param tx      the segment's transaction handle
     * @return the outcome the executor acts on
     */
    Future<WorkflowSegments.Outcome> runTimer(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        UUID timerId = command.timerId();
        String timerKind = command.timerKind();
        if (timerId == null || timerKind == null) {
            return Future.failedFuture(
                    new IllegalStateException("The recorded timer command " + command.commandId() + " names no timer"));
        }
        return instances.findById(command.workflowId(), tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.<WorkflowSegments.Outcome>failedFuture(
                        new WorkflowInstanceNotFoundException(command.workflowId()));
            }
            WorkflowInstance inst = found.get();
            Optional<EndedInstanceOutcomes.Outcome> met = EndedInstanceOutcomes.of(
                    command.kind(), inst.status().name(), EndedInstanceOutcomes.Target.timer(timerKind));
            if (met.isPresent()) {
                if (met.get().settlement() == EndedInstanceOutcomes.Settlement.CANCELLED) {
                    return settleCancelled(claim, command, met.get().reason(), tx);
                }
                // A failed instance keeps its wait, and so its timer: a retry offers the timer again.
                return settleStale(claim, command, met.get().reason(), inst.status() != WorkflowStatus.FAILED, tx);
            }
            if (!inst.definitionScopeKey().equals(head.definitionScopeKey())
                    || !inst.definitionId().equals(head.definitionId())
                    || inst.definitionVersion() != head.definitionVersion()
                    || !Objects.equals(inst.planHash(), head.planHash())) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            }
            try {
                resolveAndCheckPlanHash(inst);
            } catch (WorkflowVersionNotRunnableException | WorkflowPlanHashDriftException e) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            return timerStore.lockForFiring(timerId, tx).compose(locked -> {
                if (locked.isEmpty() || locked.get().status() != TimerStatus.SCHEDULED) {
                    return settleStale(claim, command, CommandErrors.timerNotAwaited(timerKind), tx);
                }
                TimerRecord timer = locked.get();
                return historyRecorder
                        .appendCommandHistory(
                                command.workflowId(),
                                WorkflowEntryType.COMMAND_RECEIVED,
                                command.commandId(),
                                command.kind(),
                                tx)
                        .compose(received -> fire(timer, claim, command, tx))
                        .compose(result -> switch (result) {
                            case APPLIED ->
                                timerStore
                                        .markFired(timerId, clock.instant(), tx)
                                        .compose(done -> historyRecorder.appendCommandHistory(
                                                command.workflowId(),
                                                WorkflowEntryType.COMMAND_COMPLETED,
                                                command.commandId(),
                                                command.kind(),
                                                tx))
                                        .map(done -> WorkflowSegments.Outcome.APPLIED);
                            case STALE_NOOP, LOST_TO_RACE ->
                                settleStale(claim, command, CommandErrors.timerNotAwaited(timerKind), tx);
                        });
            });
        });
    }

    /** Runs the work of the timer the command fires, with the context the timer recorded bound. */
    private Future<TaskMutationResult> fire(
            TimerRecord timer, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        DriveBinding recorded = new DriveBinding() {
            @Override
            public long commandId() {
                return command.commandId();
            }

            @Override
            public <T> Future<T> run(WorkflowInstance instance, Supplier<Future<T>> drive) {
                return contextBinder.withRecordedBase(instance, command.commandId(), null, timer.metadata(), drive);
            }
        };
        return switch (timer.purpose()) {
            case STANDALONE, SIGNAL_TIMEOUT ->
                fired(command.workflowId(), timer.timerId(), recorded, tx).map(result -> switch (result) {
                    case APPLIED -> TaskMutationResult.APPLIED;
                    case STALE_NOOP -> TaskMutationResult.STALE_NOOP;
                });
            case TASK_DUE -> taskLifecycle.dueFired(command.workflowId(), timer.taskId(), recorded, tx);
            case TASK_REMINDER ->
                taskLifecycle.reminderFired(
                        command.workflowId(), timer.taskId(), timer.timerId(), timer.fireAt(), recorded, tx);
        };
    }

    /**
     * Settles the command as stale, in the segment's transaction, and cancels the timer row if it is still scheduled.
     * The command is settled first, so that cancelling the timer leaves it alone.
     */
    private Future<WorkflowSegments.Outcome> settleStale(
            ClaimedCommand claim, SegmentCommand command, String reason, SqlClient tx) {
        return settleStale(claim, command, reason, true, tx);
    }

    /**
     * Settles the command as stale and logs it. The timer row is cancelled when {@code cancelTimer} is set; a failed
     * instance keeps its wait, and so its timer, which a retry offers again.
     */
    private Future<WorkflowSegments.Outcome> settleStale(
            ClaimedCommand claim, SegmentCommand command, String reason, boolean cancelTimer, SqlClient tx) {
        return commands.stale(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> cancelTimer
                        ? timerStore
                                .markCancelled(command.timerId(), clock.instant(), tx)
                                .<Object>map(transition -> transition)
                        : Future.<Object>succeededFuture())
                .compose(done -> historyRecorder.appendTimerCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_STALE,
                        command.commandId(),
                        command.timerKind(),
                        command.timerId(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_STALE);
    }

    /**
     * Settles the command as cancelled, in the segment's transaction, because its instance was cancelled, and cancels
     * the timer row if it is still scheduled.
     */
    private Future<WorkflowSegments.Outcome> settleCancelled(
            ClaimedCommand claim, SegmentCommand command, String reason, SqlClient tx) {
        return commands.cancel(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> timerStore.markCancelled(command.timerId(), clock.instant(), tx))
                .compose(done -> historyRecorder.appendTimerCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_CANCELLED,
                        command.commandId(),
                        command.timerKind(),
                        command.timerId(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_CANCELLED);
    }

    // --- Timer fire ---

    /**
     * Fires a timer, binding the durable context of an instance-owned drive with the given binding.
     *
     * @param workflowId the workflow instance this timer belongs to
     * @param timerId    the stable UUID of the timer that fired
     * @param binding    binds the context of the instance-owned drive
     * @param tx         the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the firing result
     */
    private Future<TimerFiringResult> fired(
            WorkflowInstanceId workflowId, UUID timerId, DriveBinding binding, SqlClient tx) {
        // Load the timer record (non-locking) to check branch ownership. The caller holds the timer row's lock in the
        // same transaction; this plain SELECT reads the already-locked row without re-acquiring the lock.
        return timerStore.findById(timerId, tx).compose(optTimer -> {
            // Route branch-owned timers before touching the parent instance.
            if (optTimer.isPresent() && optTimer.get().branchTokenId() != null) {
                return doBranchTimerFired(workflowId, timerId, optTimer.get().branchTokenId(), binding.commandId(), tx);
            }

            // Single-path: gate on the parent instance's wait state.
            return instances.findById(workflowId, tx).compose(optInst -> {
                if (optInst.isEmpty()) {
                    return Future.succeededFuture(TimerFiringResult.STALE_NOOP);
                }
                WorkflowInstance inst = optInst.get();
                // Instance-owned (non-branch) timer-fire drive.
                return binding.run(inst, () -> doTimerFiredSinglePath(workflowId, timerId, inst, tx));
            });
        });
    }

    /**
     * Executes the single-path timer-fire dispatch after the instance-owned binder-row bind is in
     * effect. Extracted so {@link #fired} can pass it to {@link WorkflowContextBinder#withBound}
     * as a {@link java.util.function.Supplier}.
     *
     * @param workflowId the workflow instance this timer belongs to
     * @param timerId    the stable UUID of the timer that fired
     * @param inst       the loaded instance snapshot
     * @param tx         the active transaction
     * @return a {@link Future} resolving to the firing result
     */
    private Future<TimerFiringResult> doTimerFiredSinglePath(
            WorkflowInstanceId workflowId, UUID timerId, WorkflowInstance inst, SqlClient tx) {
        if (inst.status() != WorkflowStatus.WAITING) {
            return Future.succeededFuture(TimerFiringResult.STALE_NOOP);
        }

        // Standalone-timer path: wait_type=TIMER and wait_key == timerId.toString()
        if (inst.waitType() == WaitType.TIMER && timerId.toString().equals(inst.waitKey())) {
            return handleTimerFiredStandalone(inst, timerId, tx);
        }

        // Signal-timeout path: wait_type=SIGNAL and wait_aux_id == timerId
        if (inst.waitType() == WaitType.SIGNAL && timerId.equals(inst.waitAuxId())) {
            return handleTimerFiredTimeout(inst, timerId, tx);
        }

        // Slot mismatch — another timer or a different wait type.
        return Future.succeededFuture(TimerFiringResult.STALE_NOOP);
    }

    // --- Single-path timer-fire flows ---

    /**
     * Fires a standalone timer: advances the instance to {@code tn.nextStepId()}, appends
     * {@code TIMER_FIRED}, drives transitions, and returns {@link TimerFiringResult#APPLIED}.
     *
     * @param inst the WAITING instance (status already validated)
     * @param timerId the timer id that fired
     * @param tx the active transaction
     * @return a {@link Future} resolving to {@link TimerFiringResult#APPLIED}
     */
    private Future<TimerFiringResult> handleTimerFiredStandalone(WorkflowInstance inst, UUID timerId, SqlClient tx) {
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

        WorkflowNode node = rw.nodeById().get(inst.currentStepId());
        if (!(node instanceof TimerNode tn)) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Expected TimerNode at step '" + inst.currentStepId() + "' but found "
                            + (node == null ? "null" : node.getClass().getSimpleName())));
        }

        Instant now = clock.instant();
        long prevVersion = inst.version();
        WorkflowInstance advanced = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.RUNNING)
                .withCurrentStepId(tn.nextStepId())
                .withWait(null, null)
                .withError(null, null)
                .withUpdatedAt(now);

        return instances
                .updateOptimistic(advanced, prevVersion, tx)
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        return Future.<Void>failedFuture(
                                new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                        + inst.id().value() + "' during timerFired"));
                    }
                    return decisions
                            .record(inst, advanced, tn.stepId(), "timer-fired", tx)
                            .compose(logged -> history.nextSequence(inst.id(), tx))
                            .compose(seq -> {
                                String histPayload = Json.encode(
                                        new TimerFiredHistoryPayload(tn.stepId(), timerId, now, tn.nextStepId()));
                                return history.append(
                                        new WorkflowHistoryEntry(
                                                inst.id(), seq, WorkflowEntryType.TIMER_FIRED, histPayload, now),
                                        tx);
                            });
                })
                .compose(v -> driver.driveTransitions(advanced, rw, tx))
                .map(v -> TimerFiringResult.APPLIED);
    }

    /**
     * Fires a signal-timeout timer: applies the {@code onTimeout} mutator, advances the instance to
     * {@code timeoutNextStepId}, appends {@code TIMEOUT}, drives transitions, and returns
     * {@link TimerFiringResult#APPLIED}.
     *
     * @param inst the WAITING instance (status already validated)
     * @param timerId the timer id that fired
     * @param tx the active transaction
     * @return a {@link Future} resolving to {@link TimerFiringResult#APPLIED}
     */
    private Future<TimerFiringResult> handleTimerFiredTimeout(WorkflowInstance inst, UUID timerId, SqlClient tx) {
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

        WorkflowNode node = rw.nodeById().get(inst.currentStepId());
        if (!(node instanceof WaitSignalNode wsn) || wsn.timeout() == null) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Expected WaitSignalNode with timeout at step '" + inst.currentStepId()
                            + "' but found "
                            + (node == null ? "null" : node.getClass().getSimpleName())));
        }

        WaitSignalNode.TimeoutBranch branch = wsn.timeout();
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());

        Object newState;
        try {
            @SuppressWarnings("unchecked")
            var mutator = (java.util.function.Function<Object, Object>) (java.util.function.Function<?, ?>)
                    rw.callbacks().stateMutator(branch.onTimeoutMutatorCallbackId());
            newState = mutator.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "onTimeoutMutator threw for step '" + wsn.stepId() + "': " + e.getMessage()));
        }

        Instant now = clock.instant();
        long prevVersion = inst.version();
        WorkflowInstance advanced = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.RUNNING)
                .withCurrentStepId(branch.timeoutNextStepId())
                .withWait(null, null)
                .withState(Json.encode(newState))
                .withError(null, null)
                .withUpdatedAt(now);

        return instances
                .updateOptimistic(advanced, prevVersion, tx)
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        return Future.<Void>failedFuture(
                                new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                        + inst.id().value() + "' during timerFired (timeout branch)"));
                    }
                    return decisions
                            .record(inst, advanced, wsn.stepId(), "timeout", tx)
                            .compose(logged -> history.nextSequence(inst.id(), tx))
                            .compose(seq -> {
                                String histPayload = Json.encode(new TimeoutHistoryPayload(
                                        wsn.stepId(), timerId, now, branch.timeoutNextStepId()));
                                return history.append(
                                        new WorkflowHistoryEntry(
                                                inst.id(), seq, WorkflowEntryType.TIMEOUT, histPayload, now),
                                        tx);
                            });
                })
                .compose(v -> driver.driveTransitions(advanced, rw, tx))
                .map(v -> TimerFiringResult.APPLIED);
    }

    // --- Branch-aware timer-fire flows ---

    /**
     * Branch path for {@link #fired}: handles a timer fire when the timer is branch-owned
     * (i.e., {@link TimerRecord#branchTokenId()} is non-null).
     *
     * <p>Routes on the branch token's current wait state:
     * <ul>
     *   <li>{@code wait_type=TIMER, wait_key=timerId} — standalone branch timer fired.</li>
     *   <li>{@code wait_type=SIGNAL, wait_aux_id=timerId} — signal-timeout timer fired.</li>
     *   <li>Otherwise — {@link TimerFiringResult#STALE_NOOP} (branch has already advanced).</li>
     * </ul>
     *
     * <p>The triggering timer is already locked by the executor; this method
     * does NOT re-acquire the timer row lock.
     *
     * @param workflowId    the owning workflow instance id
     * @param timerId       the timer id that fired (already locked by the executor)
     * @param branchTokenId the branch token that owns this timer
     * @param tx            the active transaction
     * @return a {@link Future} resolving to the firing result
     */
    private Future<TimerFiringResult> doBranchTimerFired(
            WorkflowInstanceId workflowId, UUID timerId, UUID branchTokenId, long commandId, SqlClient tx) {
        // Lock the branch token (timer is already locked by executor — lock order preserved).
        return branchTokens.findByIdForUpdate(branchTokenId, tx).compose(optBranch -> {
            if (optBranch.isEmpty()) {
                return Future.succeededFuture(TimerFiringResult.STALE_NOOP);
            }
            BranchToken branch = optBranch.get();
            if (ForkJoinCoordinator.isBranchTerminal(branch.status())
                    || branch.status() == BranchStatus.SUPERSEDED
                    || branch.status() == BranchStatus.CANCELLED) {
                return forkJoin.appendBranchLateCallbackIgnored(workflowId, timerId, "timer-fired", tx)
                        .map(v -> TimerFiringResult.STALE_NOOP);
            }

            // Standalone timer: wait_type=TIMER, wait_key=timerId.
            if (branch.waitType() == WaitType.TIMER && timerId.toString().equals(branch.waitKey())) {
                return doBranchStandaloneTimerFired(workflowId, timerId, branch, commandId, tx);
            }

            // Signal-timeout: wait_type=SIGNAL, wait_aux_id=timerId.
            if (branch.waitType() == WaitType.SIGNAL && timerId.equals(branch.waitAuxId())) {
                return doBranchSignalTimeoutFired(workflowId, timerId, branch, commandId, tx);
            }

            // Slot mismatch — late fire.
            return forkJoin.appendBranchLateCallbackIgnored(workflowId, timerId, "timer-fired-slot-mismatch", tx)
                    .map(v -> TimerFiringResult.STALE_NOOP);
        });
    }

    /**
     * Fires a standalone branch timer: advances the branch token to the {@link TimerNode}'s
     * {@code nextStepId} and drives further branch transitions.
     *
     * @param workflowId the owning workflow instance id
     * @param timerId    the timer id that fired
     * @param branch     the locked branch token (status already validated)
     * @param tx         the active transaction
     * @return a {@link Future} resolving to {@link TimerFiringResult#APPLIED}
     */
    private Future<TimerFiringResult> doBranchStandaloneTimerFired(
            WorkflowInstanceId workflowId, UUID timerId, BranchToken branch, long commandId, SqlClient tx) {
        return instances.findById(workflowId, tx).compose(optInst -> {
            if (optInst.isEmpty()) {
                return Future.<TimerFiringResult>succeededFuture(TimerFiringResult.STALE_NOOP);
            }
            WorkflowInstance inst = optInst.get();
            RuntimeWorkflow rw;
            try {
                rw = resolveAndCheckPlanHash(inst);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            WorkflowNode node = rw.nodeById().get(branch.currentStepId());
            if (!(node instanceof TimerNode tn)) {
                return Future.<TimerFiringResult>failedFuture(
                        new WorkflowDefinitionException("Expected TimerNode at step '" + branch.currentStepId()
                                + "' for branch '" + branch.branchId() + "' during timerFired"));
            }
            Instant now = clock.instant();

            // Append BRANCH_TIMER_FIRED history, then advance branch and drive transitions.
            return history.nextSequence(workflowId, tx).compose(seq -> {
                String histPayload =
                        Json.encode(new TimerFiredHistoryPayload(tn.stepId(), timerId, now, tn.nextStepId()));
                return history.append(
                                new WorkflowHistoryEntry(
                                        workflowId, seq, WorkflowEntryType.BRANCH_TIMER_FIRED, histPayload, now),
                                tx)
                        .compose(v -> {
                            BranchToken cleared = WorkflowPayloads.clearBranchWait(branch, tn.nextStepId(), now);
                            return branchTokens
                                    .updateOptimistic(cleared, branch.version(), tx)
                                    .compose(rc -> {
                                        if (rc == 0) {
                                            return Future.<Void>failedFuture(new WorkflowConflictException(
                                                    "Optimistic concurrency conflict on branch token"
                                                            + " during standalone timer fire"));
                                        }
                                        // No state mutation on standalone timer fire — drive against
                                        // the unchanged parent instance.
                                        return forkJoin.driveAndMaybeJoin(inst, cleared, rw, commandId, tx);
                                    });
                        })
                        .map(v -> TimerFiringResult.APPLIED);
            });
        });
    }

    /**
     * Fires a branch signal-timeout timer: the timeout arrived before the signal. Clears the
     * branch wait and drives transitions via the {@link WaitSignalNode.TimeoutBranch}.
     *
     * @param workflowId the owning workflow instance id
     * @param timerId    the timeout timer id that fired
     * @param branch     the locked branch token (status already validated)
     * @param tx         the active transaction
     * @return a {@link Future} resolving to {@link TimerFiringResult#APPLIED}
     */
    private Future<TimerFiringResult> doBranchSignalTimeoutFired(
            WorkflowInstanceId workflowId, UUID timerId, BranchToken branch, long commandId, SqlClient tx) {
        return instances.findById(workflowId, tx).compose(optInst -> {
            if (optInst.isEmpty()) {
                return Future.<TimerFiringResult>succeededFuture(TimerFiringResult.STALE_NOOP);
            }
            WorkflowInstance inst = optInst.get();
            RuntimeWorkflow rw;
            try {
                rw = resolveAndCheckPlanHash(inst);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            WorkflowNode node = rw.nodeById().get(branch.currentStepId());
            if (!(node instanceof WaitSignalNode wsn) || wsn.timeout() == null) {
                return Future.<TimerFiringResult>failedFuture(new WorkflowDefinitionException(
                        "Expected WaitSignalNode with timeout at step '" + branch.currentStepId() + "' for branch '"
                                + branch.branchId() + "' during signal-timeout fire"));
            }
            WaitSignalNode.TimeoutBranch timeout = wsn.timeout();
            Instant now = clock.instant();

            // Apply onTimeoutMutator to parent state — mirrors single-path handleTimerFiredTimeout.
            Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
            String newStateJson;
            try {
                @SuppressWarnings("unchecked")
                var mutator = (java.util.function.Function<Object, Object>) (java.util.function.Function<?, ?>)
                        rw.callbacks().stateMutator(timeout.onTimeoutMutatorCallbackId());
                Object newState = mutator.apply(stateObj);
                newStateJson = Json.encode(newState);
            } catch (Exception e) {
                return Future.<TimerFiringResult>failedFuture(
                        new WorkflowDefinitionException("onTimeoutMutator threw for branch step '" + wsn.stepId()
                                + "' branch '" + branch.branchId() + "': " + e.getMessage()));
            }

            // If the mutator changed parent state, persist it (parent stays at WAITING/JOIN —
            // only state_json + version change).
            String finalNewStateJson = newStateJson;
            Future<WorkflowInstance> instUpdate;
            if (!finalNewStateJson.equals(inst.stateJson())) {
                long prevVersion = inst.version();
                WorkflowInstance updatedInst = inst.withVersion(prevVersion + 1)
                        .withState(finalNewStateJson)
                        .withUpdatedAt(now);
                instUpdate = instances
                        .updateOptimistic(updatedInst, prevVersion, tx)
                        .compose(rc -> {
                            if (rc == 0) {
                                return Future.<WorkflowInstance>failedFuture(new WorkflowConflictException(
                                        "Optimistic concurrency conflict applying" + " onTimeoutMutator to instance '"
                                                + inst.id().value() + "'"));
                            }
                            return decisions
                                    .record(inst, updatedInst, wsn.stepId(), "timeout", tx)
                                    .map(logged -> updatedInst);
                        });
            } else {
                instUpdate = Future.succeededFuture(inst);
            }

            // Append BRANCH_TIMER_TIMEOUT_FIRED history, then advance branch and drive transitions.
            return instUpdate.compose(
                    updatedInst -> history.nextSequence(workflowId, tx).compose(seq -> {
                        String histPayload = Json.encode(
                                new TimeoutHistoryPayload(wsn.stepId(), timerId, now, timeout.timeoutNextStepId()));
                        return history.append(
                                        new WorkflowHistoryEntry(
                                                workflowId,
                                                seq,
                                                WorkflowEntryType.BRANCH_TIMER_TIMEOUT_FIRED,
                                                histPayload,
                                                now),
                                        tx)
                                .compose(v -> {
                                    BranchToken cleared =
                                            WorkflowPayloads.clearBranchWait(branch, timeout.timeoutNextStepId(), now);
                                    return branchTokens
                                            .updateOptimistic(cleared, branch.version(), tx)
                                            .compose(rc -> {
                                                if (rc == 0) {
                                                    return Future.<Void>failedFuture(new WorkflowConflictException(
                                                            "Optimistic concurrency conflict on branch token"
                                                                    + " during signal-timeout fire"));
                                                }
                                                // updatedInst carries the post-onTimeoutMutator state and the
                                                // current subject ref; driveAndMaybeJoin reads both off it.
                                                return forkJoin.driveAndMaybeJoin(
                                                        updatedInst, cleared, rw, commandId, tx);
                                            });
                                })
                                .map(v -> TimerFiringResult.APPLIED);
                    }));
        });
    }

    // --- Timer helpers ---

    /**
     * Resolves the absolute fire instant for a {@link TimerSpec}.
     *
     * <p>Reached by {@link WorkflowTransitionDriver} through its lazy
     * {@code Provider<TimerLifecycleService>} when it handles a {@link TimerNode}, a
     * {@link WaitSignalNode} with a timeout, or a
     * {@link dev.vertique.workflow.plan.HumanTaskNode} with a due date.
     *
     * @param spec the timer spec to resolve
     * @param inst the current workflow instance (used to decode state for {@code FromState})
     * @param rw the resolved runtime workflow (used to look up the timer-resolver callback)
     * @return the resolved fire instant
     * @throws WorkflowDefinitionException if a {@code FromState} resolver callback throws or returns
     *     {@code null}
     */
    Instant resolveFireAt(TimerSpec spec, WorkflowInstance inst, RuntimeWorkflow rw) {
        return switch (spec) {
            case TimerSpec.At at -> at.fireAt();
            case TimerSpec.After after -> clock.instant().plus(after.delay());
            case TimerSpec.FromState fs -> {
                Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                Instant resolved;
                try {
                    @SuppressWarnings("unchecked")
                    var resolver = (java.util.function.Function<Object, Instant>)
                            (java.util.function.Function<?, ?>) rw.callbacks().timerResolver(fs.resolverCallbackId());
                    resolved = resolver.apply(stateObj);
                } catch (Exception e) {
                    throw new WorkflowDefinitionException("timerResolver threw for step: " + e.getMessage());
                }
                if (resolved == null) {
                    throw new WorkflowDefinitionException("timerResolver for callback id '"
                            + fs.resolverCallbackId().value()
                            + "' returned null; TimerSpec.FromState resolvers must return a non-null Instant");
                }
                yield resolved;
            }
        };
    }

    /**
     * Appends a {@code TIMER_CANCELLED} history entry for the given timer and cause.
     *
     * <p>Canonical owner of this helper (PRD-WF-006 §4, Slice C8). {@link SignalHandler} delegates to
     * this method for its signal-arrived timeout-timer cancellation. {@link TaskLifecycleService}
     * keeps its own private copy for its task-completion due-date cancel path to avoid a
     * {@code timer → task → timer} Dagger cycle (this service injects {@link TaskLifecycleService}
     * eagerly for the single-path {@code TASK} due-date dispatch).
     *
     * @param inst the workflow instance that owns the timer wait
     * @param timerId the id of the timer that was cancelled
     * @param cause the reason the timer was cancelled
     * @param tx the active transaction
     * @return a {@link Future} that completes when the history entry is inserted
     */
    Future<Void> appendTimerCancelledHistory(
            WorkflowInstance inst, UUID timerId, TimerCancelledCause cause, SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            Instant now = clock.instant();
            String histPayload =
                    Json.encode(new TimerCancelledHistoryPayload(inst.currentStepId(), timerId, now, cause));
            return history.append(
                    new WorkflowHistoryEntry(inst.id(), seq, WorkflowEntryType.TIMER_CANCELLED, histPayload, now), tx);
        });
    }

    /**
     * Resolves the active timer id for an instance that is in a WAITING state with an associated
     * timer. Returns the timer id when:
     * <ul>
     *   <li>{@code wait_type=TIMER} — the timer id is {@code UUID.fromString(waitKey())}.</li>
     *   <li>{@code wait_type=SIGNAL} and {@code waitAuxId() != null} — the timer id is
     *       {@code waitAuxId()}.</li>
     *   <li>{@code wait_type=TASK} and {@code waitAuxId() != null} — the due-date timer id is
     *       {@code waitAuxId()}.</li>
     * </ul>
     * Returns {@code null} in all other cases.
     *
     * @param inst the workflow instance to inspect
     * @return the active timer UUID, or {@code null} if none
     */
    static UUID resolveActiveTimerId(WorkflowInstance inst) {
        if (inst.waitType() == WaitType.TIMER) {
            try {
                return UUID.fromString(inst.waitKey());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (inst.waitType() == WaitType.SIGNAL && inst.waitAuxId() != null) {
            return inst.waitAuxId();
        }
        if (inst.waitType() == WaitType.TASK && inst.waitAuxId() != null) {
            return inst.waitAuxId();
        }
        return null;
    }

    /**
     * Resolves the runtime workflow for {@code inst} and enforces the plan-hash drift guard
     * (PRD-WF-002 round-1 fix H2 + round-2 single-path adjacent fix). Synchronous helper used by the
     * branch timer-fire paths that need both lookups before any row mutation; the caller wraps the
     * thrown exception in a failed Future. Mirrors the inline pattern used in the single-path
     * callbacks (e.g. {@link #handleTimerFiredStandalone}).
     *
     * @param inst the workflow instance whose definition + plan hash to resolve and check
     * @return the resolved {@link RuntimeWorkflow} for {@code inst}'s definition/version
     * @throws dev.vertique.workflow.exception.WorkflowVersionNotRunnableException if the local registry has no plan
     *     for the pinned version
     * @throws WorkflowPlanHashDriftException if the stored plan hash differs from the resolved plan
     */
    private RuntimeWorkflow resolveAndCheckPlanHash(WorkflowInstance inst) {
        RuntimeWorkflow rw = PinnedPlans.resolve(registry, inst);
        WorkflowPayloads.requirePlanHashMatches(inst, rw);
        return rw;
    }
}
