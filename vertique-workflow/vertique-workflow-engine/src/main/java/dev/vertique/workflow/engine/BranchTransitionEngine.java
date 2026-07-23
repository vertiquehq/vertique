// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.DecisionNode;
import dev.vertique.workflow.plan.FailNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec;
import dev.vertique.workflow.plan.ReminderSpec;
import dev.vertique.workflow.plan.ServiceDispatchNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.payload.TaskCreatedHistoryPayload;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives transitions for a single fork-group branch (PRD-WF-002 §D3, §D6, §D8).
 *
 * <p>Branches own their own optimistic-concurrency token ({@link BranchToken#version()}); sibling
 * branches do not contend on each other. The engine drives a branch through nodes until one of:
 * <ul>
 *   <li>the branch reaches a durable wait (signal / timer / task);</li>
 *   <li>the branch reaches a terminal status (COMPLETED / FAILED);</li>
 *   <li>the branch records a side-effect intent that the dispatch-dedup gate skipped because the
 *       same intent was already recorded by an earlier attempt (FR-WF-PAR-038).</li>
 * </ul>
 *
 * <p>Service-dispatch handling INSERTS a {@code dispatch}-kind row into {@code workflow_dedup}
 * INSIDE this engine (NOT inside {@code OutboxSideEffectRecorder}); the recorder remains a pure
 * outbox adapter so the {@code workflow-services} → {@code workflow-postgresql} module-boundary
 * direction is preserved (round-3 finding R3-1).
 *
 * <p>Supports {@link ServiceDispatchNode}, {@link WaitSignalNode} (with and without timeout),
 * {@link TimerNode}, {@link HumanTaskNode}, {@link DecisionNode}, {@link CompleteNode}, and
 * {@link FailNode}. Compensation orchestration is handled separately.
 */
@Singleton
final class BranchTransitionEngine {

    private final BranchTokenRepository<SqlClient> branchTokens;
    private final WorkflowDedupRepository<SqlClient> dedup;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final RecorderRouter recorders;
    private final TaskStore<SqlClient> taskStore;
    private final Clock clock;

    /**
     * Durable context propagator used at branch-dispatch to bind the persisted
     * {@link BranchToken#metadata()} into the holder for the dispatch lifetime via
     * {@code bindFrom(..., DispatchBoundary.WORKFLOW)}. The scope is closed on
     * {@code Future.eventually(...)} so the binding survives the async {@code recorders.route(...)}
     * chain (FR-CTX-178).
     */
    private final dev.vertique.context.DurableContextPropagator propagator;

    /**
     * Substrate lifecycle helper. When provided, branch-drive uses
     * {@link dev.vertique.context.InboundExecutionContextScope#installDurable installDurable(...)}
     * which runs {@code propagator.bindFrom} AND every registered
     * {@link InboundContextInitializer} (e.g.
     * {@code CorrelationContextSeeder}) inside one composed scope. {@code null} falls back to the
     * plain {@code propagator.bindFrom} path.
     */
    private final dev.vertique.context.InboundExecutionContextScope inboundExecScope;

    /**
     * Creates a new engine.
     *
     * @param branchTokens branch-token repository
     * @param dedup        dedup repository (used for the dispatch-dedup gate)
     * @param history      history repository (used to assign sequence numbers + append entries)
     * @param recorders    side-effect recorder router
     * @param taskStore    task store (used for branch-owned human-task creation)
     * @param clock        wall-clock used for {@code updated_at} and decision timestamps
     * @param propagator   durable context propagator; {@code null} is tolerated for legacy test
     *                     ctors that do not exercise branch-dispatch durable binding
     * @param inboundExecScope substrate lifecycle helper; {@code null} for legacy callers that do
     *                         not need first-ingress initializers to run on branch drive
     */
    @Inject
    BranchTransitionEngine(
            BranchTokenRepository<SqlClient> branchTokens,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowHistoryRepository<SqlClient> history,
            RecorderRouter recorders,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            dev.vertique.context.DurableContextPropagator propagator,
            dev.vertique.context.InboundExecutionContextScope inboundExecScope) {
        this.branchTokens = branchTokens;
        this.dedup = dedup;
        this.history = history;
        this.recorders = recorders;
        this.taskStore = taskStore;
        this.clock = clock;
        this.propagator = propagator;
        this.inboundExecScope = inboundExecScope;
    }

    /**
     * Constructor without the inbound-execution helper — exists for legacy callers that don't
     * need first-ingress initializers to fire on branch dispatch.
     */
    BranchTransitionEngine(
            BranchTokenRepository<SqlClient> branchTokens,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowHistoryRepository<SqlClient> history,
            RecorderRouter recorders,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            dev.vertique.context.DurableContextPropagator propagator) {
        this(branchTokens, dedup, history, recorders, taskStore, clock, propagator, null);
    }

    /**
     * Test-only ctor without a propagator — branch-dispatch durable binding is skipped. Tests
     * that need durable capture must use the {@link Inject}'d ctor.
     */
    BranchTransitionEngine(
            BranchTokenRepository<SqlClient> branchTokens,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowHistoryRepository<SqlClient> history,
            RecorderRouter recorders,
            TaskStore<SqlClient> taskStore,
            Clock clock) {
        this(branchTokens, dedup, history, recorders, taskStore, clock, null, null);
    }

    // --- Public entry points ---

    /**
     * Drives the given branch token through nodes until it reaches a durable wait, terminal
     * state, or records a side-effect intent.
     *
     * <p>{@code parentStateJson} is the JSON-encoded {@code state_json} of the parent workflow
     * instance at the moment of dispatch. Branch payload factories, decision resolvers, and fail
     * message factories receive this state (decoded via {@link RuntimeWorkflow#stateType()}); the
     * branch's own {@link BranchToken#resultJson()} is reserved for fan-in reduction. May be
     * {@code null} for state-less workflows; the helper falls back to an empty JSON object.
     *
     * <p>{@code parentSubjectVersion} is the version string from the parent instance's
     * {@link dev.vertique.workflow.state.WorkflowInstance#subjectRef()} at dispatch time, or
     * {@code null} when the instance has no subject ref. This value is snapshotted on any
     * {@link HumanTaskNode} task row created during this drive pass, and is checked at task
     * creation time when {@link HumanTaskNode#requireVersionStability()} is {@code true}.
     *
     * @param token the branch token to drive
     * @param parentStateJson the parent instance's {@code state_json} at dispatch time
     * @param parentSubjectVersion the parent instance's subject-ref version at dispatch time,
     *     or {@code null} if the instance has no subject ref
     * @param rw the resolved runtime workflow (defines the plan + callbacks)
     * @param tx the active transaction
     * @param effectiveBaseOverride when non-null, the already-computed effective durable context
     *     document to bind for this drive instead of {@link BranchToken#metadata()} (Contract
     *     Appendix C3). Branch-recovery and branch-targeted explicit-carrier callers pass the
     *     merged {@code carrier.merge(instance.metadata(), MergePolicy.CALLER_WINS)} document here;
     *     every other call site passes {@code null}, preserving today's behavior of binding
     *     {@code token.metadata()} unchanged
     * @return a {@link Future} of the post-drive {@link BranchToken} snapshot
     */
    Future<BranchToken> driveBranchTransitions(
            BranchToken token,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx,
            @Nullable DurableMetadata effectiveBaseOverride) {
        // Bind the branch's persisted durable metadata for the entire drive so every transition
        // — service dispatch, timer side-effects, signals, tasks, decisions — sees exactly the
        // durable context the branch was forked under. bindFrom is authoritative: every
        // registered durable type absent from token.metadata() is cleared for the scope's
        // lifetime so that a subsequent mergeCaptured(...) inside the drive (e.g. timer-create
        // capturing into the underlying delayed-job's metadata column) cannot accidentally pull
        // in ambient context that did not cross the workflow boundary. Production callers reach
        // this on a duplicated context — the dispatcher's per-message duplicate is preserved
        // through pool.withTransaction by vertx-sql-client 5.x. Per FR-CTX-157b, bindFrom is
        // strict: it throws on a non-duplicated Vert.x context; tests that drive the engine
        // directly must establish a duplicate before entering pool.withTransaction.
        //
        // effectiveBaseOverride (Contract Appendix C3): when the caller has already computed the
        // merged carrier-authoritative + instance-fill document (branch recovery, branch-targeted
        // explicit-carrier signal), bind that instead of the raw token.metadata() fallback below.
        // token.metadata() is never null — BranchToken's compact constructor normalizes a null
        // metadata argument to DurableMetadata.empty() — so no empty-doc fallback is needed here.
        DurableMetadata meta = effectiveBaseOverride != null ? effectiveBaseOverride : token.metadata();
        if (inboundExecScope != null) {
            // Substrate lifecycle helper composes propagator.bindFrom AND any registered
            // InboundContextInitializers (e.g. CorrelationContextSeeder) in one scope —
            // identical bindFrom semantics, plus first-ingress seeding for typed contexts
            // that have no durable-encoded value at the workflow boundary. installDurableAndRun
            // implements the install/try/close shape shared with WorkflowContextBinder.withBound
            // and PgWorkflowBranchRecoveryService.withBranchDurableBound.
            return inboundExecScope.installDurableAndRun(
                    meta, DispatchBoundary.WORKFLOW, () -> step(token, parentStateJson, parentSubjectVersion, rw, tx));
        }
        // Test-support fallback: no substrate lifecycle helper wired (legacy ctor) — bind via the
        // plain propagator when present, or run fully unbound when neither is wired.
        ContextHolder.Scope scope = null;
        try {
            if (propagator != null) {
                scope = propagator.bindFrom(meta, DispatchBoundary.WORKFLOW);
            }
            final ContextHolder.Scope finalScope = scope;
            Future<BranchToken> driveFuture = step(token, parentStateJson, parentSubjectVersion, rw, tx);
            return finalScope == null
                    ? driveFuture
                    : driveFuture.eventually(() -> {
                        finalScope.close();
                        return Future.succeededFuture();
                    });
        } catch (RuntimeException e) {
            if (scope != null) {
                scope.close();
            }
            return Future.failedFuture(e);
        }
    }

    // --- Step dispatcher ---

    private Future<BranchToken> step(
            BranchToken token,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        if (token.status() != BranchStatus.RUNNING) {
            // Caller handed us a token that is no longer eligible to advance (WAITING /
            // COMPLETED / FAILED / RETRY_SCHEDULED / SUPERSEDED / etc.). Return as-is so the
            // caller can decide whether to evaluate the join.
            return Future.succeededFuture(token);
        }
        WorkflowNode node = rw.nodeById().get(token.currentStepId());
        if (node == null) {
            return Future.failedFuture(new WorkflowDefinitionException("Branch step '" + token.currentStepId()
                    + "' not found in plan '" + rw.plan().definitionId() + "'"));
        }
        if (node instanceof ServiceDispatchNode sdn) {
            return branchHandleServiceDispatch(token, sdn, parentStateJson, parentSubjectVersion, rw, tx);
        }
        if (node instanceof WaitSignalNode wsn) {
            if (wsn.timeout() != null) {
                return branchHandleWaitSignalWithTimeout(token, wsn, parentStateJson, rw, tx);
            }
            return branchHandleWaitSignal(token, wsn, tx);
        }
        if (node instanceof HumanTaskNode htn) {
            return branchHandleHumanTaskNode(token, htn, parentStateJson, parentSubjectVersion, rw, tx);
        }
        if (node instanceof TimerNode tn) {
            return branchHandleTimerNode(token, tn, parentStateJson, rw, tx);
        }
        if (node instanceof DecisionNode dn) {
            return branchHandleDecision(token, dn, parentStateJson, parentSubjectVersion, rw, tx);
        }
        if (node instanceof CompleteNode cn) {
            return branchHandleComplete(token, cn, tx);
        }
        if (node instanceof FailNode fn) {
            return branchHandleFail(token, fn, parentStateJson, parentSubjectVersion, rw, tx);
        }
        return Future.failedFuture(new UnsupportedOperationException(
                "Branch handler for " + node.getClass().getSimpleName() + " is not yet implemented"));
    }

    // --- ServiceDispatch (with dispatch-dedup gate) ---

    private Future<BranchToken> branchHandleServiceDispatch(
            BranchToken token,
            ServiceDispatchNode sdn,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        // PRD-WF-002 §D6: dispatch-dedup gate runs FIRST so retried branch transitions skip
        // re-emission when an earlier attempt already recorded the intent.
        String scope = WorkflowDedupScopes.dispatchScope(token.workflowId());
        String key = WorkflowDedupScopes.dispatchKey(
                token.forkStepId(), token.branchId(), sdn.stepId(), sdn.targetId(), WorkflowDedupScopes.DISPATCH_KIND);
        return dedup.claimOrResolveDispatch(token.workflowId(), scope, key, tx).compose(claimed -> {
            if (!claimed) {
                // Already recorded — append BRANCH_RESUMED noting the deduped dispatch and
                // advance the branch's currentStepId.
                return advanceBranchSkippingDispatch(token, sdn, parentStateJson, parentSubjectVersion, rw, tx);
            }
            return runBranchDispatch(token, sdn, parentStateJson, parentSubjectVersion, rw, tx);
        });
    }

    private Future<BranchToken> runBranchDispatch(
            BranchToken token,
            ServiceDispatchNode sdn,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        // The branch's durable metadata is already bound by driveBranchTransitions (the public
        // entry point) for the entire drive lifetime — see driveBranchTransitions's
        // propagator.bindFrom + Future.eventually pattern. Recorders that capture ambient durable
        // context via mergeCaptured (outbox, timer recorder) therefore see the branch's
        // persisted metadata throughout this method without an additional bind here.
        Object payload;
        try {
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<Object, Object>)
                    (java.util.function.Function<?, ?>) rw.callbacks().payloadFactory(sdn.payloadCallbackId());
            // Branch dispatches operate on the parent instance's state — the branch reducer
            // combines per-branch results at fan-in time, so branches don't carry their own
            // execution-state slice in V1.
            Object stateObj = decodeParentState(parentStateJson, rw);
            payload = factory.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException("payloadFactory threw for branch '"
                    + token.branchId() + "' step '" + sdn.stepId() + "': " + e.getMessage()));
        }
        return history.nextSequence(token.workflowId(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.SERVICE,
                    sdn.targetId(),
                    payload,
                    Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            token.workflowId(),
                            seq,
                            rw.plan().definitionId(),
                            sdn.stepId(),
                            token.id(),
                            token.forkStepId(),
                            token.branchId()));
            return recorders
                    .route(intent, tx)
                    .compose(result -> {
                        String histPayload = Json.encode(new SideEffectRecordedHistoryPayload(
                                sdn.stepId(), sdn.targetId(), token.id(), token.forkStepId(), token.branchId()));
                        return history.append(
                                new WorkflowHistoryEntry(
                                        token.workflowId(),
                                        seq,
                                        WorkflowEntryType.SIDE_EFFECT_RECORDED,
                                        histPayload,
                                        clock.instant()),
                                tx);
                    })
                    .compose(v ->
                            advanceBranchSkippingDispatch(token, sdn, parentStateJson, parentSubjectVersion, rw, tx));
        });
    }

    /**
     * Advances the branch token from a {@link ServiceDispatchNode} to its next step and continues
     * driving transitions from the next step. The dispatch intent has already been recorded; this
     * method only moves the execution pointer and drives the next node inline.
     *
     * @param token               the branch token currently at the dispatch step
     * @param sdn                 the service dispatch node
     * @param parentStateJson     the parent instance's state JSON (passed through to the next step)
     * @param parentSubjectVersion the parent instance's subject-ref version (passed through)
     * @param rw                  the resolved runtime workflow
     * @param tx                  the active transaction
     * @return a {@link Future} of the post-advance branch token
     */
    private Future<BranchToken> advanceBranchSkippingDispatch(
            BranchToken token,
            ServiceDispatchNode sdn,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        BranchToken advanced = token.withStep(sdn.nextStepId(), token.version() + 1, clock.instant());
        return casUpdate(advanced, token.version(), tx, "ServiceDispatch advance", sdn.stepId())
                .compose(t -> step(t, parentStateJson, parentSubjectVersion, rw, tx));
    }

    // --- WaitSignal (no timeout) ---

    private Future<BranchToken> branchHandleWaitSignal(BranchToken token, WaitSignalNode wsn, SqlClient tx) {
        BranchToken waiting =
                token.withWait(WaitType.SIGNAL, wsn.signalName(), null, token.version() + 1, clock.instant());
        return casUpdate(waiting, token.version(), tx, "WaitSignal", wsn.stepId())
                .compose(t -> appendBranchHistory(t, WorkflowEntryType.BRANCH_WAIT, wsn.stepId(), tx));
    }

    // --- WaitSignal with timeout ---

    /**
     * Handles a {@link WaitSignalNode} with a timeout in a branch context.
     *
     * <p>Schedules the timeout timer with branch identity set on the correlation (so the timer row
     * carries {@code branch_token_id / fork_step_id / branch_id}), then transitions the branch
     * token to {@code WAITING}, {@code wait_type=SIGNAL}, {@code wait_key=signalName},
     * {@code wait_aux_id=timeoutTimerId}. Appends
     * {@link WorkflowEntryType#BRANCH_SIGNAL_TIMEOUT_SCHEDULED}.
     *
     * @param token           the current branch token
     * @param wsn             the {@link WaitSignalNode} with a non-null timeout
     * @param parentStateJson the parent instance's {@code state_json} (used by
     *     {@link TimerSpec.FromState} resolvers)
     * @param rw              the resolved runtime workflow
     * @param tx              the active transaction
     * @return a {@link Future} of the post-transition branch token
     */
    private Future<BranchToken> branchHandleWaitSignalWithTimeout(
            BranchToken token, WaitSignalNode wsn, String parentStateJson, RuntimeWorkflow rw, SqlClient tx) {
        Instant fireAt = resolveFireAt(wsn.timeout().timeout(), parentStateJson, rw);
        return history.nextSequence(token.workflowId(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    wsn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.SIGNAL_TIMEOUT, null),
                    Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            token.workflowId(),
                            seq,
                            rw.plan().definitionId(),
                            wsn.stepId(),
                            token.id(),
                            token.forkStepId(),
                            token.branchId()));
            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer for branch SIGNAL_TIMEOUT; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();
                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(wsn.stepId(), timerId, fireAt, TimerPurpose.SIGNAL_TIMEOUT));
                return history.append(
                                new WorkflowHistoryEntry(
                                        token.workflowId(),
                                        seq,
                                        WorkflowEntryType.BRANCH_SIGNAL_TIMEOUT_SCHEDULED,
                                        histPayload,
                                        clock.instant()),
                                tx)
                        .compose(v -> {
                            BranchToken waiting = token.withWait(
                                    WaitType.SIGNAL, wsn.signalName(), timerId, token.version() + 1, clock.instant());
                            return casUpdate(waiting, token.version(), tx, "WaitSignalWithTimeout", wsn.stepId());
                        });
            });
        });
    }

    // --- HumanTaskNode ---

    /**
     * Handles a {@link HumanTaskNode} in a branch context.
     *
     * <p>Resolves the assignment, snapshots {@code parentSubjectVersion} on the task row (for audit
     * and version-stability checking at completion), inserts a task row with branch identity,
     * optionally schedules a due-date timer (and reminders) with branch-correlated intents, then
     * transitions the branch token to {@code WAITING}, {@code wait_type=TASK},
     * {@code wait_key=taskId}. Appends {@link WorkflowEntryType#BRANCH_TASK_CREATED}.
     *
     * <p>Fails fast with {@link WorkflowSubjectVersionUnavailableException} when
     * {@link HumanTaskNode#requireVersionStability()} is {@code true} and
     * {@code parentSubjectVersion} is {@code null}, mirroring the single-path guard in
     * {@link WorkflowEngine#handleHumanTaskNode}.
     *
     * @param token                the current branch token
     * @param htn                  the human-task node
     * @param parentStateJson      the parent instance's {@code state_json}
     * @param parentSubjectVersion the parent instance's subject-ref version, or {@code null}
     * @param rw                   the resolved runtime workflow
     * @param tx                   the active transaction
     * @return a {@link Future} of the post-transition branch token
     */
    private Future<BranchToken> branchHandleHumanTaskNode(
            BranchToken token,
            HumanTaskNode htn,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        // Fail fast if version stability is required but the parent has no versioned subject ref.
        if (htn.requireVersionStability() && parentSubjectVersion == null) {
            return Future.failedFuture(new WorkflowSubjectVersionUnavailableException(
                    "Workflow '" + rw.plan().definitionId() + "' branch '" + token.branchId()
                            + "' reaches stability-required task '" + htn.stepId()
                            + "' without a versioned subject ref"));
        }

        TaskAssignment assignment;
        try {
            assignment = resolveAssignment(htn.assignment(), parentStateJson, rw);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        UUID taskId = UUID.randomUUID();
        List<TaskDecisionDescriptor> descriptors = htn.decisions().stream()
                .map(d -> new TaskDecisionDescriptor(d.name(), d.payloadTypeName(), d.nextStepId()))
                .toList();

        Instant now = clock.instant();

        if (htn.dueDate() != null) {
            return branchHandleHumanTaskNodeWithDueDate(
                    token, htn, parentStateJson, rw, tx, assignment, taskId, descriptors, parentSubjectVersion, now);
        }

        // No due-date — simple branch task wait.
        TaskRecord record = buildBranchTaskRecord(
                taskId, token, htn, assignment, descriptors, null, null, parentSubjectVersion, now);
        return taskStore.insertOpen(record, tx).compose(v -> {
            BranchToken waiting =
                    token.withWait(WaitType.TASK, taskId.toString(), null, token.version() + 1, clock.instant());
            return casUpdate(waiting, token.version(), tx, "HumanTaskNode", htn.stepId())
                    .compose(t -> appendBranchTaskCreatedHistory(t, htn.stepId(), taskId, assignment, null, tx))
                    .compose(t -> scheduleBranchReminderTimers(t, htn, taskId, rw, now, tx));
        });
    }

    /**
     * Variant of {@link #branchHandleHumanTaskNode} that also schedules a due-date timer before
     * inserting the task row.
     *
     * @param token                the current branch token
     * @param htn                  the node with a non-null {@code dueDate()} triplet
     * @param rw                   the resolved runtime workflow
     * @param tx                   the active transaction
     * @param assignment           the resolved literal task assignment
     * @param taskId               the new task UUID
     * @param descriptors          the decision descriptors to snapshot
     * @param parentSubjectVersion the parent instance's subject-ref version, or {@code null}
     * @param now                  the task-creation instant
     * @return a {@link Future} of the post-transition branch token
     */
    private Future<BranchToken> branchHandleHumanTaskNodeWithDueDate(
            BranchToken token,
            HumanTaskNode htn,
            String parentStateJson,
            RuntimeWorkflow rw,
            SqlClient tx,
            TaskAssignment assignment,
            UUID taskId,
            List<TaskDecisionDescriptor> descriptors,
            @Nullable String parentSubjectVersion,
            Instant now) {
        Instant fireAt = resolveFireAt(htn.dueDate(), parentStateJson, rw);
        return history.nextSequence(token.workflowId(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    htn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.TASK_DUE, taskId),
                    Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            token.workflowId(),
                            seq,
                            rw.plan().definitionId(),
                            htn.stepId(),
                            token.id(),
                            token.forkStepId(),
                            token.branchId()));
            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer for branch TASK_DUE; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID dueDateTimerId = timerResult.timerId();
                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(htn.stepId(), dueDateTimerId, fireAt, TimerPurpose.TASK_DUE));
                return history.append(
                                new WorkflowHistoryEntry(
                                        token.workflowId(),
                                        seq,
                                        WorkflowEntryType.TIMER_SCHEDULED,
                                        histPayload,
                                        clock.instant()),
                                tx)
                        .compose(v -> {
                            TaskRecord record = buildBranchTaskRecord(
                                    taskId,
                                    token,
                                    htn,
                                    assignment,
                                    descriptors,
                                    fireAt,
                                    dueDateTimerId,
                                    parentSubjectVersion,
                                    now);
                            return taskStore.insertOpen(record, tx);
                        })
                        .compose(v -> {
                            BranchToken waiting = token.withWait(
                                    WaitType.TASK,
                                    taskId.toString(),
                                    dueDateTimerId,
                                    token.version() + 1,
                                    clock.instant());
                            return casUpdate(waiting, token.version(), tx, "HumanTaskNodeWithDueDate", htn.stepId())
                                    .compose(t -> appendBranchTaskCreatedHistory(
                                            t, htn.stepId(), taskId, assignment, fireAt, tx))
                                    .compose(t -> scheduleBranchReminderTimers(t, htn, taskId, rw, now, tx));
                        });
            });
        });
    }

    // --- TimerNode ---

    /**
     * Handles a {@link TimerNode} in a branch context.
     *
     * <p>Records the timer intent with branch identity on the correlation, transitions the branch
     * token to {@code WAITING}, {@code wait_type=TIMER}, {@code wait_key=timerId}. Appends
     * {@link WorkflowEntryType#BRANCH_TIMER_SCHEDULED}.
     *
     * @param token the current branch token
     * @param tn    the timer node
     * @param rw    the resolved runtime workflow
     * @param tx    the active transaction
     * @return a {@link Future} of the post-transition branch token
     */
    private Future<BranchToken> branchHandleTimerNode(
            BranchToken token, TimerNode tn, String parentStateJson, RuntimeWorkflow rw, SqlClient tx) {
        Instant fireAt = resolveFireAt(tn.spec(), parentStateJson, rw);
        return history.nextSequence(token.workflowId(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    tn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.STANDALONE, null),
                    Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            token.workflowId(),
                            seq,
                            rw.plan().definitionId(),
                            tn.stepId(),
                            token.id(),
                            token.forkStepId(),
                            token.branchId()));
            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer for branch STANDALONE; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();
                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(tn.stepId(), timerId, fireAt, TimerPurpose.STANDALONE));
                return history.append(
                                new WorkflowHistoryEntry(
                                        token.workflowId(),
                                        seq,
                                        WorkflowEntryType.BRANCH_TIMER_SCHEDULED,
                                        histPayload,
                                        clock.instant()),
                                tx)
                        .compose(v -> {
                            BranchToken waiting = token.withWait(
                                    WaitType.TIMER, timerId.toString(), null, token.version() + 1, clock.instant());
                            return casUpdate(waiting, token.version(), tx, "TimerNode", tn.stepId());
                        });
            });
        });
    }

    // --- Decision ---

    private Future<BranchToken> branchHandleDecision(
            BranchToken token,
            DecisionNode dn,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        String nextStepId;
        try {
            @SuppressWarnings("unchecked")
            var resolver = (java.util.function.Function<Object, String>) (java.util.function.Function<?, ?>)
                    rw.callbacks().decisionResolver(dn.nextStepResolverCallbackId());
            Object stateObj = decodeParentState(parentStateJson, rw);
            nextStepId = resolver.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException("decisionResolver threw for branch '"
                    + token.branchId() + "' step '" + dn.stepId() + "': " + e.getMessage()));
        }
        if (nextStepId == null || !rw.nodeById().containsKey(nextStepId)) {
            return Future.failedFuture(new WorkflowDefinitionException("DecisionNode '" + dn.stepId()
                    + "' resolver returned unknown step id '" + nextStepId + "' for branch '"
                    + token.branchId() + "'"));
        }
        BranchToken advanced = token.withStep(nextStepId, token.version() + 1, clock.instant());
        return casUpdate(advanced, token.version(), tx, "Decision", dn.stepId())
                .compose(t -> step(t, parentStateJson, parentSubjectVersion, rw, tx));
    }

    // --- Complete (terminal success) ---

    private Future<BranchToken> branchHandleComplete(BranchToken token, CompleteNode cn, SqlClient tx) {
        BranchToken completed = token.withStatus(BranchStatus.COMPLETED, token.version() + 1, clock.instant());
        return casUpdate(completed, token.version(), tx, "Complete", cn.stepId())
                .compose(t -> appendBranchHistory(t, WorkflowEntryType.BRANCH_COMPLETED, cn.stepId(), tx));
    }

    // --- Fail (terminal failure) ---

    private Future<BranchToken> branchHandleFail(
            BranchToken token,
            FailNode fn,
            String parentStateJson,
            @Nullable String parentSubjectVersion,
            RuntimeWorkflow rw,
            SqlClient tx) {
        String message;
        try {
            @SuppressWarnings("unchecked")
            var factory = (java.util.function.Function<Object, String>) (java.util.function.Function<?, ?>)
                    rw.callbacks().failMessageFactory(fn.messageFactoryCallbackId());
            Object stateObj = decodeParentState(parentStateJson, rw);
            message = factory.apply(stateObj);
        } catch (Exception e) {
            message = "fail-message factory threw: " + e.getMessage();
        }
        Instant now = clock.instant();
        BranchToken failed = new BranchToken(
                token.id(),
                token.workflowId(),
                token.forkStepId(),
                token.branchId(),
                token.currentStepId(),
                BranchStatus.FAILED,
                null,
                null,
                null,
                token.resultJson(),
                fn.errorType(),
                message,
                token.attemptCount(),
                token.maxAttempts(),
                null,
                fn.errorType(),
                message,
                now,
                token.version() + 1,
                token.createdAt(),
                now,
                token.metadata());
        return casUpdate(failed, token.version(), tx, "Fail", fn.stepId())
                .compose(t -> appendBranchHistory(t, WorkflowEntryType.BRANCH_FAILED, fn.stepId(), tx));
    }

    // --- Helpers ---

    private Future<BranchToken> casUpdate(
            BranchToken updated, long expectedVersion, SqlClient tx, String label, String stepId) {
        return branchTokens.updateOptimistic(updated, expectedVersion, tx).compose(rowCount -> {
            if (rowCount == 0) {
                return Future.failedFuture(new WorkflowConflictException("Optimistic concurrency conflict on branch '"
                        + updated.branchId() + "' (" + label + " at step '" + stepId + "')"));
            }
            return Future.succeededFuture(updated);
        });
    }

    private Future<BranchToken> appendBranchHistory(
            BranchToken token, WorkflowEntryType entryType, String stepId, SqlClient tx) {
        JsonObject payload = new JsonObject()
                .put("branchTokenId", token.id().toString())
                .put("forkStepId", token.forkStepId())
                .put("branchId", token.branchId())
                .put("stepId", stepId);
        return history.nextSequence(token.workflowId(), tx)
                .compose(seq -> history.append(
                        new WorkflowHistoryEntry(token.workflowId(), seq, entryType, payload.encode(), clock.instant()),
                        tx))
                .map(token);
    }

    /**
     * Decodes the parent workflow instance's {@code state_json} into an instance of the runtime
     * state type so branch callbacks (payload factories, decision resolvers, fail-message
     * factories) see the same state the engine sees. Branches do not own a separate execution
     * state slice in V1 — {@link BranchToken#resultJson()} is reserved for fan-in reduction.
     *
     * <p>If {@code parentStateJson} is null, callbacks see an empty JSON-decoded object so
     * state-less workflows still succeed.
     *
     * @param parentStateJson the parent instance's {@code state_json} (may be null for state-less
     *     workflows; callers in this engine always pass a non-null document)
     * @param rw the runtime workflow (used for state-type resolution)
     * @return an instance of the runtime state type, or an empty JSON-decoded object when
     *     {@code parentStateJson} is null
     */
    private static Object decodeParentState(String parentStateJson, RuntimeWorkflow rw) {
        String json = parentStateJson != null ? parentStateJson : "{}";
        return Json.decodeValue(Buffer.buffer(json), rw.stateType());
    }

    // --- Slice 4 helpers ---

    /**
     * Resolves the absolute fire instant for a {@link TimerSpec} in a branch context, using the
     * parent instance's state for {@link TimerSpec.FromState} resolvers.
     *
     * @param spec            the timer spec to resolve
     * @param parentStateJson the parent instance's {@code state_json} at the moment of resolution
     * @param rw              the resolved runtime workflow (used to look up the timer-resolver callback)
     * @return the resolved fire instant
     * @throws WorkflowDefinitionException if a {@link TimerSpec.FromState} resolver callback throws
     */
    private Instant resolveFireAt(TimerSpec spec, String parentStateJson, RuntimeWorkflow rw) {
        return switch (spec) {
            case TimerSpec.At at -> at.fireAt();
            case TimerSpec.After after -> clock.instant().plus(after.delay());
            case TimerSpec.FromState fs -> {
                // Branches resolve TimerSpec.FromState against the parent instance's state — the
                // same input the single-path uses (WorkflowEngine.handleTimerNode and related).
                Object stateObj = Json.decodeValue(
                        Buffer.buffer(parentStateJson == null ? "{}" : parentStateJson), rw.stateType());
                Instant resolved;
                try {
                    @SuppressWarnings("unchecked")
                    var resolver = (java.util.function.Function<Object, Instant>)
                            (java.util.function.Function<?, ?>) rw.callbacks().timerResolver(fs.resolverCallbackId());
                    resolved = resolver.apply(stateObj);
                } catch (Exception e) {
                    throw new WorkflowDefinitionException("timerResolver threw for branch step: " + e.getMessage());
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
     * Resolves an {@link AssignmentSpec} to a concrete {@link TaskAssignment} for a branch-owned
     * task.
     *
     * @param spec            the assignment spec from the plan node
     * @param parentStateJson the parent instance's {@code state_json} (used for state-based specs)
     * @param rw              the resolved runtime workflow
     * @return the resolved literal assignment
     * @throws WorkflowDefinitionException if a state-based resolver callback throws
     */
    private TaskAssignment resolveAssignment(AssignmentSpec spec, String parentStateJson, RuntimeWorkflow rw) {
        return switch (spec) {
            case AssignmentSpec.User u -> new TaskAssignment.User(u.userId());
            case AssignmentSpec.Role r -> new TaskAssignment.Role(r.roleId());
            case AssignmentSpec.Queue q -> new TaskAssignment.Queue(q.queueName());
            case AssignmentSpec.UserFromState ufs -> {
                Object state = decodeParentState(parentStateJson, rw);
                @SuppressWarnings("unchecked")
                var resolver = (java.util.function.Function<Object, TaskAssignment>) (java.util.function.Function<?, ?>)
                        rw.callbacks().taskAssignmentResolver(ufs.resolverCallbackId());
                yield resolver.apply(state);
            }
            case AssignmentSpec.RoleFromState rfs -> {
                Object state = decodeParentState(parentStateJson, rw);
                @SuppressWarnings("unchecked")
                var resolver = (java.util.function.Function<Object, TaskAssignment>) (java.util.function.Function<?, ?>)
                        rw.callbacks().taskAssignmentResolver(rfs.resolverCallbackId());
                yield resolver.apply(state);
            }
            case AssignmentSpec.QueueFromState qfs -> {
                Object state = decodeParentState(parentStateJson, rw);
                @SuppressWarnings("unchecked")
                var resolver = (java.util.function.Function<Object, TaskAssignment>) (java.util.function.Function<?, ?>)
                        rw.callbacks().taskAssignmentResolver(qfs.resolverCallbackId());
                yield resolver.apply(state);
            }
        };
    }

    /**
     * Builds a {@link TaskRecord} for a branch-owned human task, populating branch identity fields
     * and snapshotting the parent instance's subject-ref version.
     *
     * @param taskId               the new task UUID
     * @param token                the branch token (supplies workflow id and branch identity)
     * @param htn                  the human-task node
     * @param assignment           the resolved literal assignment
     * @param descriptors          the decision descriptors snapshot
     * @param dueAt                the due-date instant, or {@code null}
     * @param dueDateTimerId       the due-date timer id, or {@code null}
     * @param parentSubjectVersion the parent instance's subject-ref version at task-creation time,
     *     or {@code null} when the instance has no subject ref
     * @param now                  the task-creation instant
     * @return the constructed {@link TaskRecord}
     */
    private static TaskRecord buildBranchTaskRecord(
            UUID taskId,
            BranchToken token,
            HumanTaskNode htn,
            TaskAssignment assignment,
            List<TaskDecisionDescriptor> descriptors,
            @Nullable Instant dueAt,
            @Nullable UUID dueDateTimerId,
            @Nullable String parentSubjectVersion,
            Instant now) {
        return new TaskRecord(
                taskId,
                token.workflowId(),
                htn.stepId(),
                assignment,
                TaskStatus.OPEN,
                descriptors,
                dueAt,
                dueDateTimerId,
                null, // completedAt
                null, // cancelledAt
                null, // expiredAt
                now, // updatedAt
                null, // decisionName
                null, // decisionPayloadJson
                null, // completedBy
                null, // cancelledBy
                null, // reassignedBy
                null, // cancellationReason
                null, // reassignmentReason
                parentSubjectVersion, // subjectVersionAtCreation
                token.id(), // branchTokenId
                token.forkStepId(), // forkStepId
                token.branchId()); // branchId
    }

    /**
     * Appends a {@link WorkflowEntryType#BRANCH_TASK_CREATED} history entry and returns the
     * (unchanged) branch token so the caller can continue chaining.
     *
     * @param token      the branch token (used for ids and workflow id)
     * @param stepId     the task's step id
     * @param taskId     the new task UUID
     * @param assignment the resolved task assignment
     * @param dueAt      the due-date instant, or {@code null}
     * @param tx         the active transaction
     * @return a {@link Future} of the unchanged branch token
     */
    private Future<BranchToken> appendBranchTaskCreatedHistory(
            BranchToken token,
            String stepId,
            UUID taskId,
            TaskAssignment assignment,
            @Nullable Instant dueAt,
            SqlClient tx) {
        return history.nextSequence(token.workflowId(), tx)
                .compose(seq -> {
                    String histPayload = Json.encode(new TaskCreatedHistoryPayload(stepId, taskId, assignment, dueAt));
                    return history.append(
                            new WorkflowHistoryEntry(
                                    token.workflowId(),
                                    seq,
                                    WorkflowEntryType.BRANCH_TASK_CREATED,
                                    histPayload,
                                    clock.instant()),
                            tx);
                })
                .map(v -> token);
    }

    /**
     * Schedules initial reminder timers for a branch-owned task, if the {@link HumanTaskNode} has
     * a {@link ReminderSpec}. Mirrors the single-path
     * {@link WorkflowEngine#scheduleReminderTimers} but uses branch-correlated intents.
     *
     * @param token         the branch token
     * @param htn           the human-task node
     * @param taskId        the new task UUID
     * @param rw            the resolved runtime workflow
     * @param taskCreatedAt the task-creation instant (anchor for offset/interval calculations)
     * @param tx            the active transaction
     * @return a {@link Future} of the unchanged branch token
     */
    private Future<BranchToken> scheduleBranchReminderTimers(
            BranchToken token,
            HumanTaskNode htn,
            UUID taskId,
            RuntimeWorkflow rw,
            Instant taskCreatedAt,
            SqlClient tx) {
        ReminderSpec spec = htn.reminders();
        if (spec == null) {
            return Future.succeededFuture(token);
        }
        Future<Void> chain = Future.succeededFuture();
        if (spec instanceof ReminderSpec.OneShotOffsets osu) {
            for (Duration offset : osu.offsetsFromTaskCreation()) {
                Instant fireAt = taskCreatedAt.plus(offset);
                chain = chain.compose(v -> scheduleBranchReminderTimer(token, htn, taskId, rw, fireAt, tx));
            }
        } else if (spec instanceof ReminderSpec.RecurringInterval ri) {
            Instant fireAt = taskCreatedAt.plus(ri.interval());
            chain = chain.compose(v -> scheduleBranchReminderTimer(token, htn, taskId, rw, fireAt, tx));
        }
        return chain.map(v -> token);
    }

    /**
     * Schedules a single reminder timer for a branch-owned task using a branch-correlated intent.
     *
     * @param token  the branch token (supplies branch identity for the correlation)
     * @param htn    the human-task node
     * @param taskId the task UUID
     * @param rw     the resolved runtime workflow
     * @param fireAt the absolute fire instant
     * @param tx     the active transaction
     * @return a {@link Future} that completes when the timer intent and history entry are persisted
     */
    private Future<Void> scheduleBranchReminderTimer(
            BranchToken token, HumanTaskNode htn, UUID taskId, RuntimeWorkflow rw, Instant fireAt, SqlClient tx) {
        return history.nextSequence(token.workflowId(), tx).compose(seq -> {
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER,
                    htn.stepId(),
                    new TimerIntentPayload(fireAt, TimerPurpose.TASK_REMINDER, taskId),
                    Map.of(),
                    new WorkflowSideEffectIntent.Correlation(
                            token.workflowId(),
                            seq,
                            rw.plan().definitionId(),
                            htn.stepId(),
                            token.id(),
                            token.forkStepId(),
                            token.branchId()));
            return recorders.route(intent, tx).compose(recorderResult -> {
                if (!(recorderResult instanceof RecorderResult.Timer timerResult)) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "WORKFLOW_TIMER recorder must return RecorderResult.Timer for branch TASK_REMINDER; got "
                                    + recorderResult.getClass().getSimpleName()));
                }
                UUID timerId = timerResult.timerId();
                String histPayload = Json.encode(
                        new TimerScheduledHistoryPayload(htn.stepId(), timerId, fireAt, TimerPurpose.TASK_REMINDER));
                return history.append(
                        new WorkflowHistoryEntry(
                                token.workflowId(),
                                seq,
                                WorkflowEntryType.BRANCH_TIMER_SCHEDULED,
                                histPayload,
                                clock.instant()),
                        tx);
            });
        });
    }
}
