// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMaps;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.executor.ClaimLostException;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.EndedInstanceOutcomes;
import dev.vertique.workflow.engine.executor.Savepoints;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DedupClaim;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.TaskReservations;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowStaleAssignmentException;
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.exception.WorkflowTaskNotFoundException;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.HumanTaskNode.AssignmentSpec;
import dev.vertique.workflow.plan.ReminderSpec;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
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
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.tasks.TaskTransition;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator owning the human-task lifecycle callback paths.
 *
 * <p>This class is extracted from {@link WorkflowEngine} as part of the Phase-1 decomposition
 * (PRD-WF-006, Slice C7). It absorbs the {@code TransactionalTaskCallbacks} bodies and their
 * supporting helpers:
 * <ul>
 *   <li>{@link #taskCompleted} — records a task completion behind a reservation of the task; {@link #runCompletion}
 *       applies it in an executor segment through {@link #doCompleteTaskBound} (single-path) or
 *       {@link #doBranchCompleteTask} (branch-owned).</li>
 *   <li>{@link #dueFired} / {@link #doTaskDueFiredBound} / {@link #advanceForDueExpired} /
 *       {@link #doBranchTaskDueFired} — due-date expiry.</li>
 *   <li>{@link #taskReassigned} — task reassignment.</li>
 *   <li>{@link #reminderFired} / {@link #doTaskReminderFiredBound} /
 *       {@link #doBranchTaskReminderFired} — reminder firing.</li>
 *   <li>The task helpers {@link #buildTaskRecord} (static), {@link #resolveAssignment},
 *       {@link #cancelTaskOwnedTimers}, {@link #appendTaskCreatedHistory},
 *       {@link #appendTaskCancelledHistory}, and {@link #appendBranchTaskCompletedHistory}.</li>
 * </ul>
 *
 * <p>The facade's {@code TransactionalTaskCallbacks} entrypoints
 * ({@code taskCompleted}/{@code taskReassigned}) delegate their bodies to this service, and
 * the executor runs {@link #dueFired} and {@link #reminderFired} for the timer commands of a task. Payload validation/coercion and plan-hash checks reuse the
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

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleService.class);

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
     * instance-owned), {@link #dueFired}, and {@link #reminderFired}. The branch-owned
     * paths ({@link #doBranchCompleteTask} and the due/reminder branch forks) MUST NOT use this
     * binder — the bind-once routing rule reserves branch-owned drives for the
     * {@link BranchTransitionEngine} carrier seam. Never {@code null} — the engine assembly seam
     * substitutes {@link WorkflowContextBinder#noop()} when the propagator is {@code null}, so
     * every instance-owned task drive calls {@link WorkflowContextBinder#withBound} directly.
     */
    private final WorkflowContextBinder contextBinder;

    /**
     * Opt-in constraint check applied to coerced decision payloads right after coercion and before
     * the task is completed. On the branch path the timer cancel, locks and history work run first,
     * inside the same transaction; a rejection rolls that transaction back, so any earlier side
     * effect and the deduplication claim roll back with it. A no-op when the application binds no
     * bean validator.
     */
    private final PayloadConstraintCheck payloadConstraintCheck;

    /** Resolves the logical decision payload type names the plan holds. */
    private final WorkflowTypeNames typeNames;

    /** Takes the execution lock of a task's instance before a completion or a reassignment writes. */
    private final WorkflowCommandRepository<SqlClient> commands;

    /** Reserves a task for a recorded completion and reopens it when the completion is rejected. */
    private final TaskReservations<SqlClient> reservations;

    /** Writes the history of what became of a recorded completion. */
    private final WorkflowHistoryRecorder historyRecorder;

    /** The size, depth and schema checks a completion meets before it is recorded. */
    private final RecordTimePayloadValidator recordValidator;

    /** Produces the payload schema of each decision a new task offers; absent when no schema module is installed. */
    private final Optional<WorkflowPlanSchemaProducer> schemaProducer;

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
     * @param payloadConstraintCheck the opt-in constraint check applied to coerced decision payloads
     * @param typeNames the type-name index the registry fills as definitions register; a decision
     *     payload name the index does not know is resolved as a binary class name
     * @param commands the command repository whose execution lock a completion or a reassignment takes after it has
     *     found the task's instance
     * @param reservations the reservations through which a recorded completion owns its task
     * @param historyRecorder the recorder of the history entries of command outcomes
     * @param recording the recording configuration whose bounds a completion's payload must meet, or empty for the
     *     defaults
     * @param schemaProducer the producer of the payload schemas a new task's decision snapshot stores, if any
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
            WorkflowContextBinder contextBinder,
            PayloadConstraintCheck payloadConstraintCheck,
            WorkflowTypeNames typeNames,
            WorkflowCommandRepository<SqlClient> commands,
            TaskReservations<SqlClient> reservations,
            WorkflowHistoryRecorder historyRecorder,
            Optional<WorkflowRecordingConfig> recording,
            Optional<WorkflowPlanSchemaProducer> schemaProducer) {
        this.commands = commands;
        this.reservations = reservations;
        this.historyRecorder = historyRecorder;
        this.recordValidator = new RecordTimePayloadValidator(recording.orElseGet(WorkflowRecordingConfig::defaults));
        this.schemaProducer = schemaProducer;
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
        this.payloadConstraintCheck = payloadConstraintCheck;
        this.typeNames = typeNames;
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
     * @return a new {@link TaskRecord} ready for {@link TaskStore#insertOpen}; it records the node's
     *     {@code requireVersionStability} so the requirement can be checked from the row
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
                null, // branchId
                inst.scopeKey(),
                htn.requireVersionStability(),
                null); // reservationCommandId
    }

    /**
     * Builds the decision snapshot a new task stores: each decision's name, payload type and next step, and the JSON
     * Schema of its payload when a schema producer is installed. A task whose snapshot carries no schemas is validated
     * by the executor alone.
     *
     * @param htn the human task node the task is created for
     * @param rw  the resolved runtime workflow the node belongs to
     * @return the descriptors, one per decision of the node
     */
    List<TaskDecisionDescriptor> decisionSnapshot(HumanTaskNode htn, RuntimeWorkflow rw) {
        return DecisionSnapshots.of(htn, rw, schemaProducer);
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
     * Records the completion of a human task and returns once it is recorded; an executor applies it afterwards.
     *
     * <p>Everything here reads the task row alone, never the plan:
     * <ol>
     *   <li>A committed duplicate (the same key and fingerprint) returns its earlier result at once, before any other
     *       check.</li>
     *   <li>Then, without a lock, the payload's size and depth bounds and, when the task's decision snapshot carries a
     *       schema for the requested decision, the payload's conformance to it. A refusal writes nothing and takes no
     *       lock.</li>
     *   <li>Under the execution lock: the duplicate again (a key with another fingerprint is a conflict), the task
     *       still {@code OPEN}, the expected assignment, the reviewed subject version when the task requires
     *       stability, and the decision.</li>
     *   <li>Then the deduplication claim, the {@code COMPLETION} command and the reservation of the task
     *       ({@code OPEN} to {@code COMPLETING}, owned by the command).</li>
     * </ol>
     *
     * <p>A task created before decision schemas and the stability flag were stored skips the checks that need them; the
     * executor runs them from the plan.
     *
     * @param cmd the completion command; must not be null
     * @param tx  the active transaction context
     * @return a {@link Future} resolving to {@link TaskMutationResult#APPLIED} once the completion is recorded, and also
     *     for a committed duplicate, which returns its earlier result
     */
    Future<TaskMutationResult> taskCompleted(TaskCompletionCommand cmd, SqlClient tx) {
        String oversized = oversizedIdentifier(cmd);
        if (oversized != null) {
            // The value is stored with the command: refuse it before any lock is taken, and echo none of it.
            return Future.failedFuture(new WorkflowInvalidPayloadException(
                    "Completion of task '" + cmd.taskId() + "' is refused: the " + oversized + " exceeds "
                            + MAX_IDENTIFIER_LENGTH + " characters",
                    List.of(ViolationDetail.unlocated("the " + oversized + " is too long", "payload"))));
        }
        String fingerprint;
        try {
            fingerprint = fingerprintCanonicalizer.fingerprintCompletion(cmd);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        return dedup.findTaskCompletion(cmd.taskId(), cmd.idempotencyKey(), tx).compose(committed -> {
            if (committed.isPresent() && fingerprint.equals(committed.get())) {
                return Future.succeededFuture(TaskMutationResult.APPLIED);
            }
            return taskStore.findById(cmd.taskId(), tx).compose(optTask -> {
                if (optTask.isEmpty()) {
                    return Future.<TaskMutationResult>failedFuture(new WorkflowTaskNotFoundException(cmd.taskId()));
                }
                TaskRecord task = optTask.get();
                try {
                    checkPayload(cmd, task);
                } catch (WorkflowInvalidPayloadException e) {
                    return Future.<TaskMutationResult>failedFuture(e);
                }
                return recordCompletion(cmd, fingerprint, task, tx);
            });
        });
    }

    /** The longest reviewed subject version or actor id a recorded completion carries, in characters. */
    private static final int MAX_IDENTIFIER_LENGTH = 255;

    /** Names the first caller-supplied identifier of the completion that is too long to store, or {@code null}. */
    private static @Nullable String oversizedIdentifier(TaskCompletionCommand cmd) {
        if (cmd.reviewedSubjectVersion() != null && cmd.reviewedSubjectVersion().length() > MAX_IDENTIFIER_LENGTH) {
            return "reviewed subject version";
        }
        String actorId = WorkflowActorMaps.toMap(cmd.completedBy()).get("value");
        return actorId != null && actorId.length() > MAX_IDENTIFIER_LENGTH ? "completing actor id" : null;
    }

    /**
     * Checks the payload's bounds and, when the task's snapshot holds a schema for the requested decision, its
     * conformance to that schema. A decision the snapshot does not hold, or one without a schema, is left to the checks
     * under the lock and to the executor.
     */
    private void checkPayload(TaskCompletionCommand cmd, TaskRecord task) {
        JsonObject schema = task.decisions().stream()
                .filter(decision -> decision.name().equals(cmd.decisionName()))
                .map(TaskDecisionDescriptor::payloadSchema)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        try {
            if (schema == null) {
                recordValidator.validate(cmd.payload());
            } else {
                recordValidator.validate(
                        cmd.payload(), schema, "decision '" + EchoedNames.bounded(cmd.decisionName()) + "'");
            }
        } catch (WorkflowInvalidPayloadException e) {
            throw new WorkflowInvalidPayloadException(
                    "Completion of task '" + task.taskId() + "' of instance '"
                            + task.workflowId().value() + "' is refused: " + e.getMessage(),
                    e.violations());
        }
    }

    /** The part of the recording that runs under the instance's execution lock. */
    private Future<TaskMutationResult> recordCompletion(
            TaskCompletionCommand cmd, String fingerprint, TaskRecord task, SqlClient tx) {
        return commands.lockInstance(task.workflowId(), tx)
                .compose(locked -> dedup.findTaskCompletion(cmd.taskId(), cmd.idempotencyKey(), tx))
                .compose(committed -> {
                    if (committed.isPresent()) {
                        return duplicateOf(cmd, fingerprint, committed.get());
                    }
                    return taskStore.lockForCompletion(cmd.taskId(), tx).compose(optLocked -> {
                        if (optLocked.isEmpty()) {
                            return Future.<TaskMutationResult>failedFuture(
                                    new WorkflowTaskNotFoundException(cmd.taskId()));
                        }
                        TaskRecord locked = optLocked.get();
                        RuntimeException refusal = refusalOf(cmd, locked);
                        if (refusal != null) {
                            return Future.<TaskMutationResult>failedFuture(refusal);
                        }
                        return reserve(cmd, fingerprint, locked, tx);
                    });
                });
    }

    /** The result of a completion whose key is already committed: the earlier result, or a conflict. */
    private static Future<TaskMutationResult> duplicateOf(
            TaskCompletionCommand cmd, String fingerprint, String existingFingerprint) {
        if (fingerprint.equals(existingFingerprint)) {
            return Future.succeededFuture(TaskMutationResult.APPLIED);
        }
        return Future.failedFuture(new WorkflowIdempotencyConflictException(
                "task-complete", cmd.idempotencyKey(), existingFingerprint, fingerprint));
    }

    /**
     * Why the locked task row cannot take the completion, in the order the checks run, or {@code null} when it can:
     * the task is not {@code OPEN}, the caller's expected assignment is stale, the reviewed subject version is missing or
     * different on a task that requires stability, or the decision is not in the snapshot.
     */
    @Nullable
    private static RuntimeException refusalOf(TaskCompletionCommand cmd, TaskRecord locked) {
        if (locked.status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
            return new WorkflowTaskNotWaitingException(cmd.taskId());
        }
        if (cmd.expectedAssignment() != null && !cmd.expectedAssignment().equals(locked.assignment())) {
            return new WorkflowStaleAssignmentException(cmd.taskId(), cmd.expectedAssignment(), locked.assignment());
        }
        if (Boolean.TRUE.equals(locked.requireVersionStability())) {
            if (cmd.reviewedSubjectVersion() == null) {
                return new WorkflowStaleSubjectVersionException("Task '" + cmd.taskId() + "' (step '" + locked.stepId()
                        + "') requires a reviewedSubjectVersion but the caller did not supply one");
            }
            if (!Objects.equals(cmd.reviewedSubjectVersion(), locked.subjectVersionAtCreation())) {
                return new WorkflowStaleSubjectVersionException("Task '" + cmd.taskId() + "' (step '"
                        + locked.stepId() + "'): reviewed version '" + EchoedNames.bounded(cmd.reviewedSubjectVersion())
                        + "' is not the version the task was created for");
            }
        }
        boolean offered =
                locked.decisions().stream().anyMatch(decision -> decision.name().equals(cmd.decisionName()));
        return offered ? null : WorkflowPayloads.unknownDecision();
    }

    /** The deduplication claim, the {@code COMPLETION} command and the reservation, under the execution lock. */
    private Future<TaskMutationResult> reserve(
            TaskCompletionCommand cmd, String fingerprint, TaskRecord task, SqlClient tx) {
        return dedup.claimOrResolveTaskCompletion(
                        cmd.taskId(), cmd.idempotencyKey(), task.workflowId(), fingerprint, tx)
                .compose((DedupClaim claim) -> {
                    if (!claim.inserted()) {
                        return duplicateOf(cmd, fingerprint, claim.existingFingerprint());
                    }
                    String payloadJson = cmd.payload() == null ? null : Json.encode(cmd.payload());
                    return commands.recordCompletion(
                                    task.workflowId(),
                                    cmd.taskId(),
                                    cmd.idempotencyKey(),
                                    payloadJson,
                                    CompletionTarget.of(cmd).encode(),
                                    tx)
                            .compose(commandId ->
                                    reservations.markCompleting(cmd.taskId().toString(), commandId, tx))
                            .compose(reserved -> reserved
                                    ? Future.succeededFuture(TaskMutationResult.APPLIED)
                                    : Future.<TaskMutationResult>failedFuture(
                                            new WorkflowTaskNotWaitingException(cmd.taskId())));
                });
    }

    /**
     * What a recorded completion carries beyond its payload: the task, the decision, the reviewed subject version and
     * the completing actor. Stored as the command's target and read back by the executor.
     *
     * @param taskId                the task the completion reserves
     * @param decisionName          the decision
     * @param reviewedSubjectVersion the subject version the reviewer saw, or {@code null}
     * @param completedBy           the actor completing the task
     */
    private record CompletionTarget(
            UUID taskId, String decisionName, @Nullable String reviewedSubjectVersion, WorkflowActor completedBy) {

        static CompletionTarget of(TaskCompletionCommand cmd) {
            return new CompletionTarget(
                    cmd.taskId(), cmd.decisionName(), cmd.reviewedSubjectVersion(), cmd.completedBy());
        }

        String encode() {
            JsonObject target = new JsonObject()
                    .put("taskId", taskId.toString())
                    .put("decision", decisionName)
                    .put("completedBy", new JsonObject(new LinkedHashMap<>(WorkflowActorMaps.toMap(completedBy))));
            if (reviewedSubjectVersion != null) {
                target.put("reviewedSubjectVersion", reviewedSubjectVersion);
            }
            return target.encode();
        }

        static CompletionTarget of(SegmentCommand command) {
            JsonObject target = new JsonObject(command.targetJson());
            JsonObject actor = target.getJsonObject("completedBy");
            String value = actor.getString("value");
            WorkflowActor completedBy =
                    switch (actor.getString("kind")) {
                        case "USER" -> new WorkflowActor.User(value);
                        case "SERVICE" -> new WorkflowActor.Service(value);
                        default -> new WorkflowActor.System(value);
                    };
            return new CompletionTarget(
                    UUID.fromString(target.getString("taskId")),
                    target.getString("decision"),
                    target.getString("reviewedSubjectVersion"),
                    completedBy);
        }

        TaskCompletionCommand toCommand(@Nullable String payloadJson, String idempotencyKey) {
            Object payload = payloadJson == null ? null : Json.decodeValue(payloadJson);
            return new TaskCompletionCommand(
                    taskId, decisionName, payload, idempotencyKey, completedBy, reviewedSubjectVersion);
        }
    }

    // --- Recorded completions in a segment ---

    /**
     * Runs the work of a claimed {@code COMPLETION} command inside the segment's transaction and settles it, except when
     * its application succeeds: then the executor completes it.
     *
     * <ul>
     *   <li>The instance has ended: the command is rejected and the task, no longer completable, is cancelled.</li>
     *   <li>The task is not {@code COMPLETING} under this command: the command is stale and no task is touched.</li>
     *   <li>The instance has failed, or no longer waits for the task: the command is rejected and the task reopened.</li>
     *   <li>Otherwise the decision is applied under a savepoint: the task is completed and the instance advances. A
     *       deterministic failure or a step-limit overrun rolls back to the savepoint, the command is rejected with a
     *       sanitized reason and the task is reopened; the instance is unchanged.</li>
     * </ul>
     *
     * @param head    the peeked head the claim came from
     * @param claim   the claim
     * @param command the fenced command
     * @param tx      the segment's transaction handle
     * @return the outcome the executor acts on
     */
    Future<WorkflowSegments.Outcome> runCompletion(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        CompletionTarget target;
        try {
            target = CompletionTarget.of(command);
        } catch (RuntimeException e) {
            return Future.failedFuture(
                    new IllegalStateException("The recorded completion " + command.commandId() + " cannot be read"));
        }
        CompletionTarget recorded = target;
        return instances.findById(command.workflowId(), tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.<WorkflowSegments.Outcome>failedFuture(
                        new dev.vertique.workflow.exception.WorkflowInstanceNotFoundException(command.workflowId()));
            }
            WorkflowInstance inst = found.get();
            if (EndedInstanceOutcomes.isEnded(inst.status())) {
                return settleEnded(
                        claim,
                        command,
                        recorded,
                        EndedInstanceOutcomes.of(
                                        command.kind(),
                                        inst.status().name(),
                                        EndedInstanceOutcomes.Target.completion(recorded.decisionName()))
                                .orElseThrow(),
                        tx);
            }
            if (!inst.definitionScopeKey().equals(head.definitionScopeKey())
                    || !inst.definitionId().equals(head.definitionId())
                    || inst.definitionVersion() != head.definitionVersion()
                    || !Objects.equals(inst.planHash(), head.planHash())) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            }
            RuntimeWorkflow rw;
            try {
                rw = resolveAndCheckPlanHash(inst);
            } catch (dev.vertique.workflow.exception.WorkflowVersionNotRunnableException
                    | WorkflowPlanHashDriftException e) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            return taskStore.findById(recorded.taskId(), tx).compose(optTask -> {
                if (optTask.isEmpty()
                        || optTask.get().status() != dev.vertique.workflow.tasks.TaskStatus.COMPLETING
                        || !Objects.equals(optTask.get().reservationCommandId(), command.commandId())) {
                    return settleStale(
                            claim,
                            command,
                            recorded,
                            CommandErrors.completionNotReserved(recorded.decisionName()),
                            false,
                            tx);
                }
                TaskRecord task = optTask.get();
                if (inst.status() == WorkflowStatus.FAILED) {
                    return settleRejected(
                            claim,
                            command,
                            recorded,
                            EndedInstanceOutcomes.of(
                                            command.kind(),
                                            inst.status().name(),
                                            EndedInstanceOutcomes.Target.completion(recorded.decisionName()))
                                    .orElseThrow()
                                    .reason(),
                            tx);
                }
                if (task.branchTokenId() == null && !waitsForTask(inst, recorded.taskId())) {
                    return settleRejected(
                            claim, command, recorded, CommandErrors.completionFailure(recorded.decisionName()), tx);
                }
                return applyCompletion(claim, command, recorded, task, inst, rw, tx);
            });
        });
    }

    private static boolean waitsForTask(WorkflowInstance inst, UUID taskId) {
        return inst.status() == WorkflowStatus.WAITING
                && inst.waitType() == WaitType.TASK
                && taskId.toString().equals(inst.waitKey());
    }

    private Future<WorkflowSegments.Outcome> applyCompletion(
            ClaimedCommand claim,
            SegmentCommand command,
            CompletionTarget target,
            TaskRecord task,
            WorkflowInstance inst,
            RuntimeWorkflow rw,
            SqlClient tx) {
        TaskCompletionCommand cmd;
        DurableMetadata recorded;
        try {
            cmd = target.toCommand(command.payloadJson(), command.dedupKey());
            recorded = WorkflowContextBinder.decodeCarrier(command.contextJson());
        } catch (RuntimeException e) {
            return Future.failedFuture(
                    new IllegalStateException("The recorded completion " + command.commandId() + " cannot be read"));
        }
        return historyRecorder
                .appendCommandHistory(
                        inst.id(), WorkflowEntryType.COMMAND_RECEIVED, command.commandId(), command.kind(), tx)
                .compose(received -> Savepoints.attempt(tx, () -> {
                    if (task.branchTokenId() != null) {
                        // A branch drive never binds the recorded context: the branch token's own carrier is bound.
                        return doBranchCompleteTask(cmd, task, command.commandId(), tx);
                    }
                    return contextBinder.withRecordedBase(
                            inst, command.commandId(), null, recorded, () -> doCompleteTaskBound(cmd, task, inst, tx));
                }))
                .compose(attempt -> switch (attempt) {
                    case Savepoints.Attempt.Applied<TaskMutationResult> applied ->
                        applied.value() == TaskMutationResult.APPLIED
                                ? historyRecorder
                                        .appendCommandHistory(
                                                inst.id(),
                                                WorkflowEntryType.COMMAND_COMPLETED,
                                                command.commandId(),
                                                command.kind(),
                                                tx)
                                        .map(done -> WorkflowSegments.Outcome.APPLIED)
                                : settleStale(
                                        claim,
                                        command,
                                        target,
                                        CommandErrors.completionNotReserved(target.decisionName()),
                                        true,
                                        tx);
                    case Savepoints.Attempt.Contained<TaskMutationResult> contained -> {
                        log.warn(
                                "Completion of task {} (command {}) of instance {} failed and was rejected: {}",
                                target.taskId(),
                                command.commandId(),
                                inst.id().value(),
                                contained.cause().getClass().getName());
                        String reason = contained.kind() == Savepoints.Containment.STEP_LIMIT
                                ? CommandErrors.completionStepLimit(target.decisionName(), driver.stepCap(tx))
                                : CommandErrors.completionFailure(target.decisionName());
                        yield settleRejected(claim, command, target, reason, tx);
                    }
                });
    }

    /** Rejects the command and reopens its task, in the segment's transaction. */
    private Future<WorkflowSegments.Outcome> settleRejected(
            ClaimedCommand claim, SegmentCommand command, CompletionTarget target, String reason, SqlClient tx) {
        return commands.reject(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> reservations.reopen(target.taskId().toString(), command.commandId(), tx))
                .compose(reopened -> {
                    if (!reopened) {
                        log.warn(
                                "The task {} was not reserved by its rejected completion {}",
                                target.taskId(),
                                command.commandId());
                    }
                    return historyRecorder.appendCompletionCommandHistory(
                            command.workflowId(),
                            WorkflowEntryType.COMMAND_REJECTED,
                            command.commandId(),
                            target.taskId(),
                            target.decisionName(),
                            reason,
                            tx);
                })
                .map(done -> WorkflowSegments.Outcome.SETTLED_REJECTED);
    }

    /**
     * Settles the command as stale, in the segment's transaction. When {@code reopen} is set, the task it still
     * reserves is reopened; a stale command never touches a task another command reserves.
     */
    private Future<WorkflowSegments.Outcome> settleStale(
            ClaimedCommand claim,
            SegmentCommand command,
            CompletionTarget target,
            String reason,
            boolean reopen,
            SqlClient tx) {
        Future<Boolean> released = reopen
                ? reservations.reopen(target.taskId().toString(), command.commandId(), tx)
                : Future.succeededFuture(false);
        return released.compose(ignored -> commands.stale(command.commandId(), claim.token(), reason, tx))
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendCompletionCommandHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_STALE,
                        command.commandId(),
                        target.taskId(),
                        target.decisionName(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_STALE);
    }

    /**
     * Settles the command of an ended instance as the outcome table states, rejected or cancelled, and cancels the task
     * it reserved.
     */
    private Future<WorkflowSegments.Outcome> settleEnded(
            ClaimedCommand claim,
            SegmentCommand command,
            CompletionTarget target,
            EndedInstanceOutcomes.Outcome outcome,
            SqlClient tx) {
        boolean cancelled = outcome.settlement() == EndedInstanceOutcomes.Settlement.CANCELLED;
        Instant now = clock.instant();
        return reservations
                .reopen(target.taskId().toString(), command.commandId(), tx)
                .compose(reopened -> reopened
                        ? taskStore
                                .markCancelled(
                                        target.taskId(),
                                        "INSTANCE_ENDED",
                                        now,
                                        new WorkflowActor.System("INSTANCE_ENDED"),
                                        tx)
                                .<Void>mapEmpty()
                        : Future.<Void>succeededFuture())
                .compose(done -> cancelled
                        ? commands.cancel(command.commandId(), claim.token(), outcome.reason(), tx)
                        : commands.reject(command.commandId(), claim.token(), outcome.reason(), tx))
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendCompletionCommandHistory(
                        command.workflowId(),
                        cancelled ? WorkflowEntryType.COMMAND_CANCELLED : WorkflowEntryType.COMMAND_REJECTED,
                        command.commandId(),
                        target.taskId(),
                        target.decisionName(),
                        outcome.reason(),
                        tx))
                .map(done -> cancelled
                        ? WorkflowSegments.Outcome.SETTLED_CANCELLED
                        : WorkflowSegments.Outcome.SETTLED_REJECTED);
    }

    /**
     * Applies a recorded single-path completion after the instance-owned binder-row bind is in effect: the decision is
     * validated against the plan, the due-date and reminder timers are cancelled, the task is completed, the
     * applicator runs and the instance advances to the decision's next step.
     *
     * @param cmd  the completion command, rebuilt from the recorded command
     * @param task the task row, {@code COMPLETING} under this completion
     * @param inst the loaded (WAITING/TASK-slot-matched) instance snapshot
     * @param tx   the segment's transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doCompleteTaskBound(
            TaskCompletionCommand cmd, TaskRecord task, WorkflowInstance inst, SqlClient tx) {
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
        if (!(node instanceof HumanTaskNode htn)) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "Expected HumanTaskNode at step '" + inst.currentStepId() + "' but found "
                            + (node == null ? "null" : node.getClass().getSimpleName())));
        }

        // Version-stability check from the plan, for tasks whose row does not record the requirement.
        if (htn.requireVersionStability()) {
            if (cmd.reviewedSubjectVersion() == null) {
                return Future.failedFuture(
                        new WorkflowStaleSubjectVersionException("Task '" + cmd.taskId() + "' (step '" + htn.stepId()
                                + "') requires a reviewedSubjectVersion but the caller did not supply one"));
            }
            if (!Objects.equals(cmd.reviewedSubjectVersion(), task.subjectVersionAtCreation())) {
                return Future.failedFuture(
                        new WorkflowStaleSubjectVersionException("Task '" + cmd.taskId() + "' (step '" + htn.stepId()
                                + "'): reviewed version '" + EchoedNames.bounded(cmd.reviewedSubjectVersion())
                                + "' is not the version the task was created for"));
            }
        }

        // Find the matching decision in the plan.
        HumanTaskNode.TaskDecision planDecision = htn.decisions().stream()
                .filter(d -> d.name().equals(cmd.decisionName()))
                .findFirst()
                .orElse(null);
        if (planDecision == null) {
            return Future.failedFuture(WorkflowPayloads.unknownDecision());
        }

        // Payload-nullability validation + coercion.
        Object coercedPayload;
        try {
            coercedPayload = WorkflowPayloads.validateAndCoerceTaskPayload(
                    cmd.payload(), planDecision.payloadTypeName(), typeNames);
            payloadConstraintCheck.check(coercedPayload, WorkflowPayloads.DECISION_REJECTION);
        } catch (Exception e) {
            logRejectedPayloadCause(e);
            return Future.failedFuture(e);
        }

        // Cancel the due-date timer if present (timers before tasks, lock order). A due-date timer that already fired
        // against the reserved task settled itself as stale and holds nothing back.
        UUID dueDateTimerId = inst.waitAuxId();
        Future<Void> cancelTimerFuture = Future.succeededFuture();
        if (dueDateTimerId != null) {
            cancelTimerFuture = timerStore
                    .markCancelled(dueDateTimerId, clock.instant(), tx)
                    .compose(transition -> switch (transition) {
                        case APPLIED, LOST_TO_CANCELLED ->
                            appendTimerCancelledHistory(inst, dueDateTimerId, TimerCancelledCause.TASK_COMPLETED, tx);
                        case LOST_TO_FIRED, LOST_TO_FAILED -> Future.<Void>succeededFuture();
                    });
        }

        Object finalCoercedPayload = coercedPayload;
        HumanTaskNode.TaskDecision finalDecision = planDecision;

        return cancelTimerFuture
                // Cancel pending reminder timers (lock order: timers before tasks).
                .compose(v -> reminderScheduler.cancelPendingReminders(cmd.taskId(), inst, tx))
                // Lock the task row.
                .compose(v -> taskStore.lockForCompletion(cmd.taskId(), tx))
                .compose(optLocked -> {
                    if (optLocked.isEmpty()
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.COMPLETING) {
                        return Future.<TaskMutationResult>failedFuture(
                                new WorkflowConflictException("Task '" + cmd.taskId() + "' is no longer COMPLETING"));
                    }

                    Instant now = clock.instant();
                    String payloadJson = finalCoercedPayload != null ? Json.encode(finalCoercedPayload) : null;

                    // Mark completed.
                    return taskStore
                            .markCompleted(cmd.taskId(), cmd.decisionName(), payloadJson, now, cmd.completedBy(), tx)
                            .compose(transition -> {
                                if (transition != TaskTransition.APPLIED) {
                                    return Future.failedFuture(new WorkflowConflictException("Task '" + cmd.taskId()
                                            + "' completion lost to concurrent terminal transition"));
                                }

                                // Apply decision applicator.
                                Object stateObj = Json.decodeValue(Buffer.buffer(inst.stateJson()), rw.stateType());
                                Object newState;
                                try {
                                    @SuppressWarnings("unchecked")
                                    var applicator = (java.util.function.BiFunction<Object, Object, Object>)
                                            (java.util.function.BiFunction<?, ?, ?>) rw.callbacks()
                                                    .decisionApplicator(finalDecision.applicatorCallbackId());
                                    newState = applicator.apply(stateObj, finalCoercedPayload);
                                } catch (Exception e) {
                                    return Future.failedFuture(new WorkflowDefinitionException(
                                            "decisionApplicator threw for decision '" + cmd.decisionName() + "'", e));
                                }

                                // Optimistic UPDATE on workflow_instances.
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
                                            // Append TASK_COMPLETED history + emit event.
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
                                        // Drive transitions from the next step.
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
     * @param commandId the id of the executor command that applies the completion
     * @param tx   the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doBranchCompleteTask(
            TaskCompletionCommand cmd, TaskRecord task, long commandId, SqlClient tx) {
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
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.COMPLETING) {
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
                                                    + EchoedNames.bounded(cmd.reviewedSubjectVersion())
                                                    + "' is not the version the task was created for"));
                                }
                            }

                            // Step 8: Find the matching decision in the plan (for applicatorCallbackId).
                            HumanTaskNode.TaskDecision planDecision = htn.decisions().stream()
                                    .filter(d -> d.name().equals(cmd.decisionName()))
                                    .findFirst()
                                    .orElse(null);
                            if (planDecision == null) {
                                return Future.failedFuture(WorkflowPayloads.unknownDecision());
                            }

                            // Step 9: Payload-nullability validation + coercion.
                            Object coercedPayload;
                            try {
                                coercedPayload = WorkflowPayloads.validateAndCoerceTaskPayload(
                                        cmd.payload(), planDecision.payloadTypeName(), typeNames);
                                payloadConstraintCheck.check(coercedPayload, WorkflowPayloads.DECISION_REJECTION);
                            } catch (Exception e) {
                                logRejectedPayloadCause(e);
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
                                                            + cmd.decisionName() + "'",
                                                    e));
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
                                                                                updatedInst,
                                                                                cleared,
                                                                                rw,
                                                                                commandId,
                                                                                tx))
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
     * Branch path for {@link #dueFired}: fires a branch-owned task's due-date timer.
     *
     * <p>The due-date timer is identified by {@link TaskRecord#dueDateTimerId()}, which the caller
     * matched to route here. The timer row is already locked by the timer command that runs this path.
     *
     * <p>Lock order: {@code workflow_timers} (already locked by the executor) →
     * {@code workflow_tasks} → {@code workflow_branch_tokens} → {@code workflow_instances}
     * (loaded non-locking for context).
     *
     * @param workflowId the owning workflow instance id
     * @param task       the task record (non-locking snapshot from the caller)
     * @param commandId  the id of the executor command that fires the due date
     * @param tx         the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doBranchTaskDueFired(
            WorkflowInstanceId workflowId, TaskRecord task, long commandId, SqlClient tx) {
        // Resolve the pinned plan BEFORE any write: a node that cannot run the pinned version must
        // leave the reminders and the task untouched so the timer can be re-queued for a node that can.
        // The body acts on this instance snapshot and plan; it resolves nothing after its first write.
        return instances.findById(workflowId, tx).compose(optPinned -> {
            if (optPinned.isEmpty()) {
                return Future.<TaskMutationResult>succeededFuture(TaskMutationResult.STALE_NOOP);
            }
            WorkflowInstance inst = optPinned.get();
            RuntimeWorkflow rw;
            try {
                rw = resolveAndCheckPlanHash(inst);
            } catch (Exception e) {
                return Future.<TaskMutationResult>failedFuture(e);
            }
            return doBranchTaskDueFiredResolved(workflowId, task, inst, rw, commandId, tx);
        });
    }

    /**
     * Body of {@link #doBranchTaskDueFired} once the pinned plan is known to be runnable on this node.
     *
     * @param workflowId the owning workflow instance id
     * @param task       the task record (non-locking snapshot from the caller)
     * @param inst       the instance snapshot the plan was resolved for
     * @param rw         the plan resolved for {@code inst} and checked against its stored hash
     * @param commandId  the id of the executor command that fires the due date
     * @param tx         the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> doBranchTaskDueFiredResolved(
            WorkflowInstanceId workflowId,
            TaskRecord task,
            WorkflowInstance inst,
            RuntimeWorkflow rw,
            long commandId,
            SqlClient tx) {
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
                            // Act on the instance and plan resolved before the first write.
                            WorkflowNode node = rw.nodeById().get(task.stepId());
                            if (!(node instanceof HumanTaskNode htn) || htn.onDueMutatorCallbackId() == null) {
                                return Future.<TaskMutationResult>failedFuture(
                                        new WorkflowDefinitionException("Expected HumanTaskNode with due-date at step '"
                                                + task.stepId()
                                                + "' during branch dueFired"));
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
                                            BranchToken cleared =
                                                    WorkflowPayloads.clearBranchWait(branch, htn.dueNextStepId(), now);
                                            return branchTokens
                                                    .updateOptimistic(cleared, branch.version(), tx)
                                                    .compose(rc -> {
                                                        if (rc == 0) {
                                                            return Future.<Void>failedFuture(
                                                                    new WorkflowConflictException(
                                                                            "Optimistic concurrency conflict"
                                                                                    + " on branch token"
                                                                                    + " during dueFired"));
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
                                                                                                + " branch dueFired"));
                                                                    }
                                                                    // updatedInstForDue carries the
                                                                    // post-onDueMutator
                                                                    // state and the current subject ref.
                                                                    return forkJoin.driveAndMaybeJoin(
                                                                            updatedInstForDue,
                                                                            cleared,
                                                                            rw,
                                                                            commandId,
                                                                            tx);
                                                                });
                                                    });
                                        })
                                        .map(v2 -> TaskMutationResult.APPLIED);
                            });
                        });
                    });
                });
    }

    /**
     * Branch path for {@link #reminderFired}: appends a
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
            // Resolve the pinned plan BEFORE any write: a node that cannot run the pinned version
            // must leave the reminder counter untouched, because the executor re-queues the timer
            // in this same transaction and the re-queue commits.
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
                // Atomically increment the reminder counter (gated on status='OPEN').
                return taskStore.incrementRemindersFiredCount(taskId, tx).compose(maybeIndex -> {
                    if (maybeIndex.isEmpty()) {
                        // Task closed concurrently — no-op.
                        return Future.succeededFuture();
                    }
                    int reminderIndex = maybeIndex.get();
                    Instant now = clock.instant();

                    // Resolve the HumanTaskNode to get reminder spec for next scheduling.
                    WorkflowNode node = rw.nodeById().get(task.stepId());
                    if (!(node instanceof HumanTaskNode htn)) {
                        return Future.<Void>failedFuture(new WorkflowDefinitionException(
                                "Expected HumanTaskNode at step '" + task.stepId() + "' during branch reminderFired"));
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
                                // (reminderFired single-path branch). Branch-owned task reminders
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
     * Fires a task's due date, binding the durable context of an instance-owned expiry with
     * the given binding.
     *
     * @param workflowId the workflow instance this task belongs to
     * @param taskId     the stable UUID of the task whose due date passed
     * @param binding    binds the context of the instance-owned expiry drive
     * @param tx         the active transaction context
     * @return a {@link Future} resolving to the mutation result
     */
    Future<TaskMutationResult> dueFired(
            WorkflowInstanceId workflowId, UUID taskId, DriveBinding binding, SqlClient tx) {
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
                return doBranchTaskDueFired(workflowId, task, binding.commandId(), tx);
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

                // Instance-owned (no branch) due-date expiry drive.
                return binding.run(inst, () -> doTaskDueFiredBound(taskId, inst, tx));
            });
        });
    }

    /**
     * Executes the due-date expiry drive body after the instance-owned binder-row bind is in
     * effect. Extracted so {@link #dueFired} can pass it to
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
        // Resolve the pinned plan BEFORE any write: a node that cannot run the pinned version must
        // leave the reminders and the task untouched so the timer can be re-queued for a node that can.
        RuntimeWorkflow rw;
        try {
            rw = resolveAndCheckPlanHash(inst);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
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
                        return advanceForDueExpired(inst, rw, taskId, now, tx);
                    });
                });
    }

    /**
     * Helper for {@link #dueFired}: applies the {@code onDueMutator} to state, advances the
     * instance to {@code dueNextStepId}, appends {@code TASK_EXPIRED} history, and drives further
     * transitions. Called only after the wait-slot match has been validated.
     *
     * @param inst   the WAITING instance whose due-date timer fired (slot already validated)
     * @param rw     the pinned plan, already resolved and plan-hash checked before any write
     * @param taskId the task id whose due-date timer fired
     * @param now    the expiry timestamp
     * @param tx     the active transaction
     * @return a {@link Future} resolving to the mutation result
     */
    private Future<TaskMutationResult> advanceForDueExpired(
            WorkflowInstance inst, RuntimeWorkflow rw, UUID taskId, Instant now, SqlClient tx) {
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

        // Reminder timers were already cancelled by the caller (dueFired) BEFORE the task row
        // lock was acquired — see dueFired for the lock-order rationale.
        return instances
                .updateOptimistic(advanced, prevVersion, tx)
                .compose(rowCount -> {
                    if (rowCount == 0) {
                        return Future.<Void>failedFuture(new WorkflowConflictException(
                                "Optimistic concurrency conflict during dueFired for task '" + taskId + "'"));
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
     *   <li>Lock the task row; require OPEN and a matching {@code expectedAssignment} when supplied; reassign.</li>
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

            // The execution lock comes before the dedup claim, the first write.
            return commands.lockInstance(task.workflowId(), tx)
                    .compose(locked -> dedup.claimOrResolveTaskReassignment(
                            cmd.taskId(), cmd.idempotencyKey(), task.workflowId(), fingerprint, tx))
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
        // Lock the task row first so the expectedAssignment precondition is checked against the
        // row the reassignment then mutates.
        return taskStore
                .lockForCompletion(cmd.taskId(), tx)
                .compose(optLocked -> {
                    if (optLocked.isPresent()
                            && optLocked.get().status() == dev.vertique.workflow.tasks.TaskStatus.COMPLETING) {
                        // A task being completed is reserved by its recorded completion and cannot move.
                        return Future.<TaskReassignmentResult>failedFuture(
                                new WorkflowTaskNotWaitingException(cmd.taskId()));
                    }
                    if (optLocked.isEmpty()
                            || optLocked.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                        return Future.<TaskReassignmentResult>failedFuture(new WorkflowConflictException(
                                "Task '" + cmd.taskId() + "' is no longer OPEN; reassignment lost"));
                    }
                    if (cmd.expectedAssignment() != null
                            && !cmd.expectedAssignment().equals(optLocked.get().assignment())) {
                        return Future.<TaskReassignmentResult>failedFuture(new WorkflowStaleAssignmentException(
                                cmd.taskId(),
                                cmd.expectedAssignment(),
                                optLocked.get().assignment()));
                    }
                    return taskStore.reassign(
                            cmd.taskId(), cmd.newAssignment(), cmd.reassignedBy(), cmd.reason(), now, tx);
                })
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
     * Fires a task reminder, binding the durable context of an instance-owned reminder
     * with the given binding, and tells whether the reminder applied to a task that is still open and awaited.
     *
     * @param workflowId      the owning workflow instance id
     * @param taskId          the stable UUID of the task
     * @param timerId         the UUID of the firing reminder timer
     * @param scheduledFireAt the {@code fire_at} of the firing timer (anchors the next interval)
     * @param binding         binds the context of the instance-owned reminder drive
     * @param tx              the active transaction context
     * @return {@link TaskMutationResult#APPLIED} when the reminder fired, {@link TaskMutationResult#STALE_NOOP} when its
     *     task is closed or no longer awaited
     */
    Future<TaskMutationResult> reminderFired(
            WorkflowInstanceId workflowId,
            UUID taskId,
            UUID timerId,
            Instant scheduledFireAt,
            DriveBinding binding,
            SqlClient tx) {
        // Non-locking task read to discover branch ownership.
        return taskStore.findById(taskId, tx).compose(maybeTask -> {
            if (maybeTask.isEmpty() || maybeTask.get().status() != dev.vertique.workflow.tasks.TaskStatus.OPEN) {
                // Task already closed or not found — no-op; executor marks timer FIRED.
                return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
            }
            TaskRecord task = maybeTask.get();

            // Branch fork: route branch-owned tasks through the branch-specific path.
            if (task.branchTokenId() != null) {
                return doBranchTaskReminderFired(workflowId, task, timerId, scheduledFireAt, tx)
                        .map(done -> TaskMutationResult.APPLIED);
            }

            // Single-path: verify the parent instance is WAITING on this task.
            return instances.findById(workflowId, tx).compose(maybeInst -> {
                if (maybeInst.isEmpty()) {
                    // Workflow purged — no-op; executor marks the timer FIRED.
                    return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                }
                WorkflowInstance inst = maybeInst.get();
                if (inst.status() != WorkflowStatus.WAITING || inst.waitType() != WaitType.TASK) {
                    return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                }
                // Verify this reminder belongs to the task the workflow is currently waiting on.
                if (!taskId.toString().equals(inst.waitKey())) {
                    return Future.succeededFuture(TaskMutationResult.STALE_NOOP);
                }

                // Instance-owned (no branch) reminder-fire drive.
                return binding.run(
                                inst,
                                () -> doTaskReminderFiredBound(workflowId, task, inst, timerId, scheduledFireAt, tx))
                        .map(done -> TaskMutationResult.APPLIED);
            });
        });
    }

    /**
     * Executes the reminder-fire drive body after the instance-owned binder-row bind is in effect.
     * Extracted so {@link #reminderFired} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param workflowId      the owning workflow instance id
     * @param task            the task record fetched by {@link #reminderFired}
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
            rw = PinnedPlans.resolve(registry, inst);
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
                    "Expected HumanTaskNode at step '" + task.stepId() + "' during reminderFired"));
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
     * @throws dev.vertique.workflow.exception.WorkflowVersionNotRunnableException if the local registry has no plan
     *     for the pinned version
     * @throws WorkflowPlanHashDriftException if the stored plan hash differs from the resolved plan
     */
    private RuntimeWorkflow resolveAndCheckPlanHash(WorkflowInstance inst) {
        RuntimeWorkflow rw = PinnedPlans.resolve(registry, inst);
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

    /**
     * Records the binder failure behind a rejected completion payload at DEBUG so an operator can
     * diagnose it, while the rejection itself stays value-free. The log text is constant; the
     * cause is attached for the logging backend only.
     */
    private static void logRejectedPayloadCause(Exception rejection) {
        if (rejection instanceof WorkflowInvalidPayloadException && rejection.getCause() != null) {
            log.debug("Task completion payload rejected", rejection.getCause());
        }
    }
}
