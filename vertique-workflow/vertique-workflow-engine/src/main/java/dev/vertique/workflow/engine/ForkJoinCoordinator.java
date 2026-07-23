// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private collaborator owning the PRD-WF-002 fork dispatch and join evaluation flow.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C4). It absorbs the highest-risk concurrency surface of the engine:
 * <ul>
 *   <li><b>Fork dispatch</b> — {@link #handleForkNode} inserts the join state, one
 *       {@link BranchToken} per declared branch, parks the instance at the join, and drives each
 *       branch's first transition inline.</li>
 *   <li><b>Join evaluation</b> — {@link #evaluateJoin} and the policy applicators
 *       ({@link #applyAllRequired}, {@link #applyFirstSuccess}, {@link #applyFirstFailure}) decide
 *       the join outcome under a {@code SELECT … FOR UPDATE} lock on the join-state row.</li>
 *   <li><b>Race decisions and supersession</b> — {@link #decideRaceAndAdvance} and
 *       {@link #supersedeNonTerminalSiblings} elect the winning branch and mark the losers
 *       {@code SUPERSEDED} (cancelling their owned timers and tasks first).</li>
 *   <li><b>Post-join advancement</b> — {@link #advanceInstanceAfterJoin} applies the join's
 *       branch-result reducer and continues the parent instance through the transition driver.</li>
 *   <li><b>Recovery bridge</b> — {@link #evaluateJoinForRecoveredBranch} is the entrypoint used by
 *       a dialect recovery service when a recovered branch reaches a terminal state.</li>
 * </ul>
 *
 * <p><b>Mutual recursion / cycle-break.</b> Fork dispatch is reached from
 * {@link WorkflowTransitionDriver#driveTransitionsStep}, and {@link #advanceInstanceAfterJoin}
 * drives the parent instance back into {@code driveTransitionsStep}. The transition driver injects
 * this coordinator <em>eagerly</em> while this coordinator injects a {@link Provider}{@code <}{@link
 * WorkflowTransitionDriver}{@code >} <em>lazily</em> to break the Dagger constructor cycle (the same
 * pattern recovery uses for the engine). The driver provider is dereferenced only at runtime, in
 * {@link #advanceInstanceAfterJoin} (the post-join continuation).
 *
 * <p>The coordinator also injects a lazy {@link Provider}{@code <}{@link WorkflowEngine}{@code >}
 * — distinct from the driver back-edge — used by {@link #supersedeNonTerminalSiblings} to call the
 * engine's {@link WorkflowEngine#cancelBranchOwnedWaits} helper, which stays on the engine because
 * the single-path {@code cancel} path also uses it (it is not a driver method).
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class ForkJoinCoordinator {

    // --- Dependencies ---

    private final Provider<WorkflowTransitionDriver> driverPvd;
    private final Provider<WorkflowEngine> enginePvd;
    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final BranchTokenRepository<SqlClient> branchTokens;
    private final JoinStateRepository<SqlClient> joinStates;
    private final BranchTransitionEngine branchEngine;

    /**
     * Branch-scoped compensation orchestrator. {@code null} only when the coordinator is built via
     * a {@link WorkflowEngine} test-only constructor that omits the fork/join collaborators;
     * {@link #applyAllRequired} guards the null before invoking it.
     */
    @Nullable
    private final BranchCompensationOrchestrator branchCompensation;

    /**
     * Durable context propagator used at branch creation in {@link #handleForkNode} to capture
     * ambient holder-bound durable context into {@link BranchToken#metadata()} under
     * {@link dev.vertique.core.context.DispatchBoundary#WORKFLOW}. {@code null} is tolerated for
     * legacy test constructors that do not exercise the branch-capture path.
     */
    @Nullable
    private final dev.vertique.context.DurableContextPropagator propagator;

    private final Clock clock;

    /**
     * Constructs a new fork/join coordinator.
     *
     * @param driverPvd lazy provider of the transition driver, used to break the driver↔fork/join
     *     constructor cycle; dereferenced only at runtime for the post-join continuation
     * @param enginePvd lazy provider of the workflow engine, dereferenced only at runtime for the
     *     {@link WorkflowEngine#cancelBranchOwnedWaits} callback (not a driver method)
     * @param registry the workflow registry used to resolve the pinned runtime workflow when
     *     continuing the parent instance after a join
     * @param instances the instance repository used for optimistic-concurrency state transitions
     * @param history the history repository used to append fork/join lifecycle history entries
     * @param branchTokens the branch-token repository used to insert, load, and update branch tokens
     * @param joinStates the join-state repository used for the join lock and the join decision CAS
     * @param branchEngine the branch transition engine used to drive each branch inline
     * @param branchCompensation the branch compensation orchestrator; may be {@code null} when built
     *     via a test-only engine constructor that omits the fork/join collaborators
     * @param propagator the durable context propagator used to capture branch metadata; may be
     *     {@code null} for legacy test constructors
     * @param clock the clock used to timestamp entries and state updates
     */
    @Inject
    ForkJoinCoordinator(
            Provider<WorkflowTransitionDriver> driverPvd,
            Provider<WorkflowEngine> enginePvd,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            BranchTransitionEngine branchEngine,
            @Nullable BranchCompensationOrchestrator branchCompensation,
            @Nullable dev.vertique.context.DurableContextPropagator propagator,
            Clock clock) {
        this.driverPvd = driverPvd;
        this.enginePvd = enginePvd;
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.branchTokens = branchTokens;
        this.joinStates = joinStates;
        this.branchEngine = branchEngine;
        this.branchCompensation = branchCompensation;
        this.propagator = propagator;
        this.clock = clock;
    }

    // --- Recovery bridge + branch-terminal helpers ---

    /**
     * Drives the (already-cleared, RUNNING) branch token forward and, when it reaches a terminal
     * status, evaluates the owning join. Branch callbacks that mutate parent state (decision
     * applicator, onDueMutator, onTimeoutMutator) MUST pass the post-mutation
     * {@link WorkflowInstance} so the drive sees fresh {@code state_json} and {@code subjectRef}.
     *
     * @param inst    the parent instance reflecting the current state (post-mutation if any)
     * @param cleared the branch token with wait fields cleared and {@code currentStepId} advanced
     * @param rw      the resolved runtime workflow
     * @param tx      the active transaction
     * @return a {@link Future} that completes when transitions and (optionally) join evaluation finish
     */
    Future<Void> driveAndMaybeJoin(WorkflowInstance inst, BranchToken cleared, RuntimeWorkflow rw, SqlClient tx) {
        String parentSubjectVersion =
                inst.subjectRef() == null ? null : inst.subjectRef().version();
        return branchEngine
                .driveBranchTransitions(cleared, inst.stateJson(), parentSubjectVersion, rw, tx, null)
                .compose(advanced -> {
                    if (isBranchTerminal(advanced.status())) {
                        return evaluateJoinForRecoveredBranch(inst, advanced, rw, tx);
                    }
                    return Future.<Void>succeededFuture();
                });
    }

    /**
     * Evaluates the join after a recovered branch reaches a terminal state. Without this hop the
     * join state stays {@code OPEN} forever (and the parent instance stays parked at
     * {@link WaitType#JOIN}) when a branch's terminal transition happens during recovery rather
     * than during a live signal / timer / task callback.
     *
     * <p>Caller already holds the loaded {@link WorkflowInstance} (loaded for the plan-hash drift
     * check); accepting it here avoids a redundant {@code findById}. Resolves the matching
     * {@code ForkNode} / {@code JoinNode} from the runtime plan and delegates to {@link #evaluateJoin}.
     * Caller invokes this only after observing that the post-drive branch token is in a terminal
     * state.
     *
     * @param inst the parent workflow instance already loaded by the recovery service
     * @param triggering the branch token that just terminated during recovery
     * @param rw the resolved runtime workflow (must already be plan-hash-checked by the caller)
     * @param tx the active transaction
     * @return a {@link Future} that completes when join evaluation finishes
     */
    Future<Void> evaluateJoinForRecoveredBranch(
            WorkflowInstance inst,
            dev.vertique.workflow.state.BranchToken triggering,
            RuntimeWorkflow rw,
            SqlClient tx) {
        dev.vertique.workflow.plan.WorkflowNode forkPlanNode = rw.nodeById().get(triggering.forkStepId());
        if (!(forkPlanNode instanceof dev.vertique.workflow.plan.ForkNode fork)) {
            return Future.failedFuture(new WorkflowDefinitionException("Recovered branch references fork '"
                    + triggering.forkStepId() + "' which is not a ForkNode in plan '"
                    + rw.plan().definitionId() + "'"));
        }
        dev.vertique.workflow.plan.WorkflowNode joinPlanNode = rw.nodeById().get(fork.joinStepId());
        if (!(joinPlanNode instanceof dev.vertique.workflow.plan.JoinNode joinNode)) {
            return Future.failedFuture(new WorkflowDefinitionException("ForkNode '" + fork.stepId()
                    + "' references joinStepId '" + fork.joinStepId() + "' which is not a JoinNode in plan '"
                    + rw.plan().definitionId() + "'"));
        }
        return evaluateJoin(inst, fork, joinNode, triggering, rw, tx);
    }

    /**
     * Returns {@code true} if the given branch status is terminal. Static so siblings in the same
     * package (e.g. a dialect recovery service) reuse the same predicate instead of
     * duplicating the enum list.
     *
     * @param s the branch status to test
     * @return {@code true} for {@code COMPLETED}, {@code FAILED}, {@code CANCELLED}, {@code EXPIRED}
     */
    static boolean isBranchTerminal(dev.vertique.workflow.state.BranchStatus s) {
        return s == dev.vertique.workflow.state.BranchStatus.COMPLETED
                || s == dev.vertique.workflow.state.BranchStatus.FAILED
                || s == dev.vertique.workflow.state.BranchStatus.CANCELLED
                || s == dev.vertique.workflow.state.BranchStatus.EXPIRED;
    }

    /**
     * Returns {@code true} if the given branch status is terminal — instance-method alias of
     * {@link #isBranchTerminal} used by the policy applicators for readability.
     *
     * @param s the branch status to test
     * @return {@code true} for terminal branch statuses
     */
    private static boolean isTerminal(dev.vertique.workflow.state.BranchStatus s) {
        return isBranchTerminal(s);
    }

    // ===================================================================================
    // PRD-WF-002: Fork dispatch + join evaluation
    // ===================================================================================

    /**
     * Handles a {@link dev.vertique.workflow.plan.ForkNode}: inserts the join state, inserts one
     * {@link BranchToken} per declared branch, parks the workflow instance at the join, and drives
     * each branch's first transition inline (PRD-WF-002 §A.4.4 / §D3).
     *
     * @param inst the parent instance currently positioned at the fork node
     * @param fork the fork node to dispatch
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when all branches are dispatched and inline-driven
     */
    Future<Void> handleForkNode(
            WorkflowInstance inst, dev.vertique.workflow.plan.ForkNode fork, RuntimeWorkflow rw, SqlClient tx) {
        java.time.Instant now = clock.instant();
        long instVersion = inst.version();
        // Resolve the matching JoinNode so we can capture the policy in JoinState.
        dev.vertique.workflow.plan.WorkflowNode joinPlanNode = rw.nodeById().get(fork.joinStepId());
        if (!(joinPlanNode instanceof dev.vertique.workflow.plan.JoinNode joinNode)) {
            return Future.failedFuture(new WorkflowDefinitionException("ForkNode '" + fork.stepId()
                    + "' references joinStepId '" + fork.joinStepId() + "' which is not a JoinNode"));
        }
        dev.vertique.workflow.state.JoinState openJoin = new dev.vertique.workflow.state.JoinState(
                inst.id(),
                fork.stepId(),
                fork.joinStepId(),
                toJoinPolicyType(joinNode),
                dev.vertique.workflow.state.JoinStateStatus.OPEN,
                null,
                null,
                0L,
                now,
                now);
        // Capture ambient durable context once for the entire fork. All branches in this fork
        // share the same persistence-time durable metadata — they were all forked under the same
        // bound holder context. Boundary is WORKFLOW because branch-token metadata is a
        // workflow-native carrier (FR-CTX-178), distinct from the DELAYED_JOB boundary used by
        // workflow timers.
        dev.vertique.core.context.DurableMetadata branchMetadata = propagator != null
                ? propagator.mergeCaptured(
                        dev.vertique.core.context.DurableMetadata.empty(),
                        dev.vertique.core.context.DispatchBoundary.WORKFLOW)
                : dev.vertique.core.context.DurableMetadata.empty();
        // Build all branch tokens up-front so we can iterate after insert.
        java.util.List<dev.vertique.workflow.state.BranchToken> tokens =
                new java.util.ArrayList<>(fork.branches().size());
        for (var bs : fork.branches()) {
            tokens.add(new dev.vertique.workflow.state.BranchToken(
                    java.util.UUID.randomUUID(),
                    inst.id(),
                    fork.stepId(),
                    bs.branchId(),
                    bs.startStepId(),
                    dev.vertique.workflow.state.BranchStatus.RUNNING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    Math.max(1, fork.retryPolicy().maxAttempts()),
                    null,
                    null,
                    null,
                    null,
                    0L,
                    now,
                    now,
                    branchMetadata));
        }
        return joinStates
                .insert(openJoin, tx)
                .compose(v -> insertAllBranchTokens(tokens, tx))
                .compose(v -> appendForkDispatched(inst, fork, tx))
                .compose(v -> {
                    WorkflowInstance parked = inst.withVersion(instVersion + 1)
                            .withCurrentStepId(fork.joinStepId())
                            .withWait(WaitType.JOIN, fork.stepId())
                            .withError(null, null)
                            .withUpdatedAt(now);
                    return instances.updateOptimistic(parked, instVersion, tx).compose(rowCount -> {
                        if (rowCount == 0) {
                            return Future.<Void>failedFuture(
                                    new WorkflowConflictException("Optimistic concurrency conflict for instance '"
                                            + inst.id().value() + "' at fork '" + fork.stepId() + "'"));
                        }
                        return Future.<Void>succeededFuture();
                    });
                })
                .compose(v -> driveAllBranchesAndEvaluate(inst, fork, joinNode, tokens, rw, tx));
    }

    /** Maps a plan-side {@link dev.vertique.workflow.plan.JoinPolicy} to its persisted form. */
    private static dev.vertique.workflow.state.JoinPolicyType toJoinPolicyType(dev.vertique.workflow.plan.JoinNode jn) {
        if (jn.policy() instanceof dev.vertique.workflow.plan.AllRequiredJoinPolicy) {
            return dev.vertique.workflow.state.JoinPolicyType.ALL_REQUIRED;
        }
        if (jn.policy() instanceof dev.vertique.workflow.plan.FirstSuccessJoinPolicy) {
            return dev.vertique.workflow.state.JoinPolicyType.FIRST_SUCCESS;
        }
        if (jn.policy() instanceof dev.vertique.workflow.plan.FirstFailureJoinPolicy) {
            return dev.vertique.workflow.state.JoinPolicyType.FIRST_FAILURE;
        }
        throw new WorkflowDefinitionException(
                "Unknown JoinPolicy variant " + jn.policy().getClass().getSimpleName());
    }

    /**
     * Inserts every branch token sequentially within the active transaction.
     *
     * @param tokens the branch tokens to insert
     * @param tx the active transaction
     * @return a {@link Future} that completes when all tokens are inserted
     */
    private Future<Void> insertAllBranchTokens(
            java.util.List<dev.vertique.workflow.state.BranchToken> tokens, SqlClient tx) {
        Future<Void> chain = Future.succeededFuture();
        for (var t : tokens) {
            chain = chain.compose(v -> branchTokens.insert(t, tx));
        }
        return chain;
    }

    /**
     * Appends a {@link WorkflowEntryType#FORK_DISPATCHED} history entry recording the fork/join
     * step ids and the dispatched branch ids.
     *
     * @param inst the parent instance
     * @param fork the dispatched fork node
     * @param tx the active transaction
     * @return a {@link Future} that completes when the entry is appended
     */
    private Future<Void> appendForkDispatched(
            WorkflowInstance inst, dev.vertique.workflow.plan.ForkNode fork, SqlClient tx) {
        io.vertx.core.json.JsonObject payload = new io.vertx.core.json.JsonObject()
                .put("forkStepId", fork.stepId())
                .put("joinStepId", fork.joinStepId())
                .put(
                        "branchIds",
                        new io.vertx.core.json.JsonArray(fork.branches().stream()
                                .map(b -> (Object) b.branchId())
                                .toList()));
        return history.nextSequence(inst.id(), tx)
                .compose(seq -> history.append(
                        new WorkflowHistoryEntry(
                                inst.id(), seq, WorkflowEntryType.FORK_DISPATCHED, payload.encode(), clock.instant()),
                        tx));
    }

    /**
     * Drives every branch token inline (within the same transaction). After each branch settles,
     * if it reached a terminal state, evaluate the join in case the policy is already satisfied.
     *
     * @param inst the parent instance
     * @param fork the dispatched fork node
     * @param joinNode the matching join node
     * @param tokens the branch tokens dispatched for this fork
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when every branch has been driven and evaluated
     */
    private Future<Void> driveAllBranchesAndEvaluate(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            java.util.List<dev.vertique.workflow.state.BranchToken> tokens,
            RuntimeWorkflow rw,
            SqlClient tx) {
        Future<Void> chain = Future.succeededFuture();
        for (var token : tokens) {
            UUID id = token.id();
            // Reload each branch from the DB before driving — under FIRST_SUCCESS / FIRST_FAILURE,
            // an earlier inline branch's evaluateJoin may have marked this one SUPERSEDED, so the
            // in-memory copy from the original insert list would be stale.
            chain = chain.compose(v -> branchTokens.findById(id, tx).compose(opt -> {
                if (opt.isEmpty()) {
                    return Future.<Void>succeededFuture();
                }
                BranchToken fresh = opt.get();
                if (fresh.status() != BranchStatus.RUNNING) {
                    return Future.<Void>succeededFuture();
                }
                return branchEngine
                        .driveBranchTransitions(
                                fresh,
                                inst.stateJson(),
                                inst.subjectRef() == null
                                        ? null
                                        : inst.subjectRef().version(),
                                rw,
                                tx,
                                null)
                        .compose(advanced -> {
                            if (isTerminal(advanced.status())) {
                                return evaluateJoin(inst, fork, joinNode, advanced, rw, tx)
                                        .mapEmpty();
                            }
                            return Future.<Void>succeededFuture();
                        });
            }));
        }
        return chain;
    }

    /**
     * Evaluates a join under a {@code SELECT … FOR UPDATE} lock on the join-state row, dispatching
     * to the policy applicator that matches the join's policy.
     *
     * <p>Algorithm (PRD-WF-002 §10):
     * <ol>
     *   <li>SELECT FOR UPDATE on the join state row.</li>
     *   <li>If status != OPEN, append BRANCH_LATE_RESULT_IGNORED and return — the join was already
     *       decided.</li>
     *   <li>Load all branch tokens.</li>
     *   <li>Dispatch to the policy applicator (ALL_REQUIRED / FIRST_SUCCESS / FIRST_FAILURE).</li>
     * </ol>
     *
     * @param inst the parent instance
     * @param fork the fork node owning the join
     * @param joinNode the join node being evaluated
     * @param triggeringBranch the branch token whose terminal transition triggered this evaluation
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the join is evaluated (and possibly decided)
     */
    Future<Void> evaluateJoin(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            dev.vertique.workflow.state.BranchToken triggeringBranch,
            RuntimeWorkflow rw,
            SqlClient tx) {
        return joinStates
                .findForUpdate(inst.id(), fork.stepId(), joinNode.stepId(), tx)
                .compose(opt -> {
                    if (opt.isEmpty()) {
                        return Future.<Void>failedFuture(
                                new WorkflowDefinitionException("Join state missing for fork '" + fork.stepId() + "'"));
                    }
                    var js = opt.get();
                    if (js.status() != dev.vertique.workflow.state.JoinStateStatus.OPEN) {
                        // Late terminal — record as ignored / superseded.
                        return appendLateLoserAndSupersede(inst, triggeringBranch, tx);
                    }
                    return branchTokens
                            .findByWorkflowAndFork(inst.id(), fork.stepId(), tx)
                            .compose(branches -> {
                                if (joinNode.policy() instanceof dev.vertique.workflow.plan.AllRequiredJoinPolicy) {
                                    return applyAllRequired(inst, fork, joinNode, js, branches, rw, tx);
                                }
                                if (joinNode.policy() instanceof dev.vertique.workflow.plan.FirstSuccessJoinPolicy) {
                                    return applyFirstSuccess(
                                            inst, fork, joinNode, js, triggeringBranch, branches, rw, tx);
                                }
                                if (joinNode.policy() instanceof dev.vertique.workflow.plan.FirstFailureJoinPolicy) {
                                    return applyFirstFailure(
                                            inst, fork, joinNode, js, triggeringBranch, branches, rw, tx);
                                }
                                return Future.<Void>failedFuture(
                                        new WorkflowDefinitionException("Unknown JoinPolicy variant "
                                                + joinNode.policy().getClass().getSimpleName()));
                            });
                });
    }

    /**
     * FIRST_SUCCESS: the first branch to reach COMPLETED wins. The triggering branch is the
     * winner candidate. Sibling branches that are still non-terminal become SUPERSEDED.
     *
     * @param inst the parent instance
     * @param fork the fork node owning the join
     * @param joinNode the join node being evaluated
     * @param js the locked join state
     * @param triggeringBranch the branch token whose transition triggered this evaluation
     * @param branches all branch tokens of the fork
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the join is decided or left open
     */
    private Future<Void> applyFirstSuccess(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            dev.vertique.workflow.state.JoinState js,
            dev.vertique.workflow.state.BranchToken triggeringBranch,
            java.util.List<dev.vertique.workflow.state.BranchToken> branches,
            RuntimeWorkflow rw,
            SqlClient tx) {
        if (triggeringBranch.status() != dev.vertique.workflow.state.BranchStatus.COMPLETED) {
            // Branch terminated non-successfully (FAILED/CANCELLED/EXPIRED). FIRST_SUCCESS does
            // not advance on those — wait for another sibling to succeed. If ALL branches end
            // non-successfully, the join takes the failure route (deterministic recovery
            // tie-break: handled by applyAllRequired-like fall-through below).
            boolean anyStillRunning = branches.stream().anyMatch(b -> !isTerminal(b.status()));
            if (anyStillRunning) {
                return Future.succeededFuture();
            }
            // All terminal but none COMPLETED — take failure route.
            return decideRaceAndAdvance(
                    inst, fork, joinNode, js, triggeringBranch.branchId(), branches, /* success = */ false, rw, tx);
        }
        return decideRaceAndAdvance(
                inst, fork, joinNode, js, triggeringBranch.branchId(), branches, /* success = */ true, rw, tx);
    }

    /**
     * FIRST_FAILURE: the first branch to reach FAILED/CANCELLED/EXPIRED wins. The triggering
     * branch is the winner candidate. Sibling branches that are still non-terminal become
     * SUPERSEDED.
     *
     * @param inst the parent instance
     * @param fork the fork node owning the join
     * @param joinNode the join node being evaluated
     * @param js the locked join state
     * @param triggeringBranch the branch token whose transition triggered this evaluation
     * @param branches all branch tokens of the fork
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the join is decided or left open
     */
    private Future<Void> applyFirstFailure(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            dev.vertique.workflow.state.JoinState js,
            dev.vertique.workflow.state.BranchToken triggeringBranch,
            java.util.List<dev.vertique.workflow.state.BranchToken> branches,
            RuntimeWorkflow rw,
            SqlClient tx) {
        var s = triggeringBranch.status();
        boolean isFailure = s == dev.vertique.workflow.state.BranchStatus.FAILED
                || s == dev.vertique.workflow.state.BranchStatus.CANCELLED
                || s == dev.vertique.workflow.state.BranchStatus.EXPIRED;
        if (!isFailure) {
            // Branch completed successfully — under FIRST_FAILURE we wait for a sibling to fail.
            // If ALL branches succeed, FIRST_FAILURE never fires; treat as success (advance via
            // nextStepId).
            boolean anyStillRunning = branches.stream().anyMatch(b -> !isTerminal(b.status()));
            if (anyStillRunning) {
                return Future.succeededFuture();
            }
            return decideRaceAndAdvance(
                    inst, fork, joinNode, js, triggeringBranch.branchId(), branches, /* success = */ true, rw, tx);
        }
        return decideRaceAndAdvance(
                inst, fork, joinNode, js, triggeringBranch.branchId(), branches, /* success = */ false, rw, tx);
    }

    /**
     * Decides a race join (FIRST_SUCCESS / FIRST_FAILURE) via the join-state CAS, supersedes the
     * losing siblings, records the fan-in decision, and advances the parent instance.
     *
     * @param inst the parent instance
     * @param fork the fork node owning the join
     * @param joinNode the join node being decided
     * @param js the locked join state
     * @param winningBranchId the elected winning branch id
     * @param branches all branch tokens of the fork
     * @param success {@code true} to decide COMPLETED, {@code false} to decide FAILED
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the decision is applied (or the CAS lost)
     */
    private Future<Void> decideRaceAndAdvance(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            dev.vertique.workflow.state.JoinState js,
            String winningBranchId,
            java.util.List<dev.vertique.workflow.state.BranchToken> branches,
            boolean success,
            RuntimeWorkflow rw,
            SqlClient tx) {
        var newStatus = success
                ? dev.vertique.workflow.state.JoinStateStatus.COMPLETED
                : dev.vertique.workflow.state.JoinStateStatus.FAILED;
        java.time.Instant now = clock.instant();
        return joinStates
                .decide(
                        inst.id(),
                        fork.stepId(),
                        joinNode.stepId(),
                        newStatus,
                        winningBranchId,
                        now,
                        js.version() + 1,
                        js.version(),
                        tx)
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        // Concurrent decider won the race — treat this caller as a late loser.
                        return Future.<Void>succeededFuture();
                    }
                    // Codex review round-3 fix H-new-1: the reducer must see post-supersede
                    // statuses for losing siblings. Patch the in-memory branches list so
                    // applyJoinReducer sees losers as SUPERSEDED, not the stale RUNNING/WAITING
                    // status from before supersedeNonTerminalSiblings ran.
                    java.time.Instant supersededAt = clock.instant();
                    java.util.List<dev.vertique.workflow.state.BranchToken> patched = branches.stream()
                            .map(b -> {
                                if (b.branchId().equals(winningBranchId)) return b;
                                if (isTerminal(b.status())) return b;
                                return supersededWithClearedWait(b, supersededAt);
                            })
                            .toList();
                    return supersedeNonTerminalSiblings(branches, winningBranchId, tx)
                            .compose(v -> appendFanInDecided(inst, fork, joinNode, success, tx))
                            .compose(v -> advanceInstanceAfterJoin(inst, joinNode, success, patched, rw, tx));
                });
    }

    /**
     * Marks every non-terminal sibling branch (other than the winner) as {@code SUPERSEDED}
     * (PRD-WF-002 FR-WF-PAR-049/050/053).
     *
     * <p>Lock order: timers → tasks → branch token. For each losing branch,
     * {@link WorkflowEngine#cancelBranchOwnedWaits} closes the branch's owned timers and tasks
     * (acquiring those row locks first), then the branch-token row is updated to {@code SUPERSEDED}.
     * The {@code cancelBranchOwnedWaits} helper is reached through the lazy engine provider because
     * the engine's single-path {@code cancel} path also uses it, so it stays on the engine.
     *
     * @param branches all branch tokens of the fork
     * @param winningBranchId the elected winning branch id (never superseded)
     * @param tx the active transaction
     * @return a {@link Future} that completes when all losing siblings are superseded
     */
    private Future<Void> supersedeNonTerminalSiblings(
            java.util.List<dev.vertique.workflow.state.BranchToken> branches, String winningBranchId, SqlClient tx) {
        Future<Void> chain = Future.succeededFuture();
        java.time.Instant now = clock.instant();
        for (var b : branches) {
            if (b.branchId().equals(winningBranchId)) continue;
            if (isTerminal(b.status())) continue;
            // Cancel owned timers and tasks first (lock order: timers → tasks → branch token).
            // PRD-WF-002 FR-WF-PAR-052: null out wait_type/wait_key on the SUPERSEDED row so it
            // no longer matches the branch-token repository's find-by-wait query (which has no
            // status predicate) — late signals/timer-fires on SUPERSEDED branches are silently no-ops.
            final BranchToken loser = b;
            BranchToken superseded = supersededWithClearedWait(b, now);
            chain = chain.compose(v -> enginePvd.get().cancelBranchOwnedWaits(loser, tx))
                    .compose(v -> branchTokens
                            .updateOptimistic(superseded, loser.version(), tx)
                            .mapEmpty());
        }
        return chain;
    }

    /**
     * Builds a {@code SUPERSEDED} branch-token snapshot with the wait fields cleared.
     *
     * @param b the losing branch token
     * @param now the supersession timestamp
     * @return a new {@link BranchToken} with status {@code SUPERSEDED} and wait fields cleared
     */
    private static BranchToken supersededWithClearedWait(BranchToken b, java.time.Instant now) {
        return b.withClearedWait(BranchStatus.SUPERSEDED, b.currentStepId(), now);
    }

    /**
     * Evaluates an ALL_REQUIRED join: decides COMPLETED when every branch completed, FAILED when
     * any branch reached a terminal failure (triggering deterministic compensation of completed
     * compensable siblings before the failure route), and otherwise leaves the join open.
     *
     * @param inst the parent instance
     * @param fork the fork node owning the join
     * @param joinNode the join node being evaluated
     * @param js the locked join state
     * @param branches all branch tokens of the fork
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the join is decided or left open
     */
    private Future<Void> applyAllRequired(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            dev.vertique.workflow.state.JoinState js,
            java.util.List<dev.vertique.workflow.state.BranchToken> branches,
            RuntimeWorkflow rw,
            SqlClient tx) {
        boolean allCompleted =
                branches.stream().allMatch(b -> b.status() == dev.vertique.workflow.state.BranchStatus.COMPLETED);
        boolean anyFailed = branches.stream().anyMatch(b -> {
            var s = b.status();
            return s == dev.vertique.workflow.state.BranchStatus.FAILED
                    || s == dev.vertique.workflow.state.BranchStatus.CANCELLED
                    || s == dev.vertique.workflow.state.BranchStatus.EXPIRED;
        });
        if (!allCompleted && !anyFailed) {
            // Branches still in flight; nothing to decide yet.
            return Future.succeededFuture();
        }
        boolean success = allCompleted;
        var newStatus = success
                ? dev.vertique.workflow.state.JoinStateStatus.COMPLETED
                : dev.vertique.workflow.state.JoinStateStatus.FAILED;
        java.time.Instant now = clock.instant();
        return joinStates
                .decide(
                        inst.id(),
                        fork.stepId(),
                        joinNode.stepId(),
                        newStatus,
                        null,
                        now,
                        js.version() + 1,
                        js.version(),
                        tx)
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        // Concurrent decider won the race; treat this caller as a late loser.
                        return Future.<Void>succeededFuture();
                    }
                    Future<Void> after = appendFanInDecided(inst, fork, joinNode, success, tx);
                    if (!success && branchCompensation != null) {
                        // PRD-WF-002 AC #2 (Codex review fix H1): a failed required branch must
                        // trigger deterministic compensation for completed compensable sibling
                        // branches BEFORE the instance advances through the failure route.
                        after = after.compose(
                                v -> branchCompensation.compensateFailedFork(inst, fork, branches, rw, tx));
                    }
                    return after.compose(v -> advanceInstanceAfterJoin(inst, joinNode, success, branches, rw, tx));
                });
    }

    /**
     * Appends the {@code FAN_IN_EVALUATED} history entry followed by either {@code FAN_IN_COMPLETED}
     * or {@code FAN_IN_FAILED}, recording the join decision.
     *
     * @param inst the parent instance
     * @param fork the fork node owning the join
     * @param joinNode the decided join node
     * @param success {@code true} for a completed decision, {@code false} for a failed decision
     * @param tx the active transaction
     * @return a {@link Future} that completes when both entries are appended
     */
    private Future<Void> appendFanInDecided(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.ForkNode fork,
            dev.vertique.workflow.plan.JoinNode joinNode,
            boolean success,
            SqlClient tx) {
        io.vertx.core.json.JsonObject payload = new io.vertx.core.json.JsonObject()
                .put("forkStepId", fork.stepId())
                .put("joinStepId", joinNode.stepId());
        return history.nextSequence(inst.id(), tx)
                .compose(seq -> history.append(
                        new WorkflowHistoryEntry(
                                inst.id(), seq, WorkflowEntryType.FAN_IN_EVALUATED, payload.encode(), clock.instant()),
                        tx))
                .compose(v -> history.nextSequence(inst.id(), tx))
                .compose(seq -> history.append(
                        new WorkflowHistoryEntry(
                                inst.id(),
                                seq,
                                success ? WorkflowEntryType.FAN_IN_COMPLETED : WorkflowEntryType.FAN_IN_FAILED,
                                payload.encode(),
                                clock.instant()),
                        tx))
                .mapEmpty();
    }

    /**
     * Applies the join's branch-result reducer to produce the post-fan-in state, then advances the
     * parent instance through the join's {@code nextStepId} (success) or {@code failureStepId}
     * (failure) and continues the state machine — fencing against concurrent terminal transitions.
     *
     * @param inst the parent instance as observed before the join decision
     * @param joinNode the decided join node
     * @param success {@code true} to take the success route, {@code false} for the failure route
     * @param branches the (possibly patched) branch tokens passed to the reducer
     * @param rw the resolved runtime workflow
     * @param tx the active transaction
     * @return a {@link Future} that completes when the instance has advanced (or the fence skipped it)
     */
    private Future<Void> advanceInstanceAfterJoin(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.JoinNode joinNode,
            boolean success,
            java.util.List<dev.vertique.workflow.state.BranchToken> branches,
            RuntimeWorkflow rw,
            SqlClient tx) {
        // PRD-WF-002 FR-WF-PAR-044 (Codex review round-2 fix): apply the join's reducer to
        // produce the post-fan-in state JSON. Reducer registration is mandatory (DSL guarantees
        // it via JoinScope.allRequired/firstSuccess/firstFailure); the engine had been silently
        // dropping the result and carrying state_json unchanged.
        String nextStep = success ? joinNode.nextStepId() : joinNode.failureStepId();
        return instances.findByIdForUpdate(inst.id(), tx).compose(opt -> {
            if (opt.isEmpty()) {
                return Future.<Void>failedFuture(
                        new WorkflowDefinitionException("instance disappeared during join evaluation: "
                                + inst.id().value()));
            }
            WorkflowInstance current = opt.get();
            // Codex review round-2 fix: fence against concurrent terminal transitions
            // (e.g., cancel() uses non-locking read + optimistic write to avoid a deadlock with
            // timer locking). The instance MUST still be parked at THIS join when we advance —
            // otherwise a parallel cancel/fail/timeout committed and we must NOT overwrite it.
            //
            // handleForkNode parks with waitType=JOIN and waitKey=forkStepId but leaves
            // status=RUNNING (the workflow is alive, just waiting on its branches). The fence
            // therefore checks the wait identity + terminal-status absence, NOT status==WAITING.
            if (current.status() == WorkflowStatus.COMPLETED
                    || current.status() == WorkflowStatus.FAILED
                    || current.status() == WorkflowStatus.CANCELLED
                    || current.status() == WorkflowStatus.EXPIRED
                    || current.status() == WorkflowStatus.COMPENSATED
                    || current.status() == WorkflowStatus.COMPENSATING
                    || current.waitType() != WaitType.JOIN
                    || !joinNode.stepId().equals(current.currentStepId())) {
                return Future.<Void>succeededFuture();
            }
            long prevVersion = current.version();
            String reducedStateJson;
            try {
                reducedStateJson = applyJoinReducer(current, joinNode, branches, rw);
            } catch (Exception reducerErr) {
                return Future.<Void>failedFuture(
                        new WorkflowDefinitionException("Branch-result reducer threw for join '" + joinNode.stepId()
                                + "': " + reducerErr.getMessage()));
            }
            if (nextStep == null) {
                // Failure path declared without a route — fail the instance terminally.
                WorkflowInstance failed = current.withVersion(prevVersion + 1)
                        .withStatus(WorkflowStatus.FAILED)
                        .withWait(null, null)
                        .withState(reducedStateJson)
                        .withError(
                                "join_failed_no_failure_route",
                                "Join '" + joinNode.stepId()
                                        + "' decided FAILED but the plan declares no failureStepId")
                        .withUpdatedAt(clock.instant());
                return instances.updateOptimistic(failed, prevVersion, tx).compose(rowCount -> {
                    if (rowCount == 0) {
                        return Future.<Void>failedFuture(new WorkflowConflictException(
                                "Optimistic concurrency conflict failing instance after join '" + joinNode.stepId()
                                        + "'"));
                    }
                    return Future.<Void>succeededFuture();
                });
            }
            WorkflowInstance advanced = current.withVersion(prevVersion + 1)
                    .withCurrentStepId(nextStep)
                    .withWait(null, null)
                    .withState(reducedStateJson)
                    .withUpdatedAt(clock.instant());
            return instances.updateOptimistic(advanced, prevVersion, tx).compose(rowCount -> {
                if (rowCount == 0) {
                    return Future.<Void>failedFuture(new WorkflowConflictException(
                            "Optimistic concurrency conflict advancing instance after join '" + joinNode.stepId()
                                    + "'"));
                }
                return driverPvd
                        .get()
                        .driveTransitionsStep(
                                advanced,
                                registry.resolvePinned(advanced.definitionId(), advanced.definitionVersion()),
                                tx);
            });
        });
    }

    /**
     * Records the triggering branch as a late loser when the join was already decided. If the
     * triggering branch is non-terminal, also mark it SUPERSEDED so its identity reflects the
     * race outcome (PRD-WF-002 FR-WF-PAR-052/055).
     *
     * @param inst the parent instance
     * @param triggeringBranch the late-arriving branch token
     * @param tx the active transaction
     * @return a {@link Future} that completes when the late-loser entry is appended
     */
    private Future<Void> appendLateLoserAndSupersede(
            WorkflowInstance inst, dev.vertique.workflow.state.BranchToken triggeringBranch, SqlClient tx) {
        if (isTerminal(triggeringBranch.status())) {
            return appendLateLoser(inst, triggeringBranch, tx);
        }
        java.time.Instant now = clock.instant();
        BranchToken superseded = supersededWithClearedWait(triggeringBranch, now);
        return branchTokens
                .updateOptimistic(superseded, triggeringBranch.version(), tx)
                .compose(rc -> appendLateLoser(inst, triggeringBranch, tx));
    }

    /**
     * Appends a {@link WorkflowEntryType#BRANCH_LATE_RESULT_IGNORED} history entry recording the
     * late-arriving branch token.
     *
     * @param inst the parent instance
     * @param triggeringBranch the late-arriving branch token
     * @param tx the active transaction
     * @return a {@link Future} that completes when the entry is appended
     */
    private Future<Void> appendLateLoser(
            WorkflowInstance inst, dev.vertique.workflow.state.BranchToken triggeringBranch, SqlClient tx) {
        io.vertx.core.json.JsonObject payload = new io.vertx.core.json.JsonObject()
                .put("branchTokenId", triggeringBranch.id().toString())
                .put("forkStepId", triggeringBranch.forkStepId())
                .put("branchId", triggeringBranch.branchId());
        return history.nextSequence(inst.id(), tx)
                .compose(seq -> history.append(
                        new WorkflowHistoryEntry(
                                inst.id(),
                                seq,
                                WorkflowEntryType.BRANCH_LATE_RESULT_IGNORED,
                                payload.encode(),
                                clock.instant()),
                        tx))
                .mapEmpty();
    }

    /**
     * Appends a {@link WorkflowEntryType#BRANCH_LATE_CALLBACK_IGNORED} history entry. Called from
     * the engine's branch task/timer callback paths when a callback arrives for a branch that has
     * already moved on or been superseded.
     *
     * @param workflowId the owning workflow instance id
     * @param entityId   the id of the entity (task or timer) whose callback was ignored
     * @param cause      a short string identifying which callback was ignored
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the entry is appended
     */
    Future<Void> appendBranchLateCallbackIgnored(
            dev.vertique.workflow.ops.WorkflowInstanceId workflowId, Object entityId, String cause, SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            io.vertx.core.json.JsonObject payload = new io.vertx.core.json.JsonObject()
                    .put("entityId", entityId.toString())
                    .put("cause", cause);
            return history.append(
                    new WorkflowHistoryEntry(
                            workflowId,
                            seq,
                            WorkflowEntryType.BRANCH_LATE_CALLBACK_IGNORED,
                            payload.encode(),
                            clock.instant()),
                    tx);
        });
    }

    /**
     * Invokes the join's branch-result reducer (PRD-WF-002 FR-WF-PAR-044). Decodes the parent
     * instance's {@code state_json}, builds a {@code Map<branchId, BranchResult>} from the branch
     * tokens, calls the registered reducer, and returns the JSON-encoded new state. Throws if
     * the reducer is missing (defensive — the DSL guarantees registration via JoinScope) or
     * returns null.
     *
     * @param inst the parent instance whose state is reduced
     * @param joinNode the join node whose reducer callback is invoked
     * @param branches the branch tokens supplying the per-branch results
     * @param rw the resolved runtime workflow holding the reducer callback and state type
     * @return the JSON-encoded reduced state
     */
    @SuppressWarnings("unchecked")
    private String applyJoinReducer(
            WorkflowInstance inst,
            dev.vertique.workflow.plan.JoinNode joinNode,
            java.util.List<dev.vertique.workflow.state.BranchToken> branches,
            RuntimeWorkflow rw) {
        var reducerCallback = joinNode.branchResultReducerCallbackId();
        var reducer =
                (java.util.function.BiFunction<Object, Map<String, dev.vertique.workflow.plan.BranchResult>, Object>)
                        (java.util.function.BiFunction<?, ?, ?>) rw.callbacks().branchResultReducer(reducerCallback);
        Object stateObj = io.vertx.core.json.Json.decodeValue(
                io.vertx.core.buffer.Buffer.buffer(inst.stateJson()), rw.stateType());
        Map<String, dev.vertique.workflow.plan.BranchResult> resultsByBranchId = new java.util.LinkedHashMap<>();
        for (var b : branches) {
            io.vertx.core.json.JsonObject result =
                    b.resultJson() != null ? new io.vertx.core.json.JsonObject(b.resultJson()) : null;
            resultsByBranchId.put(
                    b.branchId(),
                    new dev.vertique.workflow.plan.BranchResult(
                            b.branchId(), b.status(), result, b.errorType(), b.errorMessage()));
        }
        Object reduced = reducer.apply(stateObj, resultsByBranchId);
        if (reduced == null) {
            throw new WorkflowDefinitionException("Branch-result reducer for join '" + joinNode.stepId()
                    + "' returned null; reducer must produce a non-null state");
        }
        return io.vertx.core.json.Json.encode(reduced);
    }
}
