// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMaps;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.DedupClaim;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.exception.WorkflowTaskNotFoundException;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec;
import dev.vertique.workflow.plan.ReminderSpec;
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
import dev.vertique.workflow.state.payload.TaskCancelledHistoryPayload;
import dev.vertique.workflow.state.payload.TaskCompletedHistoryPayload;
import dev.vertique.workflow.state.payload.TaskCreatedHistoryPayload;
import dev.vertique.workflow.state.payload.TaskExpiredHistoryPayload;
import dev.vertique.workflow.state.payload.TaskReassignedHistoryPayload;
import dev.vertique.workflow.state.payload.TaskReminderFiredHistoryPayload;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.tasks.TaskTransition;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Package-private collaborator owning the human-task lifecycle callback paths.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C7). It absorbs the {@code TransactionalTaskCallbacks} bodies and their
 * supporting helpers:
 * <ul>
 *   <li>{@link #taskCompleted} / {@link #doCompleteTask} / {@link #doCompleteTaskBound} /
 *       {@link #doBranchCompleteTask} — task completion (single-path and branch-owned).</li>
 *   <li>{@link #taskDueFired} / {@link #doTaskDueFiredBound} / {@link #advanceForDueExpired} /
 *       {@link #doBranchTaskDueFired} — due-date expiry.</li>
 *   <li>{@link #taskReassigned} — task reassignment.</li>
 *   <li>{@link #taskReminderFired} / {@link #doTaskReminderFiredBound} /
 *       {@link #doBranchTaskReminderFired} — reminder firing.</li>
 *   <li>The task helpers {@link #buildTaskRecord} (static), {@link #resolveAssignment},
 *       {@link #cancelTaskOwnedTimers}, {@link #appendTaskCreatedHistory},
 *       {@link #appendTaskCancelledHistory}, and {@link #appendBranchTaskCompletedHistory}.</li>
 * </ul>
 *
 * <p>The facade's {@code TransactionalTaskCallbacks} entrypoints
 * ({@code taskCompleted}/{@code taskDueFired}/{@code taskReassigned}/{@code taskReminderFired})
 * delegate their bodies to this service. Payload validation/coercion and plan-hash checks reuse the
 * shared {@link WorkflowPayloads} utilities.
 *
 * <p><b>Driver↔task cycle-break.</b> This service drives the state machine forward after a task
 * mutation via {@link WorkflowTransitionDriver#driveTransitions} (single-path) and
 * {@link ForkJoinCoordinator#driveAndMaybeJoin} (branch-path), so it injects both
 * <em>eagerly</em>. The {@link WorkflowTransitionDriver} in turn needs this service's task-creation
 * helpers ({@link #resolveAssignment}, {@link #appendTaskCreatedHistory}, and the static
 * {@link #buildTaskRecord}) when it handles a {@link HumanTaskNode}; it reaches them through a lazy
 * {@code Provider<TaskLifecycleService>}. The lazy {@code Provider} on the driver side is what
 * breaks the Dagger constructor cycle.
 *
 * <p><b>Timer→task→timer cycle-break.</b> The task-completion due-date cancel path calls
 * {@link TimerLifecycleService#appendTimerCancelledHistory}. Because
 * {@link TimerLifecycleService} injects this service <em>eagerly</em> (for the single-path
 * {@code TASK} due-date dispatch), this service reaches the canonical helper through a lazy
 * {@code Provider<TimerLifecycleService>} to avoid a {@code timer → task → timer} Dagger cycle.
 *
 * <p>Instances are {@code @Singleton} and constructed by Dagger via {@code @Inject}.
 */
@Singleton
final class TaskLifecycleService {

    // --- Dependencies ---

    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final WorkflowDedupRepository<SqlClient> dedup;
    private final TaskStore<SqlClient> taskStore;
    private final TimerStore<SqlClient> timerStore;
    private final BranchTokenRepository<SqlClient> branchTokens;
    private final FingerprintCanonicalizer fingerprintCanonicalizer;
    private final WorkflowEventEmitter eventEmitter;
    private final ReminderScheduler reminderScheduler;
    private final ForkJoinCoordinator forkJoin;
    private final WorkflowTransitionDriver driver;
    private final Clock clock;

    /**
     * Lazy back-edge to {@link TimerLifecycleService} for the canonical
     * {@link TimerLifecycleService#appendTimerCancelledHistory} helper used when a task-completion
     * cancels the due-date timer. Lazy to avoid a {@code timer → task → timer} Dagger cycle:
     * {@link TimerLifecycleService} injects this service eagerly for the single-path {@code TASK}
     * due-date dispatch; dereferenced only at runtime, after construction is complete.
     */
    private final Provider<TimerLifecycleService> timerPvd;

    /**
     * Binder-row seam for the instance-owned (non-branch) task drives: {@link #doCompleteTask},
     * {@link #taskReassigned} (which has no branch fork at all — every reassignment is
     * instance-owned), {@link #taskDueFired}, and {@link #taskReminderFired}. The branch-owned
     * paths ({@link #doBranchCompleteTask} and the due/reminder branch forks) MUST NOT use this
     * binder — the bind-once routing rule reserves branch-owned drives for the
     * {@link BranchTransitionEngine} carrier seam. Never {@code null} — the engine assembly seam
     * substitutes {@link WorkflowContextBinder#noop()} when the propagator is {@code null}, so
     * every instance-owned task drive calls {@link WorkflowContextBinder#withBound} directly.
     */
    private final WorkflowContextBinder contextBinder;

    /**
     * Constructs a new task-lifecycle service.
     *
     * @param registry the workflow registry used to resolve the pinned {@link RuntimeWorkflow}
     * @param instances the instance repository used for optimistic-concurrency state transitions
     * @param history the history repository used for sequence numbers and history entries
     * @param dedup the dedup repository used for the idempotency claims on completion/reassignment
     * @param taskStore the task store used to lock, complete, expire, and reassign task rows
     * @param timerStore the timer store used to cancel task-owned due-date and reminder timers
     * @param branchTokens the branch-token repository used by the branch-owned task paths; may be
     *     {@code null} in legacy test constructors that omit the fork/join collaborators
     * @param fingerprintCanonicalizer canonicalizer used to compute idempotency fingerprints
     * @param eventEmitter the event emitter used to emit task-lifecycle workflow events
     * @param reminderScheduler the reminder scheduler used to (re)schedule and cancel task reminders
     * @param forkJoin the fork/join coordinator whose {@code driveAndMaybeJoin} continues a branch
     *     after a branch-owned task mutation; may be {@code null} in legacy test constructors
     * @param driver the transition driver invoked as the single-path post-mutation continuation
     * @param clock the clock used to timestamp history entries and state updates
     * @param timerPvd lazy provider of the timer-lifecycle service, used for
     *     {@link TimerLifecycleService#appendTimerCancelledHistory} when a task-completion cancels
     *     the due-date timer; lazy to break the {@code timer → task → timer} Dagger cycle
     * @param contextBinder the binder-row seam for the instance-owned task drives; never
     *     {@code null} (the engine assembly seam substitutes {@link WorkflowContextBinder#noop()}
     *     when durable-context wiring is not exercised)
     */
    @Inject
    TaskLifecycleService(
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            TaskStore<SqlClient> taskStore,
            TimerStore<SqlClient> timerStore,
            BranchTokenRepository<SqlClient> branchTokens,
            FingerprintCanonicalizer fingerprintCanonicalizer,
            WorkflowEventEmitter eventEmitter,
            ReminderScheduler reminderScheduler,
            ForkJoinCoordinator forkJoin,
            WorkflowTransitionDriver driver,
            Clock clock,
            Provider<TimerLifecycleService> timerPvd,
            WorkflowContextBinder contextBinder) {
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.dedup = dedup;
        this.taskStore = taskStore;
        this.timerStore = timerStore;
        this.branchTokens = branchTokens;
        this.fingerprintCanonicalizer = fingerprintCanonicalizer;
        this.eventEmitter = eventEmitter;
        this.reminderScheduler = reminderScheduler;
        this.forkJoin = forkJoin;
        this.driver = driver;
        this.clock = clock;
        this.timerPvd = timerPvd;
        this.contextBinder = contextBinder;
    }

    // --- Task-creation helpers (reached by the driver via Provider<TaskLifecycleService>) ---

    /**
     * Resolves an {@link AssignmentSpec} to a concrete {@link TaskAssignment}.
     *
     * <p>Literal variants are mapped directly; {@code *FromState} variants invoke the registered
     * callback with the decoded workflow state.
     *
     * <p>Reached by {@link WorkflowTransitionDriver} through its lazy
     * {@code Provider<TaskLifecycleService>} when it handles a {@link HumanTaskNode}.
     *
     * @param spec the assignment spec from the plan node
     * @param inst the current workflow instance (state decoded when needed for resolver variants)
     * @param rw   the resolved runtime workflow (provides the callback registry)
     * @return the resolved literal assignment
     */
    TaskAssignment resolveAssignment(AssignmentSpec spec, WorkflowInstance inst, RuntimeWorkflow rw) {
        return switch (spec) {
            case AssignmentSpec.User u -> new TaskAssignment.User(u.userId());
            case AssignmentSpec.Role r -> new TaskAssignment.Role(r.roleId());
            case AssignmentSpec.Queue q -> new TaskAssignment.Queue(q.queueName());
            case AssignmentSpec.UserFromState ufs -> {
                Object state = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                @SuppressWarnings("unchecked")
                var resolver = (java.util.function.Function<Object, TaskAssignment>) (java.util.function.Function<?, ?>)
                        rw.callbacks().taskAssignmentResolver(ufs.resolverCallbackId());
                yield resolver.apply(state);
            }
            case AssignmentSpec.RoleFromState rfs -> {
                Object state = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                @SuppressWarnings("unchecked")
                var resolver = (java.util.function.Function<Object, TaskAssignment>) (java.util.function.Function<?, ?>)
                        rw.callbacks().taskAssignmentResolver(rfs.resolverCallbackId());
                yield resolver.apply(state);
            }
            case AssignmentSpec.QueueFromState qfs -> {
                Object state = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                @SuppressWarnings("unchecked")
                var resolver = (java.util.function.Function<Object, TaskAssignment>) (java.util.function.Function<?, ?>)
                        rw.callbacks().taskAssignmentResolver(qfs.resolverCallbackId());
                yield resolver.apply(state);
            }
        };
    }

    /**
     * Builds a {@link TaskRecord} for insertion with {@link dev.vertique.workflow.tasks.TaskStatus#OPEN} status.
     *
     * <p>Invoked statically by {@link WorkflowTransitionDriver} when it handles a {@link HumanTaskNode}.
     *
     * @param taskId                    the new task UUID
     * @param inst                      the workflow instance creating this task
     * @param htn                       the human task node definition
     * @param assignment                the resolved literal assignment
     * @param descriptors               the decision descriptor snapshot
     * @param dueAt                     the due-date instant, or {@code null}
     * @param dueDateTimerId            the due-date timer id, or {@code null}
     * @param subjectVersionAtCreation  snapshot of the subject version at creation time, or
     *                                  {@code null} when the instance has no versioned subject ref
     * @param now                       the creation timestamp
     * @return a new {@link TaskRecord} ready for {@link TaskStore#insertOpen}
     */
    static TaskRecord buildTaskRecord(
            UUID taskId,
            WorkflowInstance inst,
            HumanTaskNode htn,
            TaskAssignment assignment,
            java.util.List<dev.vertique.workflow.tasks.TaskDecisionDescriptor> descriptors,
            Instant dueAt,
            UUID dueDateTimerId,
            @jakarta.annotation.Nullable String subjectVersionAtCreation,
            Instant now) {
        return new TaskRecord(
                taskId,
                inst.id(),
                htn.stepId(),
                assignment,
                dev.vertique.workflow.tasks.TaskStatus.OPEN,
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
                subjectVersionAtCreation,
                null, // branchTokenId
                null, // forkStepId
                null); // branchId
    }

    /**
     * Appends a {@code TASK_CREATED} history entry and emits a {@code TASK_CREATED} workflow event.
     *
     * <p>Reached by {@link WorkflowTransitionDriver} through its lazy
     * {@code Provider<TaskLifecycleService>} when it handles a {@link HumanTaskNode}.
     *
     * @param inst       the workflow instance
     * @param stepId     the task's step id
     * @param taskId     the new task UUID
     * @param assignment the resolved task assignment
     * @param dueAt      the due-date instant, or {@code null}
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the entry is inserted and the event emitted
     */
    Future<Void> appendTaskCreatedHistory(
            WorkflowInstance inst, String stepId, UUID taskId, TaskAssignment assignment, Instant dueAt, SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            String histPayload = Json.encode(new TaskCreatedHistoryPayload(stepId, taskId, assignment, dueAt));
            return history.append(
                            new WorkflowHistoryEntry(
                                    inst.id(), seq, WorkflowEntryType.TASK_CREATED, histPayload, clock.instant()),
                            tx)
                    .compose(v -> eventEmitter.emitEvent(
                            inst,
                            WorkflowEventType.TASK_CREATED,
                            seq,
                            taskId,
                            stepId,
                            WorkflowEventEmitter.attrs(
                                    "assigneeType",
                                    assignment.getClass().getSimpleName(),
                                    "assigneeKey",
                                    WorkflowActorMaps.toMap(assignment).get("value"),
                                    "dueAt",
                                    dueAt != null ? dueAt.toString() : null),
                            tx));
        });
    }

    /**
     * Appends a {@code TASK_CANCELLED} history entry.
     *
     * @param inst             the workflow instance that owns the task
     * @param taskId           the UUID of the cancelled task
     * @param cancellationReason human-readable reason for the cancellation
     * @param cancelledBy      the actor who triggered the cancellation
     * @param cancelledAt      the UTC instant of cancellation
     * @param tx               the active transaction
     * @return a {@link Future} that completes with the sequence number assigned to the appended
     *     entry; callers use this sequence as the {@code sequence} field on any
     *     {@link WorkflowEventType#TASK_CANCELLED} event so the event correlates with this exact
     *     history row (rather than a later row that happens to consume the next sequence).
     */
    Future<Long> appendTaskCancelledHistory(
            WorkflowInstance inst,
            UUID taskId,
            String cancellationReason,
            WorkflowActor cancelledBy,
            Instant cancelledAt,
            SqlClient tx) {
        return history.nextSequence(inst.id(), tx).compose(seq -> {
            // Locate the stepId from the plan node at the current step.
            String stepId = inst.currentStepId();
            String histPayload = Json.encode(new TaskCancelledHistoryPayload(
                    stepId,
                    taskId,
                    cancelledAt,
                    cancellationReason,
                    cancelledBy,
                    WorkflowPayloads.commandCorrelationId()));
            return history.append(
                            new WorkflowHistoryEntry(
                                    inst.id(), seq, WorkflowEntryType.TASK_CANCELLED, histPayload, cancelledAt),
                            tx)
                    .map(v -> seq);
        });
    }

    // --- Task completion ---

    /**
     * Completes a human task.
     *
     * <p>Implementation steps:
     * <ol>
     *   <li>Non-locking task lookup to discover workflowId and decisionsSnapshot.</li>
     *   <li>Fingerprint computation and idempotency dedup claim.</li>
     *   <li>Decision validation and payload coercion.</li>
     *   <li>Non-locking instance read; validates WAITING/TASK/wait-key slot.</li>
     *   <li>Cancel the due-date timer (if present) — lock order: timers before tasks.</li>
     *   <li>Lock the task row for completion.</li>
     *   <li>Write completion columns.</li>
     *   <li>Apply decision applicator to state.</li>
     *   <li>Optimistic UPDATE on workflow_instances.</li>
     *   <li>Append TASK_COMPLETED history.</li>
     *   <li>Drive transitions from the decision's next step.</li>
     * </ol>
     *
     * @param cmd the completion command; must not be null
     * @param tx  the active transaction context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskCompleted(TaskCompletionCommand cmd, SqlClient tx) {
        // Step 1: Non-locking task lookup.
        return taskStore.findById(cmd.taskId(), tx).compose(optTask -> {
            if (optTask.isEmpty()) {
                return Future.failedFuture(new WorkflowTaskNotFoundException(cmd.taskId()));
            }
            TaskRecord task = optTask.get();

            // Step 2: Fingerprint and dedup.
            String fingerprint;
            try {
                fingerprint = fingerprintCanonicalizer.fingerprintCompletion(cmd);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }

            return dedup.claimOrResolveTaskCompletion(
                            cmd.taskId(), cmd.idempotencyKey(), task.workflowId(), fingerprint, tx)
                    .compose((DedupClaim claim) -> {
                        if (!claim.inserted()) {
                            if (fingerprint.equals(claim.existingFingerprint())) {
                                // Idempotent retry — same key, same fingerprint. The original
                                // command already advanced the workflow; surface as LOST_TO_RACE
                                // so the public TaskService.complete(...) caller observes
                                // success. STALE_NOOP is reserved for stale due-date / no-wait
                                // cases where the workflow has legitimately moved past the task.
                                return Future.succeededFuture(TaskMutationResult.LOST_TO_RACE);
                            }
                            // Conflict — same key, different fingerprint.
                            return Future.failedFuture(new WorkflowIdempotencyConflictException(
                                    "task-complete", cmd.idempotencyKey(), claim.existingFingerprint(), fingerprint));
                        }
                        return doCompleteTask(cmd, task, fingerprint, tx);
                    });
        });
    }

    /**
     * Performs the actual task-completion writes after the dedup claim has been won.
     *
     * <p>Branch-forks on {@link TaskRecord#branchTokenId()}: when the task is branch-owned,
     * delegates to {@link #doBranchCompleteTask} which gates on the branch token's wait state
     * instead of the parent instance's.
     *
     * @param cmd         the completion command
     * @param task        the task record fetched in step 1
     * @param fingerprint the computed fingerprint (unused after claim, kept for context)
     * @param tx          the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doCompleteTask(
            TaskCompletionCommand cmd, TaskRecord task, String fingerprint, SqlClient tx) {
        // Branch fork: route branch-owned tasks through the branch-specific path. The branch-owned
        // path (doBranchCompleteTask) MUST NOT be binder-bound — bind-once routing rule, C2.
        if (task.branchTokenId() != null) {
            return doBranchCompleteTask(cmd, task, tx);
        }

        // Load the plan to get the applicator callback and payload type.
        // The decisions snapshot on the task record carries name/payloadTypeName/nextStepId but
        // not the applicatorCallbackId — that lives in the plan. We resolve the plan below.
        WorkflowInstanceId workflowId = task.workflowId();

        return instances.findById(workflowId, tx).compose(optInst -> {
            if (optInst.isEmpty()
                    || optInst.get().status() != WorkflowStatus.WAITING
                    || optInst.get().waitType() != WaitType.TASK
                    || !cmd.taskId().toString().equals(optInst.get().waitKey())) {
                // Roll back the dedup row by failing the future (transaction will roll back).
                return Future.failedFuture(new WorkflowTaskNotWaitingException(cmd.taskId()));
            }
            WorkflowInstance inst = optInst.get();

            // Binder-row bind (Contract Appendix C2): instance-owned (no branch) task-completion
            // drive.
            return contextBinder.withBound(inst, null, () -> doCompleteTaskBound(cmd, task, inst, tx));
        });
    }

    /**
     * Executes the task-completion drive body after the instance-owned binder-row bind is in
     * effect. Extracted so {@link #doCompleteTask} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param cmd  the completion command
     * @param task the task record fetched in step 1
     * @param inst the loaded (WAITING/TASK-slot-matched) instance snapshot
     * @param tx   the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doCompleteTaskBound(
            TaskCompletionCommand cmd, TaskRecord task, WorkflowInstance inst, SqlClient tx) {
        RuntimeWorkflow rw;
        try {
            rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
        } catch (WorkflowVersionPinUnavailableException e) {
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

        WorkflowNode node = rw.nodeById().get(inst.currentStepId());
        if (!(node instanceof HumanTaskNode htn)) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Expected HumanTaskNode at step '" + inst.currentStepId() + "' but found "
                            + (node == null ? "null" : node.getClass().getSimpleName())));
        }

        // Step 3a: Version-stability check (cycle 5, §B.3).
        // Runs after plan-node load and before any row mutations so it does not hold locks.
        if (htn.requireVersionStability()) {
            if (cmd.reviewedSubjectVersion() == null) {
                return Future.failedFuture(
                        new WorkflowStaleSubjectVersionException("Task '" + cmd.taskId() + "' (step '" + htn.stepId()
                                + "') requires a reviewedSubjectVersion but the caller did not supply one"));
            }
            if (!Objects.equals(cmd.reviewedSubjectVersion(), task.subjectVersionAtCreation())) {
                return Future.failedFuture(
                        new WorkflowStaleSubjectVersionException("Task '" + cmd.taskId() + "' (step '" + htn.stepId()
                                + "'): reviewed version '" + cmd.reviewedSubjectVersion()
                                + "' did not match snapshot '"
                                + task.subjectVersionAtCreation() + "'"));
            }
        }

        // Find the matching decision in the plan.
        HumanTaskNode.TaskDecision planDecision = htn.decisions().stream()
                .filter(d -> d.name().equals(cmd.decisionName()))
                .findFirst()
                .orElse(null);
        if (planDecision == null) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Unknown decision '" + cmd.decisionName() + "' for task step '" + htn.stepId() + "'"));
        }

        // Step 3b: Payload-nullability validation + coercion.
        Object coercedPayload;
        try {
            coercedPayload =
                    WorkflowPayloads.validateAndCoerceTaskPayload(cmd.payload(), planDecision.payloadTypeName());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        // Step 5: Cancel the due-date timer if present (timers before tasks, lock order).
        UUID dueDateTimerId = inst.waitAuxId();
        Future<Void> cancelTimerFuture = Future.succeededFuture();
        if (dueDateTimerId != null) {
            cancelTimerFuture = timerStore
                    .markCancelled(dueDateTimerId, clock.instant(), tx)
                    .compose(transition -> switch (transition) {
                        case APPLIED, LOST_TO_CANCELLED ->
                            appendTimerCancelledHistory(inst, dueDateTimerId, TimerCancelledCause.TASK_COMPLETED, tx);
                        case LOST_TO_FIRED ->
                            Future.failedFuture(new WorkflowConflictException("Due-date timer '" + dueDateTimerId
                                    + "' already fired; cannot complete task '" + cmd.taskId() + "'"));
                        case LOST_TO_FAILED ->
                            Future.failedFuture(new WorkflowConflictException("Due-date timer '" + dueDateTimerId
                                    + "' is in FAILED state; cannot complete task '" + cmd.taskId() + "'"));
                    });
        }

        Object finalCoercedPayload = coercedPayload;
        HumanTaskNode.TaskDecision finalDecision = planDecision;

        return cancelTimerFuture
                // Step 5b: Cancel pending reminder timers (lock order: timers before tasks).
                .compose(v -> reminderScheduler.cancelPendingReminders(cmd.taskId(), inst, tx))
                // Step 6: Lock the task row.
                .compose(v -> taskStore.lockForCompletion(cmd.taskId(), tx))
                .compose(optLocked -> {
                    if (optLocked.isEmpty()
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                        return Future.<TaskMutationResult>failedFuture(
                                new WorkflowConflictException("Task '" + cmd.taskId() + "' is no longer OPEN"));
                    }

                    Instant now = clock.instant();
                    String payloadJson = finalCoercedPayload != null ? Json.encode(finalCoercedPayload) : null;

                    // Step 7: Mark completed.
                    return taskStore
                            .markCompleted(cmd.taskId(), cmd.decisionName(), payloadJson, now, cmd.completedBy(), tx)
                            .compose(transition -> {
                                if (transition != TaskTransition.APPLIED) {
                                    return Future.failedFuture(new WorkflowConflictException("Task '" + cmd.taskId()
                                            + "' completion lost to concurrent terminal transition"));
                                }

                                // Step 8: Apply decision applicator.
                                Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                                Object newState;
                                try {
                                    @SuppressWarnings("unchecked")
                                    var applicator = (java.util.function.BiFunction<Object, Object, Object>)
                                            (java.util.function.BiFunction<?, ?, ?>) rw.callbacks()
                                                    .decisionApplicator(finalDecision.applicatorCallbackId());
                                    newState = applicator.apply(stateObj, finalCoercedPayload);
                                } catch (Exception e) {
                                    return Future.failedFuture(
                                            new WorkflowDefinitionException("decisionApplicator threw for decision '"
                                                    + cmd.decisionName() + "': " + e.getMessage()));
                                }

                                // Step 9: Optimistic UPDATE on workflow_instances.
                                long prevVersion = inst.version();
                                WorkflowInstance advanced = inst.withVersion(prevVersion + 1)
                                        .withStatus(WorkflowStatus.RUNNING)
                                        .withCurrentStepId(finalDecision.nextStepId())
                                        .withWait(null, null)
                                        .withState(Json.encode(newState))
                                        .withError(null, null)
                                        .withUpdatedAt(now);

                                return instances
                                        .updateOptimistic(advanced, prevVersion, tx)
                                        .compose(rowCount -> {
                                            if (rowCount == 0) {
                                                return Future.<Void>failedFuture(new WorkflowConflictException(
                                                        "Optimistic concurrency conflict completing task '"
                                                                + cmd.taskId() + "'"));
                                            }
                                            // Step 10: Append TASK_COMPLETED history + emit event.
                                            return history.nextSequence(inst.id(), tx)
                                                    .compose(seq -> {
                                                        String hp = Json.encode(new TaskCompletedHistoryPayload(
                                                                htn.stepId(),
                                                                cmd.taskId(),
                                                                cmd.decisionName(),
                                                                cmd.completedBy(),
                                                                now,
                                                                cmd.reviewedSubjectVersion(),
                                                                WorkflowPayloads.commandCorrelationId()));
                                                        return history.append(
                                                                        new WorkflowHistoryEntry(
                                                                                inst.id(),
                                                                                seq,
                                                                                WorkflowEntryType.TASK_COMPLETED,
                                                                                hp,
                                                                                now),
                                                                        tx)
                                                                .compose(v2 -> eventEmitter.emitEvent(
                                                                        inst,
                                                                        WorkflowEventType.TASK_COMPLETED,
                                                                        seq,
                                                                        cmd.taskId(),
                                                                        htn.stepId(),
                                                                        WorkflowEventEmitter.attrs(
                                                                                "decisionName",
                                                                                cmd.decisionName(),
                                                                                "completedBy",
                                                                                WorkflowActorMaps.toMap(
                                                                                        cmd.completedBy())),
                                                                        tx));
                                                    });
                                        })
                                        // Step 11: Drive transitions from the next step.
                                        .compose(v -> driver.driveTransitions(advanced, rw, tx))
                                        .map(v -> TaskMutationResult.APPLIED);
                            });
                });
    }

    // --- Branch-aware task-callback helpers ---

    /**
     * Branch path for {@link #taskCompleted}: handles completion of a branch-owned human task.
     *
     * <p>Lock order: {@code workflow_timers} (task-owned due/reminder timers) →
     * {@code workflow_tasks} → {@code workflow_branch_tokens} → {@code workflow_instances}
     * (non-locking read + optimistic write).
     *
     * <p>Mirrors the single-path {@link #doCompleteTask} with the following branch-specific
     * adaptations:
     * <ul>
     *   <li>Version-stability is checked against {@link TaskRecord#subjectVersionAtCreation()},
     *       which was snapshotted from the parent instance's subject ref at task creation time.</li>
     *   <li>The decision applicator is applied to the parent instance's {@code state_json},
     *       producing a new state that is written back to the parent instance via optimistic
     *       update (only the {@code state_json} and {@code version} fields change; the parent
     *       stays at {@code WAITING/JOIN}).</li>
     *   <li>The branch wait is cleared inline so the branch token is already {@code RUNNING} when
     *       {@link BranchTransitionEngine#driveBranchTransitions} is called.</li>
     * </ul>
     *
     * <p>If the branch token is no longer {@code WAITING} with {@code wait_type=TASK} and
     * {@code wait_key=taskId}, the completion is treated as a late/stale callback and a
     * {@link WorkflowEntryType#BRANCH_LATE_CALLBACK_IGNORED} entry is appended.
     *
     * @param cmd  the completion command
     * @param task the non-locking task snapshot obtained in step 1 of {@link #taskCompleted}
     * @param tx   the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doBranchCompleteTask(TaskCompletionCommand cmd, TaskRecord task, SqlClient tx) {
        UUID taskId = cmd.taskId();
        UUID branchTokenId = task.branchTokenId();
        WorkflowInstanceId workflowId = task.workflowId();

        // Step 1: Cancel task-owned timers FIRST (timers before task lock).
        // Due-date timer (if present) + any scheduled reminder timers.
        return cancelTaskOwnedTimers(task, tx)
                // Step 2: Lock the task row for completion.
                .compose(v -> taskStore.lockForCompletion(taskId, tx))
                .compose(optLocked -> {
                    if (optLocked.isEmpty()
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                        return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                    }
                    // Step 3: Lock the branch token.
                    return branchTokens.findByIdForUpdate(branchTokenId, tx).compose(optBranch -> {
                        if (optBranch.isEmpty()) {
                            return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                        }
                        BranchToken branch = optBranch.get();
                        // Step 4: Verify branch is still WAITING on this exact task.
                        if (branch.status() != BranchStatus.WAITING
                                || branch.waitType() != WaitType.TASK
                                || !taskId.toString().equals(branch.waitKey())) {
                            // Late callback — branch has already moved on or was superseded.
                            return forkJoin.appendBranchLateCallbackIgnored(workflowId, taskId, "task-completed", tx)
                                    .map(v2 -> TaskMutationResult.STALE_NOOP);
                        }

                        // Step 5: Load parent instance (non-locking) for plan + state context.
                        return instances.findById(workflowId, tx).compose(optInst -> {
                            if (optInst.isEmpty()) {
                                return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                            }
                            WorkflowInstance inst = optInst.get();

                            RuntimeWorkflow rw;
                            try {
                                rw = resolveAndCheckPlanHash(inst);
                            } catch (Exception e) {
                                return Future.failedFuture(e);
                            }

                            // Step 6: Resolve the HumanTaskNode from the plan (needed for the
                            // applicator callback id and version-stability flag).
                            WorkflowNode node = rw.nodeById().get(task.stepId());
                            if (!(node instanceof HumanTaskNode htn)) {
                                return Future.failedFuture(new WorkflowDefinitionException(
                                        "Expected HumanTaskNode at step '" + task.stepId()
                                                + "' for branch task completion, found "
                                                + (node == null
                                                        ? "null"
                                                        : node.getClass().getSimpleName())));
                            }

                            // Step 7: Version-stability check — mirrors single-path §B.3.
                            if (htn.requireVersionStability()) {
                                if (cmd.reviewedSubjectVersion() == null
                                        || !Objects.equals(
                                                cmd.reviewedSubjectVersion(), task.subjectVersionAtCreation())) {
                                    return Future.failedFuture(new WorkflowStaleSubjectVersionException(
                                            "Branch task '" + taskId + "' (step '" + htn.stepId()
                                                    + "'): reviewed version '"
                                                    + cmd.reviewedSubjectVersion()
                                                    + "' did not match snapshot '"
                                                    + task.subjectVersionAtCreation() + "'"));
                                }
                            }

                            // Step 8: Find the matching decision in the plan (for applicatorCallbackId).
                            HumanTaskNode.TaskDecision planDecision = htn.decisions().stream()
                                    .filter(d -> d.name().equals(cmd.decisionName()))
                                    .findFirst()
                                    .orElse(null);
                            if (planDecision == null) {
                                return Future.failedFuture(new WorkflowDefinitionException("Unknown decision '"
                                        + cmd.decisionName() + "' for branch task step '" + htn.stepId() + "'"));
                            }

                            // Step 9: Payload-nullability validation + coercion.
                            Object coercedPayload;
                            try {
                                coercedPayload = WorkflowPayloads.validateAndCoerceTaskPayload(
                                        cmd.payload(), planDecision.payloadTypeName());
                            } catch (Exception e) {
                                return Future.failedFuture(e);
                            }
                            Object finalCoercedPayload = coercedPayload;
                            HumanTaskNode.TaskDecision finalDecision = planDecision;

                            // Step 10: Mark the task complete.
                            Instant now = clock.instant();
                            String payloadJson = finalCoercedPayload != null ? Json.encode(finalCoercedPayload) : null;
                            return taskStore
                                    .markCompleted(taskId, cmd.decisionName(), payloadJson, now, cmd.completedBy(), tx)
                                    .compose(transition -> {
                                        if (transition != TaskTransition.APPLIED) {
                                            return Future.<TaskMutationResult>succeededFuture(
                                                    TaskMutationResult.STALE_NOOP);
                                        }

                                        // Step 11: Apply decision applicator to parent state.
                                        Object stateObj =
                                                Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                                        Object newState;
                                        try {
                                            @SuppressWarnings("unchecked")
                                            var applicator = (java.util.function.BiFunction<Object, Object, Object>)
                                                    (java.util.function.BiFunction<?, ?, ?>) rw.callbacks()
                                                            .decisionApplicator(finalDecision.applicatorCallbackId());
                                            newState = applicator.apply(stateObj, finalCoercedPayload);
                                        } catch (Exception e) {
                                            return Future.failedFuture(new WorkflowDefinitionException(
                                                    "decisionApplicator threw for branch decision '"
                                                            + cmd.decisionName() + "': " + e.getMessage()));
                                        }
                                        String newStateJson = Json.encode(newState);

                                        // Step 12: Clear the branch wait and advance to
                                        // planDecision.nextStepId(). The branch token goes directly to
                                        // RUNNING so driveBranchTransitions picks up from the next step.
                                        BranchToken cleared = WorkflowPayloads.clearBranchWait(
                                                branch, finalDecision.nextStepId(), now);
                                        return branchTokens
                                                .updateOptimistic(cleared, branch.version(), tx)
                                                .compose(rc -> {
                                                    if (rc == 0) {
                                                        return Future.<TaskMutationResult>failedFuture(
                                                                new WorkflowConflictException(
                                                                        "Optimistic concurrency conflict on branch"
                                                                                + " token during task completion for task '"
                                                                                + taskId + "'"));
                                                    }

                                                    // Step 13: Update parent instance state (stays at
                                                    // WAITING/JOIN; only state_json and version change).
                                                    long instVersion = inst.version();
                                                    WorkflowInstance updatedInst = inst.withVersion(instVersion + 1)
                                                            .withState(newStateJson)
                                                            .withUpdatedAt(now);
                                                    return instances
                                                            .updateOptimistic(updatedInst, instVersion, tx)
                                                            .compose(instRc -> {
                                                                if (instRc == 0) {
                                                                    return Future.<TaskMutationResult>failedFuture(
                                                                            new WorkflowConflictException(
                                                                                    "Optimistic concurrency conflict on"
                                                                                            + " parent instance during"
                                                                                            + " branch task completion for task '"
                                                                                            + taskId + "'"));
                                                                }

                                                                // Step 14: Append BRANCH_TASK_COMPLETED history.
                                                                return appendBranchTaskCompletedHistory(
                                                                                workflowId,
                                                                                task.stepId(),
                                                                                taskId,
                                                                                cmd,
                                                                                now,
                                                                                inst,
                                                                                tx)
                                                                        // Step 15-16: Drive transitions from the
                                                                        // cleared token (already RUNNING) and, if it
                                                                        // reaches a terminal status, evaluate the
                                                                        // owning join. updatedInst carries newStateJson
                                                                        // and the post-applicator subject ref.
                                                                        .compose(v2 -> forkJoin.driveAndMaybeJoin(
                                                                                updatedInst, cleared, rw, tx))
                                                                        .map(v2 -> TaskMutationResult.APPLIED);
                                                            });
                                                });
                                    });
                        });
                    });
                });
    }

    /**
     * Cancels all timers owned by a specific task (due-date timer + scheduled reminders),
     * enforcing the {@code workflow_timers}-first lock order.
     *
     * <p>This is the branch-path equivalent of {@code cancelPendingReminders} combined with the
     * due-date timer cancel in the single-path {@link #taskCompleted} flow. The due-date timer id
     * is read from the non-locking task snapshot ({@link TaskRecord#dueDateTimerId()}) to avoid
     * requiring a pre-locked task row.
     *
     * @param task the non-locking task snapshot (provides {@code taskId} and {@code dueDateTimerId})
     * @param tx   the active transaction
     * @return a {@link Future} that completes when all task-owned timers are in a terminal state
     */
    private Future<Void> cancelTaskOwnedTimers(TaskRecord task, SqlClient tx) {
        Instant now = clock.instant();
        // 1a. Cancel the due-date timer by id if present.
        Future<Void> cancelDue = Future.succeededFuture();
        if (task.dueDateTimerId() != null) {
            cancelDue = timerStore.markCancelled(task.dueDateTimerId(), now, tx).mapEmpty();
        }
        // 1b. Cancel scheduled reminder timers.
        return cancelDue
                .compose(v -> timerStore.findScheduledRemindersForTask(task.taskId(), tx))
                .compose(reminderIds -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (UUID timerId : reminderIds) {
                        chain = chain.compose(v -> timerStore.markCancelled(timerId, now, tx))
                                .mapEmpty();
                    }
                    return chain;
                });
    }

    /**
     * Branch path for {@link #taskDueFired}: fires a branch-owned task's due-date timer.
     *
     * <p>The due-date timer is identified by {@link TaskRecord#dueDateTimerId()}, which the caller
     * matched to route here. The timer row is already locked by {@code WorkflowTimerFireExecutor}.
     *
     * <p>Lock order: {@code workflow_timers} (already locked by the executor) →
     * {@code workflow_tasks} → {@code workflow_branch_tokens} → {@code workflow_instances}
     * (loaded non-locking for context).
     *
     * @param workflowId the owning workflow instance id
     * @param task       the task record (non-locking snapshot from the caller)
     * @param tx         the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doBranchTaskDueFired(
            WorkflowInstanceId workflowId, TaskRecord task, SqlClient tx) {
        UUID taskId = task.taskId();
        UUID branchTokenId = task.branchTokenId();

        // Cancel reminder timers FIRST (timers before task — the due-date timer is already locked
        // by the executor, so reminder timers are the next in the ordering).
        return timerStore
                .findScheduledRemindersForTask(taskId, tx)
                .compose(reminderIds -> {
                    Future<Void> chain = Future.succeededFuture();
                    Instant cancelNow = clock.instant();
                    for (UUID rid : reminderIds) {
                        chain = chain.compose(v -> timerStore.markCancelled(rid, cancelNow, tx))
                                .mapEmpty();
                    }
                    return chain;
                })
                .compose(v -> taskStore.lockForCompletion(taskId, tx))
                .compose(optLocked -> {
                    if (optLocked.isEmpty()
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                        return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                    }
                    // Lock the branch token.
                    return branchTokens.findByIdForUpdate(branchTokenId, tx).compose(optBranch -> {
                        if (optBranch.isEmpty()) {
                            return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                        }
                        BranchToken branch = optBranch.get();
                        // Verify branch is WAITING/TASK on this task.
                        if (branch.status() != BranchStatus.WAITING
                                || branch.waitType() != WaitType.TASK
                                || !taskId.toString().equals(branch.waitKey())) {
                            return forkJoin.appendBranchLateCallbackIgnored(workflowId, taskId, "task-due-fired", tx)
                                    .map(v2 -> TaskMutationResult.STALE_NOOP);
                        }

                        Instant now = clock.instant();
                        return taskStore.markExpired(taskId, now, tx).compose(transition -> {
                            if (transition != TaskTransition.APPLIED) {
                                return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                            }
                            // Load parent instance for state + plan resolution.
                            return instances.findById(workflowId, tx).compose(optInst -> {
                                if (optInst.isEmpty()) {
                                    return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
                                }
                                WorkflowInstance inst = optInst.get();
                                RuntimeWorkflow rw;
                                try {
                                    rw = resolveAndCheckPlanHash(inst);
                                } catch (Exception e) {
                                    return Future.failedFuture(e);
                                }
                                WorkflowNode node = rw.nodeById().get(task.stepId());
                                if (!(node instanceof HumanTaskNode htn) || htn.onDueMutatorCallbackId() == null) {
                                    return Future.<TaskMutationResult>failedFuture(new WorkflowDefinitionException(
                                            "Expected HumanTaskNode with due-date at step '"
                                                    + task.stepId()
                                                    + "' during branch taskDueFired"));
                                }

                                // Apply onDueMutator to parent state — mirrors single-path
                                // advanceForDueExpired. The parent stays at WAITING/JOIN but its
                                // state_json is updated so that the branch can see the mutated state.
                                Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                                Object newStateForDue;
                                try {
                                    @SuppressWarnings("unchecked")
                                    var mutator = (java.util.function.Function<Object, Object>)
                                            (java.util.function.Function<?, ?>)
                                                    rw.callbacks().stateMutator(htn.onDueMutatorCallbackId());
                                    newStateForDue = mutator.apply(stateObj);
                                } catch (Exception e) {
                                    return Future.<TaskMutationResult>failedFuture(
                                            new WorkflowDefinitionException("onDueMutator threw for branch step '"
                                                    + htn.stepId() + "': " + e.getMessage()));
                                }
                                String newStateJsonForDue = Json.encode(newStateForDue);
                                long instVersionForDue = inst.version();
                                WorkflowInstance updatedInstForDue = inst.withVersion(instVersionForDue + 1)
                                        .withState(newStateJsonForDue)
                                        .withUpdatedAt(now);

                                // Append BRANCH_TASK_DUE_EXPIRED history.
                                WorkflowActor expiredBy = new WorkflowActor.System("due-date-expired");
                                return history.nextSequence(workflowId, tx).compose(seq -> {
                                    String hp = Json.encode(
                                            new TaskExpiredHistoryPayload(task.stepId(), taskId, now, expiredBy));
                                    return history.append(
                                                    new WorkflowHistoryEntry(
                                                            workflowId,
                                                            seq,
                                                            WorkflowEntryType.BRANCH_TASK_DUE_EXPIRED,
                                                            hp,
                                                            now),
                                                    tx)
                                            .compose(v2 -> {
                                                // Advance to dueNextStepId.
                                                BranchToken cleared = WorkflowPayloads.clearBranchWait(
                                                        branch, htn.dueNextStepId(), now);
                                                return branchTokens
                                                        .updateOptimistic(cleared, branch.version(), tx)
                                                        .compose(rc -> {
                                                            if (rc == 0) {
                                                                return Future.<Void>failedFuture(
                                                                        new WorkflowConflictException(
                                                                                "Optimistic concurrency conflict"
                                                                                        + " on branch token"
                                                                                        + " during taskDueFired"));
                                                            }
                                                            // Update parent instance state.
                                                            return instances
                                                                    .updateOptimistic(
                                                                            updatedInstForDue, instVersionForDue, tx)
                                                                    .compose(instRc -> {
                                                                        if (instRc == 0) {
                                                                            return Future.<Void>failedFuture(
                                                                                    new WorkflowConflictException(
                                                                                            "Optimistic concurrency"
                                                                                                    + " conflict on parent"
                                                                                                    + " instance during"
                                                                                                    + " branch taskDueFired"));
                                                                        }
                                                                        // updatedInstForDue carries the
                                                                        // post-onDueMutator
                                                                        // state and the current subject ref.
                                                                        return forkJoin.driveAndMaybeJoin(
                                                                                updatedInstForDue, cleared, rw, tx);
                                                                    });
                                                        });
                                            })
                                            .map(v2 -> TaskMutationResult.APPLIED);
                                });
                            });
                        });
                    });
                });
    }

    /**
     * Branch path for {@link #taskReminderFired}: appends a
     * {@link WorkflowEntryType#BRANCH_TASK_REMINDER_FIRED} history entry when a reminder fires on
     * a branch-owned task.
     *
     * <p>Lock order: {@code workflow_timers} (already locked by executor) →
     * {@code workflow_tasks} (via {@code incrementRemindersFiredCount}).
     *
     * @param workflowId      the owning workflow instance id
     * @param task            the task record snapshot
     * @param timerId         the reminder timer id that fired
     * @param scheduledFireAt the {@code fire_at} from the timer row (used to anchor next interval)
     * @param tx              the active transaction
     * @return a {@link Future} that completes when history is appended (or no-op on closed task)
     */
    private Future<Void> doBranchTaskReminderFired(
            WorkflowInstanceId workflowId, TaskRecord task, UUID timerId, Instant scheduledFireAt, SqlClient tx) {
        UUID taskId = task.taskId();
        UUID branchTokenId = task.branchTokenId();

        // Verify the branch is still WAITING on this task (non-locking check first).
        return branchTokens.findById(branchTokenId, tx).compose(optBranch -> {
            if (optBranch.isEmpty()) {
                return Future.succeededFuture();
            }
            BranchToken branch = optBranch.get();
            if (branch.status() != BranchStatus.WAITING
                    || branch.waitType() != WaitType.TASK
                    || !taskId.toString().equals(branch.waitKey())) {
                // Branch has moved on — reminder is irrelevant.
                return Future.succeededFuture();
            }
            // Atomically increment the reminder counter (gated on status='OPEN').
            return taskStore.incrementRemindersFiredCount(taskId, tx).compose(maybeIndex -> {
                if (maybeIndex.isEmpty()) {
                    // Task closed concurrently — no-op.
                    return Future.succeededFuture();
                }
                int reminderIndex = maybeIndex.get();
                Instant now = clock.instant();

                // Resolve the HumanTaskNode to get reminder spec for next scheduling.
                return instances.findById(workflowId, tx).compose(optInst -> {
                    if (optInst.isEmpty()) {
                        return Future.succeededFuture();
                    }
                    WorkflowInstance inst = optInst.get();
                    RuntimeWorkflow rw;
                    try {
                        rw = resolveAndCheckPlanHash(inst);
                    } catch (Exception e) {
                        return Future.<Void>failedFuture(e);
                    }
                    WorkflowNode node = rw.nodeById().get(task.stepId());
                    if (!(node instanceof HumanTaskNode htn)) {
                        return Future.<Void>failedFuture(
                                new WorkflowDefinitionException("Expected HumanTaskNode at step '" + task.stepId()
                                        + "' during branch taskReminderFired"));
                    }
                    ReminderSpec spec = htn.reminders();
                    if (spec == null) {
                        return Future.succeededFuture();
                    }

                    String histPayload = Json.encode(
                            new TaskReminderFiredHistoryPayload(task.stepId(), taskId, timerId, reminderIndex, now));
                    return history.nextSequence(workflowId, tx).compose(seq -> history.append(
                                    new WorkflowHistoryEntry(
                                            workflowId,
                                            seq,
                                            WorkflowEntryType.BRANCH_TASK_REMINDER_FIRED,
                                            histPayload,
                                            now),
                                    tx)
                            .compose(v -> {
                                // Build the same TASK_REMINDER emission shape the single-path produces
                                // (taskReminderFired single-path branch). Branch-owned task reminders
                                // must be observable to external consumers identically to single-path;
                                // only the history-entry type differs.
                                ReminderScheduler.ReminderEmission emission =
                                        switch (spec) {
                                            case ReminderSpec.OneShotOffsets osu -> {
                                                boolean isLast = reminderIndex
                                                        >= osu.offsetsFromTaskCreation()
                                                                .size();
                                                Duration offset = osu.offsetsFromTaskCreation()
                                                        .get(reminderIndex - 1);
                                                yield new ReminderScheduler.ReminderEmission(
                                                        Future.succeededFuture(),
                                                        Map.of(
                                                                "reminderIndex",
                                                                reminderIndex,
                                                                "isLast",
                                                                isLast,
                                                                "kind",
                                                                ReminderScheduler.REMINDER_KIND_ONE_SHOT_OFFSET,
                                                                "offsetFromCreation",
                                                                offset.toString()));
                                            }
                                            case ReminderSpec.RecurringInterval ri -> {
                                                boolean hasMoreFires =
                                                        ri.maxFires() == null || reminderIndex < ri.maxFires();
                                                Future<Void> next = hasMoreFires
                                                        ? reminderScheduler.scheduleBranchReminderTimer(
                                                                inst,
                                                                htn,
                                                                taskId,
                                                                branchTokenId,
                                                                task.forkStepId(),
                                                                task.branchId(),
                                                                scheduledFireAt.plus(ri.interval()),
                                                                tx)
                                                        : Future.succeededFuture();
                                                yield new ReminderScheduler.ReminderEmission(
                                                        next,
                                                        Map.of(
                                                                "reminderIndex",
                                                                reminderIndex,
                                                                "isLast",
                                                                !hasMoreFires,
                                                                "kind",
                                                                ReminderScheduler.REMINDER_KIND_RECURRING,
                                                                "interval",
                                                                ri.interval().toString()));
                                            }
                                        };
                                return emission.scheduleNext()
                                        .compose(ignored -> eventEmitter.emitEvent(
                                                inst,
                                                WorkflowEventType.TASK_REMINDER,
                                                seq,
                                                taskId,
                                                task.stepId(),
                                                emission.attributes(),
                                                tx));
                            }));
                });
            });
        });
    }

    /**
     * Appends a {@link WorkflowEntryType#BRANCH_TASK_COMPLETED} history entry.
     *
     * <p>{@code commandCorrelationId} is resolved via {@link WorkflowPayloads#commandCorrelationId(
     * dev.vertique.workflow.state.WorkflowInstance)}: the ambient correlation bound to the current
     * execution context wins when present; otherwise it falls back to the correlation id captured in
     * {@code inst}'s start-time durable metadata (branch-owned task completion does not bind a
     * correlation context for the duration of the drive — the C2 bind-once rule keeps binding
     * exclusive to the instance path).
     *
     * @param workflowId the owning workflow instance id
     * @param stepId     the task's step id
     * @param taskId     the task UUID
     * @param cmd        the completion command
     * @param now        the completion timestamp
     * @param inst       the owning workflow instance, consulted for its start-time metadata fallback
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the entry is appended
     */
    private Future<Void> appendBranchTaskCompletedHistory(
            WorkflowInstanceId workflowId,
            String stepId,
            UUID taskId,
            TaskCompletionCommand cmd,
            Instant now,
            WorkflowInstance inst,
            SqlClient tx) {
        return history.nextSequence(workflowId, tx).compose(seq -> {
            String hp = Json.encode(new TaskCompletedHistoryPayload(
                    stepId,
                    taskId,
                    cmd.decisionName(),
                    cmd.completedBy(),
                    now,
                    cmd.reviewedSubjectVersion(),
                    WorkflowPayloads.commandCorrelationId(inst)));
            return history.append(
                    new WorkflowHistoryEntry(workflowId, seq, WorkflowEntryType.BRANCH_TASK_COMPLETED, hp, now), tx);
        });
    }

    // --- Task due-date expiry ---

    /**
     * Fires a task's due-date timer.
     *
     * <p>Steps:
     * <ol>
     *   <li>Lock the task row for completion.</li>
     *   <li>If not OPEN → return {@link TaskMutationResult#STALE_NOOP}.</li>
     *   <li>Mark task EXPIRED.</li>
     *   <li>Apply the {@code onDueMutator} to the workflow state.</li>
     *   <li>Optimistic UPDATE on workflow_instances to {@code dueNextStepId}.</li>
     *   <li>Append {@code TASK_EXPIRED} history.</li>
     *   <li>Drive transitions.</li>
     * </ol>
     *
     * @param workflowId the workflow instance this task belongs to
     * @param taskId     the stable UUID of the task whose due-date timer fired
     * @param tx         the active transaction context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskDueFired(WorkflowInstanceId workflowId, UUID taskId, SqlClient tx) {
        // Lock order: workflow_timers BEFORE workflow_tasks (matches taskCompleted/cancel and the
        // reminder-fire executor — preserves the cycle-4 workflow-wide lock-order invariant).
        // Step sequence: (1) non-locking task read for the slot validation, (2) branch-fork on
        // branchTokenId, (3) cancel pending reminder timers — locks REMINDER timer rows, (4) lock
        // the task row, (5) markExpired re-verifies status='OPEN' under lock, (6) advance.
        return taskStore.findById(taskId, tx).compose(optTask -> {
            if (optTask.isEmpty() || optTask.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
            }
            TaskRecord task = optTask.get();
            // Defensive cross-checks before any mutation: the task row must belong to the supplied
            // workflowId. A mismatched call would otherwise mark an unrelated task EXPIRED.
            if (!workflowId.equals(task.workflowId())) {
                return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
            }

            // Branch fork: route branch-owned tasks through the branch-specific path.
            if (task.branchTokenId() != null) {
                return doBranchTaskDueFired(workflowId, task, tx);
            }

            return instances.findById(workflowId, tx).compose(optInst -> {
                if (optInst.isEmpty()) {
                    return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                }
                WorkflowInstance inst = optInst.get();
                if (inst.status() != WorkflowStatus.WAITING
                        || inst.waitType() != WaitType.TASK
                        || !taskId.toString().equals(inst.waitKey())
                        || task.dueDateTimerId() == null
                        || !task.dueDateTimerId().equals(inst.waitAuxId())) {
                    // The instance has already moved on or never matched this task's due-date
                    // wait slot — refuse to mutate the task row.
                    return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                }

                // Binder-row bind (Contract Appendix C2): instance-owned (no branch) due-date
                // expiry drive.
                return contextBinder.withBound(inst, null, () -> doTaskDueFiredBound(taskId, inst, tx));
            });
        });
    }

    /**
     * Executes the due-date expiry drive body after the instance-owned binder-row bind is in
     * effect. Extracted so {@link #taskDueFired} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * <p>Step 2: cancel pending reminder timers FIRST (acquires REMINDER timer row locks ahead of
     * the task row lock). Step 3: lockForCompletion. Step 4: markExpired — gated on
     * status='OPEN'; if a parallel close raced through between the unlocked {@code findById} above
     * and this UPDATE, markExpired returns LOST_TO_* and we STALE_NOOP.
     *
     * @param taskId the task id whose due-date timer fired
     * @param inst   the loaded (WAITING/TASK-slot-matched) instance snapshot
     * @param tx     the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doTaskDueFiredBound(UUID taskId, WorkflowInstance inst, SqlClient tx) {
        return reminderScheduler
                .cancelPendingReminders(taskId, inst, tx)
                .compose(v -> taskStore.lockForCompletion(taskId, tx))
                .compose(optLocked -> {
                    if (optLocked.isEmpty()
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                        return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                    }
                    Instant now = clock.instant();
                    return taskStore.markExpired(taskId, now, tx).compose(transition -> {
                        if (transition != TaskTransition.APPLIED) {
                            return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                        }
                        return advanceForDueExpired(inst, optLocked.get(), taskId, now, tx);
                    });
                });
    }

    /**
     * Helper for {@link #taskDueFired}: applies the {@code onDueMutator} to state, advances the
     * instance to {@code dueNextStepId}, appends {@code TASK_EXPIRED} history, and drives further
     * transitions. Called only after the wait-slot match has been validated.
     *
     * @param inst   the WAITING instance whose due-date timer fired (slot already validated)
     * @param task   the locked task row
     * @param taskId the task id whose due-date timer fired
     * @param now    the expiry timestamp
     * @param tx     the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> advanceForDueExpired(
            WorkflowInstance inst, TaskRecord task, UUID taskId, Instant now, SqlClient tx) {
        RuntimeWorkflow rw;
        try {
            rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
            // Same plan-hash drift gate as completion / signal / timer paths: if the
            // pinned-version plan in the registry has drifted from the snapshot the
            // instance was started against, refuse to apply the due-date mutator
            // (which could route to a different next-step or use a stale state mutator).
            WorkflowPayloads.requirePlanHashMatches(inst, rw);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        WorkflowNode node = rw.nodeById().get(inst.currentStepId());
        if (!(node instanceof HumanTaskNode htn) || htn.onDueMutatorCallbackId() == null) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Expected HumanTaskNode with due-date at step '" + inst.currentStepId() + "'"));
        }

        // Apply onDueMutator to state.
        Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
        Object newState;
        try {
            @SuppressWarnings("unchecked")
            var mutator = (java.util.function.Function<Object, Object>)
                    (java.util.function.Function<?, ?>) rw.callbacks().stateMutator(htn.onDueMutatorCallbackId());
            newState = mutator.apply(stateObj);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "onDueMutator threw for step '" + htn.stepId() + "': " + e.getMessage()));
        }

        long prevVersion = inst.version();
        WorkflowInstance advanced = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.RUNNING)
                .withCurrentStepId(htn.dueNextStepId())
                .withWait(null, null)
                .withState(Json.encode(newState))
                .withError(null, null)
                .withUpdatedAt(now);

        // Reminder timers were already cancelled by the caller (taskDueFired) BEFORE the task row
        // lock was acquired — see taskDueFired for the lock-order rationale.
        return instances
                .updateOptimistic(advanced, prevVersion, tx)
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        return Future.<Void>failedFuture(new WorkflowConflictException(
                                "Optimistic concurrency conflict during taskDueFired for task '" + taskId + "'"));
                    }
                    WorkflowActor expiredBy = new WorkflowActor.System("due-date-expired");
                    return history.nextSequence(inst.id(), tx).compose(seq -> {
                        String hp = Json.encode(new TaskExpiredHistoryPayload(htn.stepId(), taskId, now, expiredBy));
                        return history.append(
                                        new WorkflowHistoryEntry(
                                                inst.id(), seq, WorkflowEntryType.TASK_EXPIRED, hp, now),
                                        tx)
                                .compose(v2 -> eventEmitter.emitEvent(
                                        inst,
                                        WorkflowEventType.TASK_EXPIRED,
                                        seq,
                                        taskId,
                                        htn.stepId(),
                                        Map.of("expiredBy", WorkflowActorMaps.toMap(expiredBy)),
                                        tx));
                    });
                })
                .compose(v -> driver.driveTransitions(advanced, rw, tx))
                .map(v -> TaskMutationResult.APPLIED);
    }

    // --- Task reassignment ---

    /**
     * Reassigns an open human task.
     *
     * <p>Steps:
     * <ol>
     *   <li>Non-locking task lookup to discover workflowId.</li>
     *   <li>Fingerprint computation and dedup claim.</li>
     *   <li>Conditional reassignment UPDATE on the task row.</li>
     *   <li>Append {@code TASK_REASSIGNED} history on success.</li>
     * </ol>
     *
     * @param cmd the reassignment command; must not be null
     * @param tx  the active transaction context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> taskReassigned(TaskReassignmentCommand cmd, SqlClient tx) {
        return taskStore.findById(cmd.taskId(), tx).compose(optTask -> {
            if (optTask.isEmpty()) {
                return Future.failedFuture(new WorkflowTaskNotFoundException(cmd.taskId()));
            }
            TaskRecord task = optTask.get();

            String fingerprint;
            try {
                fingerprint = fingerprintCanonicalizer.fingerprintReassignment(cmd);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }

            return dedup.claimOrResolveTaskReassignment(
                            cmd.taskId(), cmd.idempotencyKey(), task.workflowId(), fingerprint, tx)
                    .compose((DedupClaim claim) -> {
                        if (!claim.inserted()) {
                            if (fingerprint.equals(claim.existingFingerprint())) {
                                // Idempotent retry — same key, same fingerprint. The original
                                // reassignment already updated the row; surface as LOST_TO_RACE
                                // so the public TaskService.reassign(...) caller observes success.
                                return Future.succeededFuture(TaskMutationResult.LOST_TO_RACE);
                            }
                            return Future.failedFuture(new WorkflowIdempotencyConflictException(
                                    "task-reassign", cmd.idempotencyKey(), claim.existingFingerprint(), fingerprint));
                        }

                        // Load the instance for the binder-row bind (Contract Appendix C2) and for
                        // event emission below. taskReassigned has no branch fork at all — every
                        // reassignment is instance-owned — so this is always the binder-row seam,
                        // never the branch carrier.
                        return instances.findById(task.workflowId(), tx).compose(optInst -> {
                            if (optInst.isEmpty()) {
                                return Future.<TaskMutationResult>failedFuture(
                                        new dev.vertique.workflow.exception.WorkflowInstanceNotFoundException(
                                                task.workflowId()));
                            }
                            WorkflowInstance inst = optInst.get();
                            return contextBinder.withBound(inst, null, () -> doReassignBound(cmd, task, inst, tx));
                        });
                    });
        });
    }

    /**
     * Executes the reassignment drive body after the instance-owned binder-row bind is in effect.
     * Extracted so {@link #taskReassigned} can pass it to {@link WorkflowContextBinder#withBound}
     * as a {@link java.util.function.Supplier}.
     *
     * @param cmd  the reassignment command
     * @param task the task record fetched in step 1
     * @param inst the loaded instance snapshot (reused for event emission)
     * @param tx   the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doReassignBound(
            TaskReassignmentCommand cmd, TaskRecord task, WorkflowInstance inst, SqlClient tx) {
        Instant now = clock.instant();
        return taskStore
                .reassign(cmd.taskId(), cmd.newAssignment(), cmd.reassignedBy(), cmd.reason(), now, tx)
                .compose(result -> switch (result) {
                    case TaskReassignmentResult.LostToTerminal ltt ->
                        Future.failedFuture(new WorkflowConflictException(
                                "Task '" + cmd.taskId() + "' is no longer OPEN; reassignment lost"));
                    case TaskReassignmentResult.Applied applied ->
                        history.nextSequence(task.workflowId(), tx)
                                .compose(seq -> {
                                    String hp = Json.encode(new TaskReassignedHistoryPayload(
                                            applied.stepId(),
                                            cmd.taskId(),
                                            applied.oldAssignment(),
                                            cmd.newAssignment(),
                                            cmd.reassignedBy(),
                                            cmd.reason(),
                                            now,
                                            WorkflowPayloads.commandCorrelationId()));
                                    return history.append(
                                                    new WorkflowHistoryEntry(
                                                            task.workflowId(),
                                                            seq,
                                                            WorkflowEntryType.TASK_REASSIGNED,
                                                            hp,
                                                            now),
                                                    tx)
                                            .compose(v -> eventEmitter.emitEvent(
                                                    inst,
                                                    WorkflowEventType.TASK_REASSIGNED,
                                                    seq,
                                                    cmd.taskId(),
                                                    applied.stepId(),
                                                    WorkflowEventEmitter.attrs(
                                                            "oldAssignment",
                                                            WorkflowActorMaps.toMap(applied.oldAssignment()),
                                                            "newAssignment",
                                                            WorkflowActorMaps.toMap(cmd.newAssignment()),
                                                            "reassignedBy",
                                                            WorkflowActorMaps.toMap(cmd.reassignedBy()),
                                                            "reason",
                                                            cmd.reason()),
                                                    tx));
                                })
                                .map(v -> TaskMutationResult.APPLIED);
                });
    }

    // --- Task reminder firing ---

    /**
     * Fires a task reminder.
     *
     * <p>Cycle-4 reminder-fire handling: appends a {@code TASK_REMINDER_FIRED} history entry and
     * emits a {@code WORKFLOW_EVENT} side-effect when the task is still
     * {@link dev.vertique.workflow.tasks.TaskStatus#OPEN}. Returns a succeeded future for both
     * the applied and no-op (task already closed) paths; the executor marks the timer
     * {@code FIRED} unconditionally on a succeeded future.
     *
     * @param workflowId the owning workflow instance id
     * @param taskId     the stable UUID of the task
     * @param timerId    the UUID of the firing reminder timer
     * @param scheduledFireAt the {@code fire_at} of the firing timer (anchors the next interval)
     * @param tx         the active transaction context
     * @return succeeded future on both applied and no-op paths; failed future on transient errors
     */
    Future<Void> taskReminderFired(
            WorkflowInstanceId workflowId, UUID taskId, UUID timerId, Instant scheduledFireAt, SqlClient tx) {
        // Non-locking task read to discover branch ownership.
        return taskStore.findById(taskId, tx).compose(maybeTask -> {
            if (maybeTask.isEmpty() || maybeTask.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                // Task already closed or not found — no-op; executor marks timer FIRED.
                return Future.succeededFuture();
            }
            TaskRecord task = maybeTask.get();

            // Branch fork: route branch-owned tasks through the branch-specific path.
            if (task.branchTokenId() != null) {
                return doBranchTaskReminderFired(workflowId, task, timerId, scheduledFireAt, tx);
            }

            // Single-path: verify the parent instance is WAITING on this task.
            return instances.findById(workflowId, tx).compose(maybeInst -> {
                if (maybeInst.isEmpty()) {
                    // Workflow purged — no-op; executor marks the timer FIRED.
                    return Future.succeededFuture();
                }
                WorkflowInstance inst = maybeInst.get();
                if (inst.status() != WorkflowStatus.WAITING || inst.waitType() != WaitType.TASK) {
                    return Future.succeededFuture();
                }
                // Verify this reminder belongs to the task the workflow is currently waiting on.
                if (!taskId.toString().equals(inst.waitKey())) {
                    return Future.succeededFuture();
                }

                // Binder-row bind (Contract Appendix C2): instance-owned (no branch) reminder-fire
                // drive.
                return contextBinder.withBound(
                        inst,
                        null,
                        () -> doTaskReminderFiredBound(workflowId, task, inst, timerId, scheduledFireAt, tx));
            });
        });
    }

    /**
     * Executes the reminder-fire drive body after the instance-owned binder-row bind is in effect.
     * Extracted so {@link #taskReminderFired} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param workflowId      the owning workflow instance id
     * @param task            the task record fetched by {@link #taskReminderFired}
     * @param inst            the loaded (WAITING/TASK-slot-matched) instance snapshot
     * @param timerId         the UUID of the firing reminder timer
     * @param scheduledFireAt the {@code fire_at} of the firing timer (anchors the next interval)
     * @param tx              the active transaction
     * @return succeeded future on both applied and no-op paths; failed future on transient errors
     */
    private Future<Void> doTaskReminderFiredBound(
            WorkflowInstanceId workflowId,
            TaskRecord task,
            WorkflowInstance inst,
            UUID timerId,
            Instant scheduledFireAt,
            SqlClient tx) {
        UUID taskId = task.taskId();
        // task was already loaded — re-use it below.
        RuntimeWorkflow rw;
        try {
            rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        try {
            WorkflowPayloads.requirePlanHashMatches(inst, rw);
        } catch (WorkflowPlanHashDriftException e) {
            return Future.failedFuture(e);
        }
        WorkflowNode node = rw.nodeById().get(task.stepId());
        if (!(node instanceof HumanTaskNode htn)) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Expected HumanTaskNode at step '" + task.stepId() + "' during taskReminderFired"));
        }
        ReminderSpec spec = htn.reminders();
        if (spec == null) {
            return Future.succeededFuture();
        }

        // Atomically increment the durable reminder counter on workflow_tasks. The UPDATE is
        // gated on status='OPEN', so a concurrent terminal transition between the earlier
        // findById check and this write is observed as Optional.empty() and we no-op (the
        // executor will still markFired the timer). Replaces the cycle-4 first-cut JSONB-path
        // COUNT(*) over workflow_history, which both scaled poorly and was vulnerable to
        // duplicate counting on concurrent fires (no row lock to serialize).
        return taskStore.incrementRemindersFiredCount(taskId, tx).compose(maybeIndex -> {
            if (maybeIndex.isEmpty()) {
                // Task closed concurrently with this fire — emit nothing, let the executor
                // markFired the timer as a normal no-op.
                return Future.succeededFuture();
            }
            int reminderIndex = maybeIndex.get();
            Instant now = clock.instant();
            String histPayload = Json.encode(
                    new TaskReminderFiredHistoryPayload(task.stepId(), taskId, timerId, reminderIndex, now));
            return history.nextSequence(workflowId, tx).compose(seq -> history.append(
                            new WorkflowHistoryEntry(
                                    workflowId, seq, WorkflowEntryType.TASK_REMINDER_FIRED, histPayload, now),
                            tx)
                    .compose(v -> {
                        // Exhaustive switch on the sealed ReminderSpec — the spec is
                        // non-null here (filtered earlier) and ReminderSpec has exactly
                        // two permits, so the compiler proves totality.
                        ReminderScheduler.ReminderEmission emission =
                                switch (spec) {
                                    case ReminderSpec.OneShotOffsets osu -> {
                                        boolean isLast = reminderIndex
                                                >= osu.offsetsFromTaskCreation().size();
                                        Duration offset =
                                                osu.offsetsFromTaskCreation().get(reminderIndex - 1);
                                        yield new ReminderScheduler.ReminderEmission(
                                                Future.succeededFuture(),
                                                Map.of(
                                                        "reminderIndex",
                                                        reminderIndex,
                                                        "isLast",
                                                        isLast,
                                                        "kind",
                                                        ReminderScheduler.REMINDER_KIND_ONE_SHOT_OFFSET,
                                                        "offsetFromCreation",
                                                        offset.toString()));
                                    }
                                    case ReminderSpec.RecurringInterval ri -> {
                                        boolean hasMoreFires = ri.maxFires() == null || reminderIndex < ri.maxFires();
                                        // Anchor the next fire to the firing timer's scheduled fire_at, NOT
                                        // clock.instant(). Anchoring to the fired-at clock would let executor
                                        // delays accumulate as drift across many fires; anchoring to the
                                        // persisted fire_at keeps the cadence steady regardless of when this
                                        // tx actually runs.
                                        Future<Void> next = hasMoreFires
                                                ? reminderScheduler.scheduleReminderTimer(
                                                        inst, htn, taskId, scheduledFireAt.plus(ri.interval()), tx)
                                                : Future.succeededFuture();
                                        yield new ReminderScheduler.ReminderEmission(
                                                next,
                                                Map.of(
                                                        "reminderIndex",
                                                        reminderIndex,
                                                        "isLast",
                                                        !hasMoreFires,
                                                        "kind",
                                                        ReminderScheduler.REMINDER_KIND_RECURRING,
                                                        "interval",
                                                        ri.interval().toString()));
                                    }
                                };
                        return emission.scheduleNext()
                                .compose(ignored -> eventEmitter.emitEvent(
                                        inst,
                                        WorkflowEventType.TASK_REMINDER,
                                        seq,
                                        taskId,
                                        task.stepId(),
                                        emission.attributes(),
                                        tx));
                    }));
        });
    }

    // --- Internal helpers ---

    /**
     * Resolves the runtime workflow for {@code inst} and enforces the plan-hash drift guard
     * (PRD-WF-002 round-1 fix H2 + round-2 single-path adjacent fix). Synchronous helper used by
     * branch callback paths that need both lookups before any row mutation; the caller wraps the
     * thrown exception in a failed Future. Mirrors the inline pattern used in the single-path
     * callbacks (e.g. {@link #doCompleteTask}).
     *
     * @param inst the workflow instance whose definition + plan hash to resolve and check
     * @return the resolved {@link RuntimeWorkflow} for {@code inst}'s definition/version
     * @throws WorkflowVersionPinUnavailableException if the registry no longer has the pinned version
     * @throws WorkflowPlanHashDriftException if the stored plan hash differs from the resolved plan
     */
    private RuntimeWorkflow resolveAndCheckPlanHash(WorkflowInstance inst) {
        RuntimeWorkflow rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
        WorkflowPayloads.requirePlanHashMatches(inst, rw);
        return rw;
    }

    /**
     * Appends a {@code TIMER_CANCELLED} history entry for a task-owned due-date timer that was
     * cancelled because the task completed.
     *
     * <p>Delegates to the canonical
     * {@link TimerLifecycleService#appendTimerCancelledHistory} via the lazy
     * {@link #timerPvd} back-edge, which breaks the {@code timer → task → timer} Dagger cycle.
     *
     * @param inst the workflow instance that owns the timer wait
     * @param timerId the id of the timer that was cancelled
     * @param cause the reason the timer was cancelled
     * @param tx the active transaction
     * @return a {@link Future} that completes when the history entry is inserted
     */
    private Future<Void> appendTimerCancelledHistory(
            WorkflowInstance inst, UUID timerId, TimerCancelledCause cause, SqlClient tx) {
        return timerPvd.get().appendTimerCancelledHistory(inst, timerId, cause, tx);
    }
}
