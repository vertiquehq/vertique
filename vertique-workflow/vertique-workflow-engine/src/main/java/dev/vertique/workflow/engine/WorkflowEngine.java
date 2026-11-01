// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.db.IsolationLevel;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMaps;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.engine.executor.ClaimLostException;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.executor.EndedInstanceOutcomes;
import dev.vertique.workflow.engine.executor.StartPayloadValidation;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.history.DecisionLog;
import dev.vertique.workflow.engine.history.WorkflowHistoryEventPublisher;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.SettledCommand;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.TaskReservations;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads.StartSelection;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads.StartVersion;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowSignalActorException;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalLimitException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatusTransition;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialect-neutral implementation of {@link WorkflowOperations} and
 * {@link TransactionalWorkflowOperations}, exposed as the composite {@link WorkflowEngineHandle}.
 *
 * <p>This class is the primary entry point for the portable workflow engine. Persistence is injected
 * at composition time via the {@code SqlClient}-typed repository SPIs, so the engine carries no
 * dependency on any specific SQL dialect. It orchestrates durable saga execution by:
 * <ul>
 *   <li>Resolving the {@link RuntimeWorkflow} from the {@link WorkflowRegistry} using the
 *       definition id and version pin stored on the instance.</li>
 *   <li>Evaluating the current workflow node and advancing the plan within a single database
 *       transaction (atomically persisting instance state, history, and dedup records).</li>
 *   <li>Routing side-effect intents via {@link RecorderRouter} to registered
 *       {@link dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder}s.</li>
 *   <li>Enforcing optimistic concurrency via the {@code version} column on
 *       {@code workflow_instances}.</li>
 * </ul>
 *
 * <p>Public methods open a transaction via {@link WorkflowTransactionRunner#inTransaction} and
 * delegate to the tx-aware variants. {@link #query(WorkflowInstanceId)} runs at
 * {@code REPEATABLE READ} so its two reads see a consistent snapshot. The runner owns the
 * transaction boundary and the layered DB-to-workflow exception mapping.
 *
 * <p>JSON serialization uses {@code io.vertx.core.json.Json} (not Jackson directly) to stay on the
 * Vert.x codec path.
 */
@Singleton
final class WorkflowEngine implements WorkflowEngineHandle {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    // --- Dependencies ---

    private final WorkflowTransactionRunner<SqlClient> txRunner;
    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final WorkflowDedupRepository<SqlClient> dedup;
    private final WorkflowSignalWaitRepository<SqlClient> signalWaits;

    /** Deletes the records of the signal waits of an instance that is cancelled. */
    private final SignalWaits waitRecords;

    private final WorkflowTypeNames typeNames;
    private final TimerStore<SqlClient> timerStore;
    private final TaskStore<SqlClient> taskStore;
    private final Clock clock;
    private final BranchTokenRepository<SqlClient> branchTokens;
    /**
     * Used only as a null-presence guard in the branch-aware signal path to detect whether the
     * engine was constructed without fork/join collaborators (legacy test constructors).
     */
    private final BranchTransitionEngine branchEngine;
    /**
     * Captures the ambient durable context at workflow start (PRD-WF-007 AC-1, FR-WF-CTX-010/012).
     * Start is capture-only: {@link #doCreateInstance} calls {@link DurableContextPropagator#capture}
     * but never installs a new durable scope around the start drive.
     */
    private final DurableContextPropagator propagator;
    /** Leaf collaborator for emitting {@code WORKFLOW_EVENT} side-effect intents. */
    private final WorkflowEventEmitter eventEmitter;
    /** Leaf collaborator for appending generic workflow lifecycle history entries. */
    private final WorkflowHistoryRecorder historyRecorder;
    /** The decision log whose first-claim baseline, start baseline and segment scope the engine writes. */
    private final DecisionLog decisions;
    /** Leaf collaborator for scheduling and cancelling {@link dev.vertique.workflow.timer.TimerPurpose#TASK_REMINDER} timers. */
    private final ReminderScheduler reminderScheduler;
    /**
     * Collaborator owning fork dispatch and join evaluation (PRD-WF-002). Injected eagerly; the
     * coordinator holds a lazy {@code Provider<WorkflowTransitionDriver>} back-edge so the
     * driver↔fork/join mutual recursion does not form a Dagger constructor cycle.
     */
    private final ForkJoinCoordinator forkJoin;

    /**
     * Collaborator owning the core state-machine transition driver (PRD-WF-006, slice C5). The
     * facade injects it eagerly and calls {@link WorkflowTransitionDriver#driveTransitions} from its
     * lifecycle entrypoints (start / signal / cancel / retry / migrate and the task/timer callbacks).
     * The driver reaches the timer helper {@code resolveFireAt} (moved to
     * {@link TimerLifecycleService} in C8) through a lazy {@code Provider<TimerLifecycleService>} and
     * its task-creation helpers ({@code resolveAssignment}, {@code appendTaskCreatedHistory}, static
     * {@code buildTaskRecord}, moved to {@link TaskLifecycleService} in C7) through a lazy
     * {@code Provider<TaskLifecycleService>}. After C8 the driver no longer references this facade.
     */
    private final WorkflowTransitionDriver driver;

    /**
     * Collaborator owning the workflow-signal delivery paths (PRD-WF-006, Slice C6). The facade's
     * public {@code signal(...)} transaction entrypoints open the transaction via the runner and
     * delegate their bodies to {@link SignalHandler#applyBranchSignal} / {@link
     * SignalHandler#doApplySignal}. Injected eagerly; the handler references the driver and the
     * fork/join coordinator (both eager) but nothing references the handler, so no Dagger cycle.
     */
    private final SignalHandler signalHandler;

    /**
     * Collaborator owning the human-task lifecycle callback paths (PRD-WF-006, Slice C7). The
     * facade's {@code TransactionalTaskCallbacks} entrypoints
     * ({@code taskCompleted}/{@code taskReassigned})
     * delegate their bodies to this service. It drives the state machine forward after a task
     * mutation via the {@link WorkflowTransitionDriver} and {@link ForkJoinCoordinator}; the driver
     * reaches its task-creation helpers ({@code resolveAssignment}, {@code appendTaskCreatedHistory},
     * static {@code buildTaskRecord}) through a lazy {@code Provider<TaskLifecycleService>} that
     * breaks the driver↔task constructor cycle.
     */
    private final TaskLifecycleService taskLifecycle;

    /**
     * Collaborator owning the timer-fire paths (PRD-WF-006, Slice C8). The executor runs a recorded
     * timer command through it. It drives the state machine forward after a timer fires
     * via the {@link WorkflowTransitionDriver} and {@link ForkJoinCoordinator}, and runs the
     * task due-date and reminder fires of {@link TaskLifecycleService} (so it injects the task
     * service eagerly). The driver reaches the timer helper {@code resolveFireAt} through a lazy
     * {@code Provider<TimerLifecycleService>} that breaks the driver↔timer constructor cycle.
     */
    private final TimerLifecycleService timerLifecycle;

    /**
     * Collaborator owning the instance-migration flow (PRD-WF-006, Slice C9). The facade's public
     * {@code migrate(...)} transaction entrypoints open the transaction via the runner and delegate
     * their bodies to {@link MigrationExecutor#migrate}. The executor owns the opt-in registry and
     * the null-registry {@link UnsupportedOperationException} fail-fast guard. Injected eagerly; it
     * drives transitions via the {@link WorkflowTransitionDriver} (eager) but nothing references it
     * back, so no Dagger cycle.
     */
    private final MigrationExecutor migrationExecutor;

    /**
     * Binder-row seam for instance-owned single-path drives (engine assembly seam, Contract
     * Appendix C2). Wraps {@code signal}/{@code cancel}/{@code retry} drive bodies after the
     * instance is loaded (the {@code migrate} entrypoint delegates its wrap to
     * {@link MigrationExecutor}; the task/timer lifecycle callbacks delegate theirs to
     * {@link TaskLifecycleService} / {@link TimerLifecycleService}). Never {@code null} — the
     * public assembly seam ({@link WorkflowEngineFactory#create}) substitutes
     * {@link WorkflowContextBinder#noop()} when the propagator is {@code null}, so every call site
     * invokes {@link WorkflowContextBinder#withBound} directly.
     */
    private final WorkflowContextBinder contextBinder;

    /**
     * Opt-in constraint check applied to the coerced start payload before the instance is created.
     * A no-op when the application binds no bean validator.
     */
    private final PayloadConstraintCheck payloadConstraintCheck;

    /**
     * The reads of definition rows on the caller's transaction that a start selects its version from; {@code null} when
     * the store cannot read on a caller's transaction, which fails a start that has no replay to answer.
     */
    private final @Nullable TransactionalDefinitionReads<SqlClient> definitionReads;

    /** Checks a start payload against the stored start schema of the selected version, within the recording bounds. */
    private final StartPayloadValidation startPayloadValidation;

    /** Waits until the recorded commands of an instance, or of every instance, have run. */
    private final WorkflowExecutionProbe executionProbe;

    /**
     * Takes the execution lock of an instance inside the caller's transaction, before the first workflow change of
     * every instance-changing entry point.
     */
    private final WorkflowCommandRepository<SqlClient> commands;

    /** The control of this engine's executor; a supplier because the executor is built from this engine's segments. */
    private final Supplier<WorkflowExecutorControl> executorControl;

    /** The size and depth bounds a signal's payload and explicit carrier must meet to be recorded. */
    private final RecordTimePayloadValidator recordValidator;

    /** The most signals an instance may hold open. */
    private final int maxOpenSignals;

    /** Takes the actor a recorded command is logged with from the identity of the caller that records it. */
    private final CommandActors actors;

    /** Routes the intents of a segment; told which command a segment executes so that it can attribute them. */
    private final RecorderRouter recorderRouter;

    /** Announces the history a committed transaction wrote, and the actions that failed, to the history listeners. */
    private final WorkflowHistoryEventPublisher historyPublisher;

    @Inject
    WorkflowEngine(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowSignalWaitRepository<SqlClient> signalWaits,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            BranchTokenRepository<SqlClient> branchTokens,
            BranchTransitionEngine branchEngine,
            WorkflowEventEmitter eventEmitter,
            WorkflowHistoryRecorder historyRecorder,
            ReminderScheduler reminderScheduler,
            ForkJoinCoordinator forkJoin,
            WorkflowTransitionDriver driver,
            SignalHandler signalHandler,
            TaskLifecycleService taskLifecycle,
            TimerLifecycleService timerLifecycle,
            MigrationExecutor migrationExecutor,
            DurableContextPropagator propagator,
            WorkflowContextBinder contextBinder,
            PayloadConstraintCheck payloadConstraintCheck,
            Optional<TransactionalDefinitionReads<SqlClient>> definitionReads,
            WorkflowExecutionProbe executionProbe,
            WorkflowCommandRepository<SqlClient> commands,
            Provider<WorkflowExecutorControl> executorControl,
            Optional<WorkflowRecordingConfig> recording,
            CommandActors actors,
            RecorderRouter recorderRouter,
            WorkflowHistoryEventPublisher historyPublisher,
            WorkflowTypeNames typeNames) {
        WorkflowRecordingConfig recordingConfig = recording.orElseGet(WorkflowRecordingConfig::defaults);
        this.historyPublisher = historyPublisher;
        this.actors = actors;
        this.recorderRouter = recorderRouter;
        this.recordValidator = new RecordTimePayloadValidator(recordingConfig);
        this.startPayloadValidation = new StartPayloadValidation(recordValidator);
        this.maxOpenSignals = recordingConfig.maxOpenSignalsPerInstance();
        this.executionProbe = executionProbe;
        this.commands = commands;
        this.executorControl = executorControl::get;
        this.definitionReads = definitionReads.orElse(null);
        this.txRunner = txRunner;
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.dedup = dedup;
        this.signalWaits = signalWaits;
        this.waitRecords = new SignalWaits(signalWaits, commands, historyRecorder, clock);
        this.typeNames = typeNames;
        this.timerStore = timerStore;
        this.taskStore = taskStore;
        this.clock = clock;
        this.branchTokens = branchTokens;
        this.branchEngine = branchEngine;
        this.eventEmitter = eventEmitter;
        this.historyRecorder = historyRecorder;
        this.decisions = historyRecorder.decisions();
        this.reminderScheduler = reminderScheduler;
        this.forkJoin = forkJoin;
        this.driver = driver;
        this.signalHandler = signalHandler;
        this.taskLifecycle = taskLifecycle;
        this.timerLifecycle = timerLifecycle;
        this.migrationExecutor = migrationExecutor;
        this.propagator = propagator;
        this.contextBinder = contextBinder;
        this.payloadConstraintCheck = payloadConstraintCheck;
    }

    /**
     * Assembles the engine with the default recording bounds; see the constructor that takes them.
     */
    WorkflowEngine(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowSignalWaitRepository<SqlClient> signalWaits,
            RecorderRouter recorders,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            FingerprintCanonicalizer fingerprintCanonicalizer,
            Clock clock,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            BranchTransitionEngine branchEngine,
            BranchCompensationOrchestrator branchCompensation,
            @Nullable DurableContextPropagator propagator,
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowEventEmitter eventEmitter,
            WorkflowHistoryRecorder historyRecorder,
            ReminderScheduler reminderScheduler,
            CompensationService compensationService,
            @Nullable dev.vertique.context.InboundExecutionContextScope inboundExecScope,
            PayloadConstraintCheck payloadConstraintCheck,
            WorkflowTypeNames typeNames,
            Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility,
            WorkflowExecutionProbe executionProbe,
            WorkflowCommandRepository<SqlClient> commands,
            TaskReservations<SqlClient> reservations,
            Supplier<WorkflowExecutorControl> executorControl,
            @Nullable TransactionalDefinitionReads<SqlClient> definitionReads) {
        this(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                signalWaits,
                recorders,
                timerStore,
                taskStore,
                fingerprintCanonicalizer,
                clock,
                branchTokens,
                joinStates,
                branchEngine,
                branchCompensation,
                propagator,
                migrationRegistryOpt,
                eventEmitter,
                historyRecorder,
                reminderScheduler,
                compensationService,
                inboundExecScope,
                payloadConstraintCheck,
                typeNames,
                stateCompatibility,
                executionProbe,
                commands,
                executorControl,
                WorkflowRecordingConfig.defaults(),
                reservations,
                Optional.empty(),
                key -> Optional.empty(),
                definitionReads,
                CommandActors.ambient(),
                WorkflowHistoryEventPublisher.disabled());
    }

    /**
     * Shared field-assignment + driver/coordinator-construction path used by the
     * {@link WorkflowEngineFactory} assembly seam. The transition driver, the fork/join coordinator,
     * the task-lifecycle service, and the timer-lifecycle service are built here (not passed in) so
     * the driver↔fork/join mutual-recursion cycle, the driver↔task cycle, and the driver↔timer cycle
     * can be wired by hand: the coordinator is built first with a lazy
     * {@code Provider<WorkflowTransitionDriver>} (resolved from a mutable holder once the driver
     * exists) and a lazy {@code Provider<WorkflowEngine>} returning {@code this}; the driver is then
     * built eagerly against that coordinator with a lazy {@code Provider<TimerLifecycleService>} (for
     * {@code resolveFireAt}) and a lazy {@code Provider<TaskLifecycleService>} (for the task-creation
     * helpers), both resolved from holders once those services exist; the task-lifecycle service is
     * built eagerly against the driver and coordinator; finally the timer-lifecycle service is built
     * eagerly against the driver, coordinator, and task-lifecycle service. All providers are
     * dereferenced only at runtime, by which point construction has completed and the fields above
     * are assigned. Production wiring instead uses the {@code @Inject} constructor, which receives the
     * Dagger-managed singletons.
     *
     * <p>The {@code propagator} parameter is stored on the facade (used by
     * {@link #doCreateInstance} to capture the ambient durable context at start, PRD-WF-007 AC-1) in
     * addition to being threaded into the {@link ForkJoinCoordinator} for branch-creation capture. A
     * {@code null} propagator is tolerated for legacy test callers that do not exercise durable-context
     * capture — {@link #doCreateInstance} then persists {@code null} instance metadata.
     *
     * <p>The {@code inboundExecScope} parameter is the substrate lifecycle helper backing the
     * binder-row {@link WorkflowContextBinder} (factory initializer parity, see
     * {@link WorkflowEngineFactory#create(WorkflowEngineParts)}): production Dagger wiring
     * runs every registered {@code InboundContextInitializer} (e.g. correlation seeding) on a bound
     * drive, and this constructor lets the factory assembly seam do the same. The factory builds
     * exactly one {@link dev.vertique.context.InboundExecutionContextScope} from the propagator and
     * the initializer set and passes that same instance both here and into {@code branchEngine}, so
     * binder-row drives and branch-owned drives run identical registered initializers — the factory
     * never builds a second, divergent scope. {@code null} is tolerated for callers that pass a
     * {@code null} propagator (no durable-context capture at all); this constructor then substitutes
     * {@link WorkflowContextBinder#noop()}, matching {@code branchEngine}'s own null-propagator
     * fallback.
     *
     * <p>The {@code payloadConstraintCheck} parameter is the opt-in constraint check for coerced
     * payloads; the factory builds exactly one and passes that same instance here and into the
     * task-lifecycle service built below.
     *
     * <p>The {@code typeNames} parameter is the type-name index a decision payload's declared type
     * name resolves through, and the contract class of a delivery by key names through; the factory passes the one
     * index the registry fills. The {@code signalWaits} parameter is the repository of the open wait records a
     * delivery by key matches. The
     * {@code stateCompatibility} parameter is the comparison a migration applies to differing state classes. The {@code executionProbe} parameter is the probe the handle exposes. The {@code commands} parameter is the
     * command repository whose execution lock every instance-changing entry point takes. The {@code reservations}
     * parameter reserves a task for a recorded completion; the {@code schemaProducer} parameter, when present, supplies
     * the payload schemas a new task's decision snapshot stores. The {@code failingMarks} parameter reads this node's
     * evaluation of a version of a scope other than the default one, which a migration into it is refused for.
     *
     * <p>Package-visible (not {@code private}) so the factory in the same package can invoke it; it
     * is not part of any production API.
     */
    WorkflowEngine(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowSignalWaitRepository<SqlClient> signalWaits,
            RecorderRouter recorders,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            FingerprintCanonicalizer fingerprintCanonicalizer,
            Clock clock,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            BranchTransitionEngine branchEngine,
            BranchCompensationOrchestrator branchCompensation,
            @Nullable DurableContextPropagator propagator,
            Optional<dev.vertique.workflow.migration.WorkflowMigrationRegistry> migrationRegistryOpt,
            WorkflowEventEmitter eventEmitter,
            WorkflowHistoryRecorder historyRecorder,
            ReminderScheduler reminderScheduler,
            CompensationService compensationService,
            @Nullable dev.vertique.context.InboundExecutionContextScope inboundExecScope,
            PayloadConstraintCheck payloadConstraintCheck,
            WorkflowTypeNames typeNames,
            Optional<dev.vertique.workflow.migration.WorkflowStateCompatibility> stateCompatibility,
            WorkflowExecutionProbe executionProbe,
            WorkflowCommandRepository<SqlClient> commands,
            Supplier<WorkflowExecutorControl> executorControl,
            WorkflowRecordingConfig recordingConfig,
            TaskReservations<SqlClient> reservations,
            Optional<WorkflowPlanSchemaProducer> schemaProducer,
            Function<DefinitionKey, Optional<DefinitionFailure>> failingMarks,
            @Nullable TransactionalDefinitionReads<SqlClient> definitionReads,
            CommandActors actors,
            WorkflowHistoryEventPublisher historyPublisher) {
        this.historyPublisher = historyPublisher;
        this.actors = actors;
        this.recorderRouter = recorders;
        this.recordValidator = new RecordTimePayloadValidator(recordingConfig);
        this.startPayloadValidation = new StartPayloadValidation(recordValidator);
        this.definitionReads = definitionReads;
        this.maxOpenSignals = recordingConfig.maxOpenSignalsPerInstance();
        this.executionProbe = executionProbe;
        this.commands = commands;
        this.executorControl = executorControl;
        this.payloadConstraintCheck = payloadConstraintCheck;
        this.txRunner = txRunner;
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.dedup = dedup;
        this.signalWaits = signalWaits;
        SignalWaits waitRecords = new SignalWaits(signalWaits, commands, historyRecorder, clock);
        this.waitRecords = waitRecords;
        this.typeNames = typeNames;
        this.timerStore = timerStore;
        this.taskStore = taskStore;
        this.clock = clock;
        this.branchTokens = branchTokens;
        this.branchEngine = branchEngine;
        this.eventEmitter = eventEmitter;
        this.historyRecorder = historyRecorder;
        this.decisions = historyRecorder.decisions();
        this.reminderScheduler = reminderScheduler;
        this.propagator = propagator;
        // Hand-build the binder from the (possibly-null) propagator. WorkflowContextBinder requires
        // a non-null propagator, so a null propagator here (callers that do not wire
        // durable-context capture at all — the same callers that already skip start-time capture)
        // substitutes the null-object WorkflowContextBinder.noop() instead, keeping this field
        // (and every downstream contextBinder field it feeds) non-nullable; noop() runs every
        // drive unbound, exactly as it did before this feature existed. Built early (before the
        // driver↔fork/join wiring below) so TaskLifecycleService and TimerLifecycleService can take
        // it as a constructor argument. inboundExecScope is the SAME scope instance the factory
        // threaded into branchEngine — not rebuilt here — so binder-row drives and branch-owned
        // drives run identical registered initializers (factory initializer parity, review finding).
        WorkflowContextBinder builtContextBinder = propagator == null
                ? WorkflowContextBinder.noop()
                : new WorkflowContextBinder(propagator, inboundExecScope);
        this.contextBinder = builtContextBinder;
        // Hand-wire the driver↔fork/join cycle. The coordinator needs a lazy
        // Provider<WorkflowTransitionDriver>; the driver needs the coordinator eagerly. Break the
        // chicken-and-egg with a 1-element holder the coordinator's provider reads. Provider.get()
        // is only invoked at runtime — the post-join continuation (driver) and cancelBranchOwnedWaits
        // (engine) — by which point construction has completed and the fields above are assigned.
        final WorkflowTransitionDriver[] driverHolder = new WorkflowTransitionDriver[1];
        final TaskLifecycleService[] taskLifecycleHolder = new TaskLifecycleService[1];
        final TimerLifecycleService[] timerLifecycleHolder = new TimerLifecycleService[1];
        this.forkJoin = new ForkJoinCoordinator(
                () -> driverHolder[0],
                () -> this,
                registry,
                instances,
                history,
                branchTokens,
                joinStates,
                branchEngine,
                branchCompensation,
                propagator,
                clock,
                decisions,
                waitRecords);
        this.driver = new WorkflowTransitionDriver(
                instances,
                history,
                recorders,
                commands,
                taskStore,
                eventEmitter,
                reminderScheduler,
                compensationService,
                this.forkJoin,
                clock,
                decisions,
                waitRecords,
                () -> timerLifecycleHolder[0],
                () -> taskLifecycleHolder[0]);
        driverHolder[0] = this.driver;
        // TaskLifecycleService needs the driver and coordinator eagerly; both are now built. The
        // driver reaches its task-creation helpers through the lazy Provider above
        // (taskLifecycleHolder), which is resolved here once the service exists.
        this.taskLifecycle = new TaskLifecycleService(
                registry,
                instances,
                history,
                dedup,
                taskStore,
                timerStore,
                branchTokens,
                fingerprintCanonicalizer,
                eventEmitter,
                reminderScheduler,
                this.forkJoin,
                this.driver,
                clock,
                () -> timerLifecycleHolder[0],
                builtContextBinder,
                payloadConstraintCheck,
                typeNames,
                commands,
                reservations,
                historyRecorder,
                Optional.of(recordingConfig),
                schemaProducer,
                actors);
        taskLifecycleHolder[0] = this.taskLifecycle;
        // TimerLifecycleService needs the driver, coordinator, and task service eagerly; all three
        // are now built. The driver reaches resolveFireAt through the lazy Provider above
        // (timerLifecycleHolder), which is resolved here once the service exists.
        this.timerLifecycle = new TimerLifecycleService(
                registry,
                instances,
                history,
                branchTokens,
                timerStore,
                this.forkJoin,
                this.driver,
                this.taskLifecycle,
                clock,
                builtContextBinder,
                commands,
                historyRecorder,
                waitRecords);
        timerLifecycleHolder[0] = this.timerLifecycle;
        // SignalHandler needs the driver, coordinator, and timer service eagerly; all are now built.
        // Nothing references the handler, so it is a plain leaf in the construction order.
        this.signalHandler = new SignalHandler(
                registry,
                instances,
                history,
                branchTokens,
                timerStore,
                branchEngine,
                this.forkJoin,
                this.driver,
                this.timerLifecycle,
                clock,
                commands,
                builtContextBinder,
                historyRecorder,
                recorderRouter,
                waitRecords);
        // MigrationExecutor needs the driver eagerly (built above) plus the optional migration
        // registry; nothing references it back, so it is a plain leaf in the construction order.
        this.migrationExecutor = new MigrationExecutor(
                migrationRegistryOpt,
                registry,
                instances,
                history,
                branchTokens,
                this.driver,
                clock,
                builtContextBinder,
                stateCompatibility,
                failingMarks,
                commands,
                definitionReads,
                actors,
                decisions,
                waitRecords,
                timerStore,
                this.timerLifecycle);
    }

    // --- WorkflowEngineHandle ---

    @Override
    public WorkflowExecutionProbe executionProbe() {
        return executionProbe;
    }

    @Override
    public WorkflowExecutorControl executor() {
        return executorControl.get();
    }

    // --- WorkflowOperations ---

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction, then delegates to
     * {@link #start(StartCommand, SqlClient)}.
     *
     * @param cmd the start command; must not be null
     * @return a {@link Future} resolving to the id of the created (or existing) instance
     */
    @Override
    public Future<WorkflowInstanceId> start(StartCommand cmd) {
        return txRunner.inTransaction(null, tx -> start(cmd, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction, then delegates to
     * {@link #signal(WorkflowInstanceId, String, Object, String, SqlClient)}.
     *
     * @param id the workflow instance id; must not be null
     * @param signalName the name of the signal to deliver; must not be null
     * @param payload the signal payload; coerced to the declared payload type
     * @param signalDedupKey caller-supplied dedup key; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @Override
    public Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey) {
        return txRunner.inTransaction(null, tx -> signal(id, signalName, payload, signalDedupKey, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction and delivers the signal like
     * {@link #signal(WorkflowInstanceId, String, Object, String)}, after comparing {@code contractName}
     * with the contract of the definition version the instance is pinned to. A different contract fails
     * the future with
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException}; the
     * transaction rolls back, so the instance, its history and the signal's dedup row are unchanged.
     *
     * @param id the workflow instance id; must not be null
     * @param contractName the contract name of the sending client; must not be null
     * @param signalName the name of the signal to deliver; must not be null
     * @param payload the signal payload; coerced to the declared payload type
     * @param signalDedupKey caller-supplied dedup key; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @Override
    public Future<Void> signal(
            WorkflowInstanceId id, String contractName, String signalName, Object payload, String signalDedupKey) {
        Objects.requireNonNull(contractName, "contractName");
        return txRunner.inTransaction(
                null, tx -> deliverSignal(id, contractName, signalName, payload, signalDedupKey, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction and transitions the instance to {@code CANCELLED},
     * appending a history entry with the given reason.
     *
     * @param id the workflow instance id; must not be null
     * @param reason human-readable reason for the cancellation; stored in history
     * @return a {@link Future} that completes when the cancellation has been persisted
     */
    @Override
    public Future<Void> cancel(WorkflowInstanceId id, String reason) {
        return txRunner.inTransaction(null, tx -> cancel(id, reason, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction and records a retry, as
     * {@link #retry(WorkflowInstanceId, SqlClient)} does.
     *
     * @param id the workflow instance id; must not be null
     * @return a {@link Future} that completes when the retry is recorded
     */
    @Override
    public Future<Void> retry(WorkflowInstanceId id) {
        return txRunner.inTransaction(null, tx -> retry(id, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads the instance snapshot and full history rows from the database in a single
     * {@code REPEATABLE READ} transaction so the result is a consistent point-in-time snapshot.
     * Concurrent transitions cannot interleave between the two reads. Reads the instance and its history
     * from the database without the plan of its pinned definition version, so a finished instance of a
     * retired version stays readable on a node that no longer holds the code.
     *
     * @param id the workflow instance id; must not be null
     * @return a {@link Future} resolving to a {@link WorkflowView} for the instance
     */
    @Override
    public Future<WorkflowView> query(WorkflowInstanceId id) {
        return readView(id, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads like {@link #query(WorkflowInstanceId)}, after comparing {@code contractName} with the
     * contract that the definition row of the version the instance is pinned to stores, read in the query's
     * transaction, so the check needs no plan and no code on any node. A pinned row that no longer exists fails the
     * future with {@link dev.vertique.workflow.exception.WorkflowVersionNotRunnableException}. A different contract
     * fails the future with
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException} before the
     * history is read.
     *
     * @param id the workflow instance id; must not be null
     * @param contractName the contract name of the querying client; must not be null
     * @return a {@link Future} resolving to a {@link WorkflowView} for the instance
     */
    @Override
    public Future<WorkflowView> query(WorkflowInstanceId id, String contractName) {
        Objects.requireNonNull(contractName, "contractName");
        return readView(id, contractName);
    }

    private Future<WorkflowView> readView(WorkflowInstanceId id, @Nullable String callerContractName) {
        return txRunner.inTransaction(
                IsolationLevel.REPEATABLE_READ, tx -> instances.findById(id, tx).compose(optInst -> {
                    if (optInst.isEmpty()) {
                        return Future.<WorkflowView>failedFuture(new WorkflowInstanceNotFoundException(id));
                    }
                    WorkflowInstance inst = optInst.get();
                    if (callerContractName != null) {
                        // The contract is compared with the one the pinned definition's row stores, read in this
                        // transaction, so no plan is resolved or built on any node.
                        DefinitionKey pin = new DefinitionKey(
                                inst.definitionScopeKey(), inst.definitionId(), inst.definitionVersion());
                        Future<Optional<String>> row = definitionReads != null
                                ? definitionReads.pinnedContract(pin, tx)
                                : instances.findDefinitionContract(pin, tx);
                        return row.compose(stored -> {
                            if (stored.isEmpty()) {
                                return Future.<WorkflowView>failedFuture(
                                        new WorkflowVersionNotRunnableException(pin, inst.id()));
                            }
                            WorkflowDefinitionContractMismatchException refusal =
                                    ContractMismatch.refusal(inst, pin, stored.get(), callerContractName, "query");
                            return refusal != null
                                    ? Future.<WorkflowView>failedFuture(refusal)
                                    : history.listByInstance(id, tx).map(entries -> new WorkflowView(inst, entries));
                        });
                    }
                    return history.listByInstance(id, tx).map(entries -> new WorkflowView(inst, entries));
                }));
    }

    // --- TransactionalWorkflowOperations ---

    /**
     * {@inheritDoc}
     *
     * <p>Looks for a start dedup row of the start scope, the definition id and the idempotency key first; a
     * replay returns the existing instance before any version is resolved. A first start resolves the
     * definition from one read of the database on the caller's transaction, compares the command's contract with the
     * resolved definition's contract, validates the start payload against the stored start schema, takes the new instance's execution lock, atomically claims the start dedup slot and, when it
     * wins, records the instance as {@code PENDING} with a {@code START} command and returns. The executor derives the
     * initial state and drives the first steps afterwards. A null start scope is the default scope.
     *
     * @param cmd the start command; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} resolving to the id of the created (or existing) instance
     */
    @Override
    public Future<WorkflowInstanceId> start(StartCommand cmd, SqlClient tx) {
        Future<WorkflowInstanceId> invalidClient = WorkflowTransactionClientGuard.reject("start", tx);
        if (invalidClient != null) {
            return invalidClient;
        }

        String startScope = cmd.startScope() == null ? WorkflowScopes.DEFAULT : cmd.startScope();
        // A replay, or a start that lost the dedup claim, answers with an instance whose history is not this
        // transaction's; only the winner's creation is announced.
        AtomicBoolean created = new AtomicBoolean();
        return historyPublisher.aroundCreating(
                WorkflowHistoryFailure.Action.START, tx, created::get, () -> dedup.findStart(
                                startScope, cmd.definitionId(), cmd.idempotencyKey(), tx)
                        .compose(existing -> existing.isPresent()
                                ? replayedStart(cmd, existing.get(), tx)
                                : firstStart(cmd, startScope, created, tx)));
    }

    /**
     * Answers a start whose dedup row already exists. A client of another contract than the one the existing
     * instance's definition implements fails as an idempotency conflict, because business keys and idempotency
     * keys are shared by the clients of one start scope and id. A start that names no version otherwise returns
     * the existing instance. A start that names a version, and possibly a target scope, must name the definition
     * the existing instance is pinned to; any other pin fails as an idempotency conflict. A replay never changes
     * a pin. When this node has no plan for the pin, the contract the durable registry stores for the pinned
     * definition stands in for it, read through the given transaction. With no stored row the start fails with
     * {@link WorkflowVersionNotRunnableException}.
     */
    private Future<WorkflowInstanceId> replayedStart(StartCommand cmd, WorkflowInstanceId existing, SqlClient tx) {
        return instances.findById(existing, tx).compose(found -> {
            if (found.isEmpty()) {
                return cmd.requestedDefinitionVersion() == null
                        ? Future.succeededFuture(existing)
                        : Future.failedFuture(new WorkflowInstanceNotFoundException(existing));
            }
            WorkflowInstance pinned = found.get();
            // The pinned version's own plan names the contract. On a node without that plan, the contract stored for
            // the pinned definition does, read through the start transaction so that no second connection is taken;
            // with no stored row the node cannot vouch for the caller's contract and refuses, like a
            // contract-checked query does.
            DefinitionKey pin =
                    new DefinitionKey(pinned.definitionScopeKey(), pinned.definitionId(), pinned.definitionVersion());
            Optional<String> local = registry.find(pin).map(RuntimeWorkflow::contractName);
            Future<Optional<String>> contractOfPin =
                    local.isPresent() ? Future.succeededFuture(local) : instances.findDefinitionContract(pin, tx);
            return contractOfPin.compose(pinnedContract -> checkReplay(cmd, existing, pinned, pin, pinnedContract));
        });
    }

    /**
     * Compares the contract and the pin of a replayed start with those of the existing instance, once the contract
     * of the pinned definition is known or found unknown.
     */
    private Future<WorkflowInstanceId> checkReplay(
            StartCommand cmd,
            WorkflowInstanceId existing,
            WorkflowInstance pinned,
            DefinitionKey pin,
            Optional<String> pinnedContract) {
        if (pinnedContract.isEmpty()) {
            return Future.failedFuture(new WorkflowVersionNotRunnableException(pin, existing));
        }
        if (!cmd.contractName().equals(pinnedContract.get())) {
            return Future.failedFuture(new WorkflowIdempotencyConflictException(
                    "start",
                    cmd.idempotencyKey(),
                    "contract=" + pinnedContract.get(),
                    "contract=" + EchoedNames.bounded(cmd.contractName())));
        }
        if (cmd.requestedDefinitionVersion() == null) {
            return Future.succeededFuture(existing);
        }
        long requestedVersion = cmd.requestedDefinitionVersion();
        String requestedScope = cmd.requestedTargetScope();
        boolean sameVersion = pinned.definitionVersion() == requestedVersion;
        boolean sameScope = requestedScope == null || requestedScope.equals(pinned.definitionScopeKey());
        if (sameVersion && sameScope) {
            return Future.succeededFuture(existing);
        }
        // The fingerprints keep the stored "definitionVersion=N" format; the scope is named only when it differs.
        return Future.failedFuture(new WorkflowIdempotencyConflictException(
                "start",
                cmd.idempotencyKey(),
                pinFingerprint(sameScope ? null : pinned.definitionScopeKey(), pinned.definitionVersion()),
                pinFingerprint(sameScope ? null : requestedScope, requestedVersion)));
    }

    private static String pinFingerprint(@Nullable String scopeKey, long version) {
        return scopeKey == null
                ? "definitionVersion=" + version
                : "definitionVersion=" + version + ",definitionScope=" + scopeKey;
    }

    /**
     * Records a start with no dedup row. The version, its contract and its status are read from the database on the
     * caller's transaction; the payload is checked against the stored schema; and only then the lock, the dedup claim,
     * pin admission and the insert follow.
     *
     * <p>A start through a store that cannot read on the caller's transaction fails: the node keeps no copy of the
     * definition table to select from.
     */
    private Future<WorkflowInstanceId> firstStart(
            StartCommand cmd, String startScope, AtomicBoolean created, SqlClient tx) {
        WorkflowInvalidPayloadException oversized = oversizedKey(cmd);
        if (oversized == null) {
            oversized = oversizedSubject(cmd.subjectRef());
        }
        if (oversized != null) {
            return Future.failedFuture(oversized);
        }
        if (definitionReads == null) {
            return Future.failedFuture(new IllegalStateException(
                    "A start selects its version from the definition rows on the caller's transaction, but the"
                            + " definition store of this engine cannot read on it"));
        }
        return definitionReads
                .selectionRead(startScope, cmd.definitionId(), tx)
                .compose(read -> firstStartFromRows(cmd, startScope, read, created, tx));
    }

    /** Runs the version the selection read chose; no plan is loaded and no payload class is read. */
    private Future<WorkflowInstanceId> firstStartFromRows(
            StartCommand cmd, String startScope, StartSelection read, AtomicBoolean created, SqlClient tx) {
        StartVersion chosen;
        try {
            chosen = StartVersionSelection.select(
                    read,
                    startScope,
                    cmd.definitionId(),
                    cmd.contractName(),
                    cmd.requestedDefinitionVersion(),
                    cmd.requestedTargetScope());
            // A deprecated version admits no new work, whether it was named or an activation record selected it;
            // refuse before any dedup claim or write. A failing mark refuses nothing: the executor fails the start.
            if (chosen.status() == DefinitionStatus.DEPRECATED) {
                return Future.failedFuture(deprecatedRefusal(chosen.key()));
            }
            if (!cmd.contractName().equals(chosen.contractName())) {
                return Future.failedFuture(startContractRefusal(cmd, chosen.contractName(), chosen.key()));
            }
            // The payload is checked against the stored schema, within the size and depth bounds, before any lock is
            // taken or row written. Deserializing and constraint checks are the executor's.
            startPayloadValidation.validate(cmd.payload(), chosen.startSchema(), chosen.key());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        DefinitionKey key = chosen.key();
        return claimAndCreate(
                cmd,
                startScope,
                new Selected(key.scopeKey(), key.definitionId(), key.definitionVersion(), chosen.planHash()),
                cmd.payload(),
                created,
                tx);
    }

    /** The definition version a start pins: where it lives, what it is called and the hash of its plan. */
    private record Selected(String scopeKey, String definitionId, long version, String planHash) {}

    /**
     * Takes the new instance's execution lock, claims the dedup slot and, when this start wins, records the instance.
     * The lock comes before the claim, the first write; nobody else knows the id yet, so the acquisition is
     * uncontended.
     */
    private Future<WorkflowInstanceId> claimAndCreate(
            StartCommand cmd,
            String startScope,
            Selected selected,
            Object payload,
            AtomicBoolean created,
            SqlClient tx) {
        String fingerprint = "definitionVersion=" + selected.version();

        UUID newId = UUID.randomUUID();
        WorkflowInstanceId proposedId = new WorkflowInstanceId(newId);

        return commands.lockInstance(proposedId, tx)
                .compose(locked -> dedup.claimOrResolveStart(
                        startScope, selected.definitionId(), cmd.idempotencyKey(), proposedId, fingerprint, tx))
                .compose((StartDedupResult result) -> {
                    if (!result.didInsert()) {
                        // Loser: a concurrent start claimed the slot first. Apply the replay rules to its instance.
                        return replayedStart(cmd, result.workflowId(), tx);
                    }
                    // Winner: proceed with instance creation.
                    created.set(true);
                    return doCreateInstance(cmd, startScope, selected, payload, proposedId, tx);
                });
    }

    /** The longest subject type, id or version a start accepts, in characters: the widths of the instance's columns. */
    private static final int MAX_SUBJECT_TYPE_LENGTH = 64;

    private static final int MAX_SUBJECT_ID_LENGTH = 255;
    private static final int MAX_SUBJECT_VERSION_LENGTH = 64;

    /** The longest idempotency key or business key a start accepts, in characters: the width of their columns. */
    private static final int MAX_KEY_LENGTH = 255;

    /** Names the key of the start that is too long, as a refusal, without repeating it; {@code null} when both fit. */
    private static @Nullable WorkflowInvalidPayloadException oversizedKey(StartCommand cmd) {
        String part = cmd.idempotencyKey().length() > MAX_KEY_LENGTH
                ? "idempotency key"
                : cmd.businessKey() != null && cmd.businessKey().length() > MAX_KEY_LENGTH ? "business key" : null;
        if (part == null) {
            return null;
        }
        return new WorkflowInvalidPayloadException(
                "Start is refused: the " + part + " exceeds " + MAX_KEY_LENGTH + " characters",
                List.of(ViolationDetail.unlocated("the " + part + " is too long", "payload")));
    }

    /** Names the first part of the subject that is too long, as a refusal; {@code null} when the subject is within bounds. */
    private static @Nullable WorkflowInvalidPayloadException oversizedSubject(@Nullable WorkflowSubjectRef subject) {
        if (subject == null) {
            return null;
        }
        String part = null;
        int limit = 0;
        if (subject.type().length() > MAX_SUBJECT_TYPE_LENGTH) {
            part = "type";
            limit = MAX_SUBJECT_TYPE_LENGTH;
        } else if (subject.id().length() > MAX_SUBJECT_ID_LENGTH) {
            part = "id";
            limit = MAX_SUBJECT_ID_LENGTH;
        } else if (subject.version() != null && subject.version().length() > MAX_SUBJECT_VERSION_LENGTH) {
            part = "version";
            limit = MAX_SUBJECT_VERSION_LENGTH;
        }
        if (part == null) {
            return null;
        }
        return new WorkflowInvalidPayloadException(
                "Start is refused: the subject " + part + " exceeds " + limit + " characters",
                List.of(ViolationDetail.unlocated("the subject " + part + " is too long", "payload")));
    }

    /** The refusal for a start through a client of another contract than the resolved definition's. */
    private static WorkflowDefinitionContractMismatchException startContractRefusal(
            StartCommand cmd, String implementedContract, DefinitionKey resolved) {
        return new WorkflowDefinitionContractMismatchException(
                resolved,
                "Definition '" + resolved.definitionId() + "' v" + resolved.definitionVersion()
                        + " implements contract '" + implementedContract + "'; a client of contract '"
                        + EchoedNames.bounded(cmd.contractName()) + "' cannot start it");
    }

    /**
     * Builds the typed refusal for admitting new work onto a deprecated definition version. Shared
     * by explicit-version starts and migration targets.
     *
     * @param key the scope, id and version of the deprecated definition
     * @return the lifecycle exception to fail the caller's future with
     */
    static WorkflowDefinitionLifecycleException deprecatedRefusal(DefinitionKey key) {
        return new WorkflowDefinitionLifecycleException(
                WorkflowDefinitionLifecycleException.Reason.DEPRECATED,
                key,
                "Definition '" + key.definitionId() + "' version " + key.definitionVersion()
                        + " is deprecated and does not accept new starts or migrations");
    }

    /**
     * Records a new instance: validates and coerces the start payload as before, inserts the instance as
     * {@code PENDING} with its pin, business key and durable carrier, and records the {@code START} command with the
     * payload. No initial state is computed, no subject is resolved and nothing is driven here: the executor does that
     * when it runs the command. Called only by the dedup winner in {@link #start(StartCommand, SqlClient)}, under the
     * new instance's execution lock.
     *
     * @param cmd the start command
     * @param startScope the resolved start scope stamped on the instance
     * @param selected the definition version the instance is pinned to
     * @param coercedPayload the start payload, validated against the version's stored schema or bound to its declared
     *     start type
     * @param id the instance id to use
     * @param tx the active transaction
     * @return a {@link Future} resolving to the new instance id once the instance and its command are recorded
     */
    private Future<WorkflowInstanceId> doCreateInstance(
            StartCommand cmd,
            String startScope,
            Selected selected,
            Object coercedPayload,
            WorkflowInstanceId id,
            SqlClient tx) {
        String payloadJson;
        try {
            payloadJson = coercedPayload == null ? null : Json.encode(coercedPayload);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        String targetJson = cmd.subjectRef() == null ? null : Json.encode(SubjectTarget.of(cmd.subjectRef()));

        Instant now = clock.instant();

        // The instance carries the durable context of the recording caller, because the executor has no caller to
        // capture it from later. The capture strips the identity snapshot, so no later drive can bind an identity from
        // the instance carrier. An empty capture persists as NULL; a null propagator captures nothing.
        DurableMetadata captured = propagator == null
                ? DurableMetadata.empty()
                : propagator.capture(DispatchBoundary.WORKFLOW).without(IDENTITY_SNAPSHOT_NAMESPACE);
        DurableMetadata instanceMetadata = captured.isEmpty() ? null : captured;

        WorkflowInstance pending = new WorkflowInstance(
                id,
                selected.definitionId(),
                selected.version(),
                selected.planHash(),
                0L,
                WorkflowStatus.PENDING,
                cmd.businessKey(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                instanceMetadata,
                startScope,
                selected.scopeKey());

        return actors.recording("START", actor -> instances
                        .insert(pending, tx)
                        .compose(v ->
                                commands.recordStart(id, cmd.idempotencyKey(), payloadJson, targetJson, actor, tx)))
                .map(v -> id);
    }

    /** The durable-context namespace of the identity snapshot, which no instance carrier keeps. */
    static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    /** The fork step and branch a branch-addressed retry names, as recorded on its command. */
    record BranchRetryTarget(String forkStepId, String branchId) {}

    /** The caller-supplied subject of a start, as recorded on its command. */
    record SubjectTarget(WorkflowSubjectRef subject) {
        static SubjectTarget of(WorkflowSubjectRef subject) {
            return new SubjectTarget(subject);
        }
    }

    // --- Executor segments ---

    /**
     * The engine's side of a segment, for the executor.
     *
     * @return the segments seam over this engine
     */
    WorkflowSegments segments() {
        return new WorkflowSegments() {
            @Override
            public WorkflowRegistry registry() {
                return registry;
            }

            @Override
            public Future<Outcome> run(CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
                return runSegment(head, claim, command, tx, Integer.MAX_VALUE);
            }

            @Override
            public Future<Outcome> run(
                    CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx, int maxSteps) {
                return runSegment(head, claim, command, tx, maxSteps);
            }

            @Override
            public Future<Void> instanceFailed(
                    WorkflowInstanceId instance,
                    long historySequence,
                    String reasonCode,
                    String message,
                    SqlClient tx) {
                return emitInstanceFailed(instance, historySequence, reasonCode, message, tx);
            }

            @Override
            public <T> Future<T> announcing(WorkflowInstanceId instance, SqlClient tx, Supplier<Future<T>> work) {
                return historyPublisher.aroundLocked(null, instance, tx, work);
            }
        };
    }

    /**
     * Emits {@code WORKFLOW_FAILED} for an instance that the executor failed on behalf of a command, in the same
     * transaction and the way a fail node does. The event's step is the plan's initial step when the pin still
     * resolves, and {@code start} otherwise: an instance that failed before its first step has no current step.
     */
    private Future<Void> emitInstanceFailed(
            WorkflowInstanceId id, long historySequence, String reasonCode, String message, SqlClient tx) {
        return instances.findById(id, tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.succeededFuture();
            }
            WorkflowInstance inst = found.get();
            String stepId = inst.currentStepId();
            if (stepId == null) {
                try {
                    stepId = PinnedPlans.resolve(registry, inst).plan().initialStepId();
                } catch (RuntimeException e) {
                    stepId = "start";
                }
            }
            return eventEmitter.emitEvent(
                    inst,
                    WorkflowEventType.WORKFLOW_FAILED,
                    historySequence,
                    null,
                    stepId,
                    WorkflowEventEmitter.attrs("errorType", reasonCode, "errorMessage", message),
                    tx);
        });
    }

    /**
     * Runs a fenced command's work in the segment's transaction: a {@code START}, a recorded {@code SIGNAL}, a
     * recorded {@code COMPLETION} or a {@code TIMER}.
     */
    private Future<WorkflowSegments.Outcome> runSegment(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx, int maxSteps) {
        // Every history entry the segment writes is logged with the actor that recorded the command it is about, and
        // with the actor of the executed command when it names none. The executor never reads the ambient identity.
        CommandActor recorded = command.actor() == null ? CommandActor.none() : command.actor();
        DefinitionKey pinned =
                new DefinitionKey(head.definitionScopeKey(), head.definitionId(), head.definitionVersion());
        return decisions.segment(
                tx,
                command,
                pinned,
                () -> recorderRouter.executing(command, tx, () -> history.nextSequence(command.workflowId(), tx)
                        .compose(from -> legacyBaseline(command, tx)
                                .compose(logged -> runCommand(head, claim, command, tx, maxSteps))
                                .compose(outcome -> outcome == WorkflowSegments.Outcome.APPLIED
                                        // A command the segment applied may have ended the instance: its dead-lettered
                                        // commands are settled
                                        ? commands.settleEnded(command.workflowId(), tx)
                                                .map(settled -> outcome)
                                        : Future.succeededFuture(outcome))
                                .compose(outcome -> commands.attributeSegmentHistory(
                                                command.workflowId(), from, recorded, command, pinned, tx)
                                        .map(attributed -> outcome)))));
    }

    /**
     * Logs the full state of an instance that existed before the decision log when its first segment claims it: the
     * mark is consumed in the segment's transaction, so the baseline is written once, before the segment's decisions.
     */
    private Future<Void> legacyBaseline(SegmentCommand command, SqlClient tx) {
        if ("START".equals(command.kind())) {
            // an instance that has not started has no state to log: its first segment writes the baseline itself
            return Future.succeededFuture();
        }
        return commands.consumeLegacyBaseline(command.workflowId(), tx).compose(owed -> {
            if (!owed) {
                return Future.<Void>succeededFuture();
            }
            return instances
                    .findById(command.workflowId(), tx)
                    .compose(found -> found.isPresent() && found.get().stateJson() != null
                            ? decisions.baseline(found.get(), tx)
                            : Future.<Void>succeededFuture());
        });
    }

    private Future<WorkflowSegments.Outcome> runCommand(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx, int maxSteps) {
        if ("RETRY".equals(command.kind())) {
            return driver.withStepCap(tx, maxSteps, () -> signalHandler
                    .parkedSignals()
                    .managing(tx, () -> runRetry(head, claim, command, tx)));
        }
        if ("MIGRATION".equals(command.kind())) {
            return driver.withStepCap(tx, maxSteps, () -> signalHandler
                    .parkedSignals()
                    .managing(tx, () -> runMigration(head, claim, command, tx)));
        }
        if ("SIGNAL".equals(command.kind())) {
            return driver.withStepCap(tx, maxSteps, () -> signalHandler
                    .parkedSignals()
                    .managing(tx, () -> signalHandler.runSegment(head, claim, command, tx)));
        }
        if ("COMPLETION".equals(command.kind())) {
            return driver.withStepCap(
                    tx, maxSteps, () -> signalHandler.parkedSignals().managing(tx, () -> taskLifecycle
                            .runCompletion(head, claim, command, tx)
                            .compose(outcome -> outcome == WorkflowSegments.Outcome.APPLIED
                                    ? signalHandler
                                            .parkedSignals()
                                            .drain(command.workflowId(), tx)
                                            .map(done -> outcome)
                                    : Future.succeededFuture(outcome))));
        }
        if ("TIMER".equals(command.kind())) {
            return driver.withStepCap(
                    tx, maxSteps, () -> signalHandler.parkedSignals().managing(tx, () -> timerLifecycle
                            .runTimer(head, claim, command, tx)
                            .compose(outcome -> outcome == WorkflowSegments.Outcome.APPLIED
                                    ? signalHandler
                                            .parkedSignals()
                                            .drain(command.workflowId(), tx)
                                            .map(done -> outcome)
                                    : Future.succeededFuture(outcome))));
        }
        if (!"START".equals(command.kind())) {
            return Future.failedFuture(
                    new IllegalStateException("The executor cannot run a command of kind " + command.kind()));
        }
        return instances.findById(command.workflowId(), tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.failedFuture(new WorkflowInstanceNotFoundException(command.workflowId()));
            }
            WorkflowInstance inst = found.get();
            if (inst.status() == WorkflowStatus.CANCELLED) {
                return settleMet(claim, command, metInstance(command, inst).orElseThrow(), tx);
            }
            if (inst.status() != WorkflowStatus.PENDING) {
                // The instance no longer waits for its start: it was already initialized.
                return Future.succeededFuture(WorkflowSegments.Outcome.STALE);
            }
            if (!inst.definitionScopeKey().equals(head.definitionScopeKey())
                    || !inst.definitionId().equals(head.definitionId())
                    || inst.definitionVersion() != head.definitionVersion()
                    || !Objects.equals(inst.planHash(), head.planHash())) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            }
            RuntimeWorkflow rw;
            try {
                rw = PinnedPlans.resolve(registry, inst);
                requirePlanHashMatches(inst, rw);
            } catch (WorkflowVersionNotRunnableException | WorkflowPlanHashDriftException e) {
                // This node cannot run the pinned plan right now (it left the registry after the peek, or differs
                // from the recorded one). Another node, or a later pass, may run it: release rather than fail.
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            DurableMetadata recorded;
            try {
                recorded = WorkflowContextBinder.decodeCarrier(command.contextJson());
            } catch (RuntimeException e) {
                return Future.failedFuture(
                        new IllegalStateException("The recorded context of command " + command.commandId()
                                + " of workflow instance " + inst.id().value() + " cannot be read"));
            }
            return driver.withStepCap(
                            tx, maxSteps, () -> signalHandler.parkedSignals().managing(tx, () -> contextBinder
                                    .withRecordedBase(
                                            inst,
                                            command.commandId(),
                                            null,
                                            recorded,
                                            () -> startSegment(inst, rw, claim, command, tx))
                                    .compose(done ->
                                            signalHandler.parkedSignals().drain(inst.id(), tx))))
                    .map(v -> WorkflowSegments.Outcome.APPLIED);
        });
    }

    /**
     * The first segment of an instance: logs that the command was received, computes the initial state from the
     * recorded payload, resolves the subject, moves the instance out of {@code PENDING}, appends the {@code START}
     * history entry and its event, advances the instance until it waits or ends, and logs that the command
     * completed.
     */
    private Future<Void> startSegment(
            WorkflowInstance inst, RuntimeWorkflow rw, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        return initialize(inst, rw, claim, command, true, tx);
    }

    /**
     * Initializes an instance from the payload and subject recorded on a {@code START} command and drives it to its
     * first wait or its end. A {@code START} segment runs it for its own command; a {@code RETRY} segment runs it for
     * the start that failed before initialization and logs its own command.
     *
     * @param ownCommand whether the claimed command is the {@code START} itself, so that this method logs its receipt
     *     and its completion
     */
    private Future<Void> initialize(
            WorkflowInstance inst,
            RuntimeWorkflow rw,
            ClaimedCommand claim,
            SegmentCommand command,
            boolean ownCommand,
            SqlClient tx) {
        Object coercedPayload;
        Object initialState;
        WorkflowSubjectRef subjectRef;
        try {
            Object raw = command.payloadJson() == null ? null : Json.decodeValue(command.payloadJson());
            coercedPayload = WorkflowPayloads.validateAndCoerceStartPayload(raw, rw.startPayloadType());
            payloadConstraintCheck.check(coercedPayload, WorkflowPayloads.START_REJECTION);
            try {
                initialState = rw.initialState().apply(coercedPayload);
            } catch (Exception e) {
                return Future.failedFuture(new WorkflowDefinitionException(
                        "initialState function threw for definition '" + EchoedNames.bounded(inst.definitionId()) + "'",
                        e));
            }
            subjectRef = resolveSubjectRef(recordedSubject(command), rw.plan(), initialState, rw.callbacks());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        String stateJson = Json.encode(initialState);

        WorkflowInstance running = new WorkflowInstance(
                inst.id(),
                inst.definitionId(),
                inst.definitionVersion(),
                inst.planHash(),
                inst.version() + 1,
                WorkflowStatus.RUNNING,
                inst.businessKey(),
                subjectRef,
                rw.plan().initialStepId(),
                null,
                null,
                null,
                stateJson,
                null,
                null,
                inst.createdAt(),
                clock.instant(),
                inst.metadata(),
                inst.scopeKey(),
                inst.definitionScopeKey());

        Future<Void> received =
                ownCommand ? historyRecorder.appendCommandReceived(inst, command, tx) : Future.succeededFuture();
        return received.compose(v -> instances.updateOptimistic(running, inst.version(), tx))
                .compose(rows ->
                        requireRowUpdated(rows, "workflow instance " + inst.id().value() + " start"))
                .compose(v -> historyRecorder.appendStartHistory(running, command.dedupKey(), tx))
                .compose(v -> decisions.baseline(running, tx))
                .compose(v -> driver.driveTransitions(running, rw, tx))
                .compose(v -> ownCommand
                        ? historyRecorder.appendCommandHistory(
                                inst.id(), WorkflowEntryType.COMMAND_COMPLETED, claim.commandId(), command.kind(), tx)
                        : Future.<Void>succeededFuture());
    }

    /**
     * Runs a claimed {@code RETRY} command, the one recovery operation, in the segment's transaction:
     *
     * <ul>
     *   <li>an instance that has ended rejects it;</li>
     *   <li>a retry that names a fork step and branch re-drives that branch once, and is stale when the branch is no
     *       longer running or its join has decided;</li>
     *   <li>an instance that is not {@code FAILED} and holds no dead-lettered command has nothing to recover: the command
     *       is stale;</li>
     *   <li>a {@code FAILED} instance without state is initialized from the stored start payload, and a dead-lettered
     *       start is superseded; a {@code FAILED} instance with state is driven again from its current step;</li>
     *   <li>after a drive, the parked signals the wait it reached accepts are applied, while a dead-lettered timeout
     *       still holds its deadline;</li>
     *   <li>then, unless the instance has ended, every dead-lettered command is offered again with its attempts reset.</li>
     * </ul>
     *
     * A failure of the drive rolls the segment back and the executor fails the command and the instance.
     */
    private Future<WorkflowSegments.Outcome> runRetry(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        return instances.findById(command.workflowId(), tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.<WorkflowSegments.Outcome>failedFuture(
                        new WorkflowInstanceNotFoundException(command.workflowId()));
            }
            WorkflowInstance inst = found.get();
            Optional<EndedInstanceOutcomes.Outcome> met = metInstance(command, inst);
            if (met.isPresent() && met.get().settlement() != EndedInstanceOutcomes.Settlement.EXECUTES) {
                return settleMet(claim, command, met.get(), tx);
            }
            if (!inst.definitionScopeKey().equals(head.definitionScopeKey())
                    || !inst.definitionId().equals(head.definitionId())
                    || inst.definitionVersion() != head.definitionVersion()
                    || !Objects.equals(inst.planHash(), head.planHash())) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            }
            RuntimeWorkflow rw;
            DurableMetadata recorded;
            try {
                rw = PinnedPlans.resolve(registry, inst);
                requirePlanHashMatches(inst, rw);
                recorded = WorkflowContextBinder.decodeCarrier(command.contextJson());
            } catch (WorkflowVersionNotRunnableException | WorkflowPlanHashDriftException e) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
            Future<Void> received = historyRecorder.appendCommandHistory(
                    inst.id(), WorkflowEntryType.COMMAND_RECEIVED, command.commandId(), command.kind(), tx);
            if (command.targetJson() != null) {
                BranchRetryTarget target = Json.decodeValue(command.targetJson(), BranchRetryTarget.class);
                return received.compose(v -> contextBinder.withRecordedBase(
                        inst,
                        command.commandId(),
                        null,
                        recorded,
                        () -> redriveBranch(inst, rw, target, claim, command, tx)));
            }
            return received.compose(v -> commands.hasDeadLetters(inst.id(), tx))
                    .compose(dead -> inst.status() != WorkflowStatus.FAILED && !dead
                            ? staleCommand(claim, command, CommandErrors.nothingToRecover(), tx)
                            : contextBinder.withRecordedBase(
                                    inst,
                                    command.commandId(),
                                    null,
                                    recorded,
                                    () -> recover(inst, rw, claim, command, tx)));
        });
    }

    /**
     * Drives a branch once for a branch-addressed {@code RETRY}: the branch the command names runs from its current
     * step. The command is stale when that branch is no longer running or its join has already decided.
     */
    private Future<WorkflowSegments.Outcome> redriveBranch(
            WorkflowInstance inst,
            RuntimeWorkflow rw,
            BranchRetryTarget target,
            ClaimedCommand claim,
            SegmentCommand command,
            SqlClient tx) {
        return branchTokens
                .findByWorkflowAndFork(inst.id(), target.forkStepId(), tx)
                .compose(tokens -> {
                    Optional<BranchToken> named = tokens.stream()
                            .filter(t -> t.branchId().equals(target.branchId()))
                            .findFirst();
                    return named.isPresent()
                            ? branchTokens.findByIdForUpdate(named.get().id(), tx)
                            : Future.succeededFuture(Optional.<BranchToken>empty());
                })
                .compose(locked -> locked.isPresent() && locked.get().status() == BranchStatus.RUNNING
                        ? forkJoin.isJoinOpen(inst.id(), target.forkStepId(), tx)
                                .map(open -> open ? locked : Optional.<BranchToken>empty())
                        : Future.succeededFuture(Optional.<BranchToken>empty()))
                .compose(live -> live.isEmpty()
                        ? staleCommand(claim, command, CommandErrors.retryBranchEnded(), tx)
                        : forkJoin.driveAndMaybeJoin(inst, live.get(), rw, command.commandId(), tx)
                                .compose(done -> historyRecorder.appendCommandHistory(
                                        inst.id(),
                                        WorkflowEntryType.COMMAND_COMPLETED,
                                        command.commandId(),
                                        command.kind(),
                                        tx))
                                .map(done -> WorkflowSegments.Outcome.APPLIED));
    }

    private Future<WorkflowSegments.Outcome> recover(
            WorkflowInstance inst, RuntimeWorkflow rw, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        Future<Boolean> drove;
        if (inst.status() != WorkflowStatus.FAILED) {
            drove = Future.succeededFuture(false);
        } else if (inst.stateJson() == null) {
            // The start failed before the instance had a state: initialize it from the stored start payload. A start
            // that was dead-lettered is superseded, so that its payload runs once.
            drove = commands.readStart(inst.id(), tx).compose(start -> {
                if (start.isEmpty()) {
                    return Future.<Boolean>failedFuture(new IllegalStateException(
                            "Workflow instance " + inst.id().value() + " has no start command to initialize from"));
                }
                return commands.supersedeDeadStart(inst.id(), tx)
                        .compose(superseded -> initialize(inst, rw, claim, start.get(), false, tx))
                        .map(done -> true);
            });
        } else if (inst.waitType() != null) {
            // The instance failed at a wait it never left: it goes back to that wait, whose task, timers and branches
            // are all still there. The wait's handler does not run again, so nothing is recorded twice.
            drove = resumeWait(inst, tx).map(done -> true);
        } else {
            drove = redrive(inst, rw, tx).map(done -> true);
        }
        return drove.compose(drive ->
                        drive ? signalHandler.parkedSignals().drain(inst.id(), tx) : Future.<Void>succeededFuture())
                .compose(done -> instances.findById(inst.id(), tx))
                .compose(after -> after.isPresent()
                                && !EndedInstanceOutcomes.isEnded(after.get().status())
                        ? commands.reofferDeadLetters(inst.id(), tx).<Void>mapEmpty()
                        : Future.<Void>succeededFuture())
                .compose(done -> historyRecorder.appendCommandHistory(
                        inst.id(), WorkflowEntryType.COMMAND_COMPLETED, command.commandId(), command.kind(), tx))
                .map(done -> WorkflowSegments.Outcome.APPLIED);
    }

    /** Resets a {@code FAILED} instance that has state to {@code RUNNING} and drives it again from its current step. */
    private Future<Void> redrive(WorkflowInstance inst, RuntimeWorkflow rw, SqlClient tx) {
        long prevVersion = inst.version();
        WorkflowInstance retried = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.RUNNING)
                .withWait(null, null)
                .withError(null, null)
                .withUpdatedAt(clock.instant());
        return reopen(inst, retried, tx).compose(v -> driver.driveTransitions(retried, rw, tx));
    }

    /**
     * Returns a {@code FAILED} instance that failed at a wait to {@code WAITING} at that wait. The wait columns the
     * failure kept stand as they were, the wait's task and branches are untouched and the wait's handler does not run;
     * the timers whose command failed (or was settled because the instance had failed) are offered again, each with one
     * new command that keeps the original fire time, so a deadline stands and a timer that was due fires at once.
     */
    private Future<Void> resumeWait(WorkflowInstance inst, SqlClient tx) {
        long prevVersion = inst.version();
        WorkflowInstance resumed = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.WAITING)
                .withError(null, null)
                .withUpdatedAt(clock.instant());
        return reopen(inst, resumed, tx)
                .compose(v -> commands.rearmTimers(inst.id(), tx))
                .mapEmpty();
    }

    /** Writes the instance's return from {@code FAILED} and the {@code RETRIED} entry. */
    private Future<Void> reopen(WorkflowInstance failed, WorkflowInstance reopened, SqlClient tx) {
        return instances
                .updateOptimistic(reopened, failed.version(), tx)
                .compose(rowCount ->
                        requireRowUpdated(rowCount, "instance '" + failed.id().value() + "' during retry"))
                .compose(v -> decisions.record(
                        failed,
                        reopened,
                        failed.currentStepId() == null ? "start" : failed.currentStepId(),
                        "retried",
                        tx))
                .compose(v -> history.nextSequence(failed.id(), tx))
                .compose(seq -> {
                    String histPayload =
                            Json.encode(new RetriedHistoryPayload("FAILED", WorkflowPayloads.commandCorrelationId()));
                    return history.append(
                            new WorkflowHistoryEntry(
                                    failed.id(), seq, WorkflowEntryType.RETRIED, histPayload, clock.instant()),
                            tx);
                });
    }

    /**
     * Runs a claimed {@code MIGRATION} command in the segment's transaction. A failed instance rejects it. Otherwise
     * the migration runs under a savepoint: when it is applied, the parked signals the target version does not declare
     * are rejected and the rest are applied at the wait the instance reached; when it is not valid it is rejected
     * with a reason that states neither a value nor a failure's text, and the instance is unchanged.
     */
    private Future<WorkflowSegments.Outcome> runMigration(
            CommandHead head, ClaimedCommand claim, SegmentCommand command, SqlClient tx) {
        return instances.findById(command.workflowId(), tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.<WorkflowSegments.Outcome>failedFuture(
                        new WorkflowInstanceNotFoundException(command.workflowId()));
            }
            WorkflowInstance inst = found.get();
            Optional<EndedInstanceOutcomes.Outcome> met = metInstance(command, inst);
            if (met.isPresent()) {
                return settleMet(claim, command, met.get(), tx);
            }
            Optional<MigrationExecutor.Prepared> prepared;
            try {
                prepared = migrationExecutor.prepare(head, command, inst);
            } catch (RuntimeException e) {
                return Future.failedFuture(e);
            }
            if (prepared.isEmpty()) {
                return Future.succeededFuture(WorkflowSegments.Outcome.PIN_MISMATCH);
            }
            return historyRecorder
                    .appendCommandReceived(inst, command, tx)
                    .compose(v -> migrationExecutor.execute(prepared.get(), tx))
                    .compose(result -> switch (result) {
                        case MigrationExecutor.Result.Applied applied ->
                            signalHandler
                                    .rejectUndeclaredParked(inst.id(), applied.target(), tx)
                                    .compose(done ->
                                            signalHandler.parkedSignals().drain(inst.id(), tx))
                                    .compose(done -> historyRecorder.appendCommandHistory(
                                            inst.id(),
                                            WorkflowEntryType.COMMAND_COMPLETED,
                                            command.commandId(),
                                            command.kind(),
                                            tx))
                                    .map(done -> WorkflowSegments.Outcome.APPLIED);
                        case MigrationExecutor.Result.Rejected rejected ->
                            rejectCommand(claim, command, rejected.reason(), tx);
                    });
        });
    }

    /** What the outcome table makes of the command that meets the instance, empty when the instance is live. */
    private static Optional<EndedInstanceOutcomes.Outcome> metInstance(SegmentCommand command, WorkflowInstance inst) {
        return EndedInstanceOutcomes.of(command.kind(), inst.status().name(), EndedInstanceOutcomes.Target.none());
    }

    /** Settles the claimed command of a retry, a migration or a start as the outcome table states. */
    private Future<WorkflowSegments.Outcome> settleMet(
            ClaimedCommand claim, SegmentCommand command, EndedInstanceOutcomes.Outcome outcome, SqlClient tx) {
        return outcome.settlement() == EndedInstanceOutcomes.Settlement.CANCELLED
                ? cancelCommand(claim, command, outcome.reason(), tx)
                : rejectCommand(claim, command, outcome.reason(), tx);
    }

    /** Settles the claimed command as cancelled with a reason, and logs it, in the segment's transaction. */
    private Future<WorkflowSegments.Outcome> cancelCommand(
            ClaimedCommand claim, SegmentCommand command, String reason, SqlClient tx) {
        return commands.cancel(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendCommandOutcomeHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_CANCELLED,
                        command.commandId(),
                        command.kind(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_CANCELLED);
    }

    /** Settles the claimed command as rejected with a reason, and logs it, in the segment's transaction. */
    private Future<WorkflowSegments.Outcome> rejectCommand(
            ClaimedCommand claim, SegmentCommand command, String reason, SqlClient tx) {
        return commands.reject(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendCommandOutcomeHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_REJECTED,
                        command.commandId(),
                        command.kind(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_REJECTED);
    }

    /** Settles the claimed command as stale with a reason, and logs it, in the segment's transaction. */
    private Future<WorkflowSegments.Outcome> staleCommand(
            ClaimedCommand claim, SegmentCommand command, String reason, SqlClient tx) {
        return commands.stale(command.commandId(), claim.token(), reason, tx)
                .compose(ClaimLostException::requireRow)
                .compose(done -> historyRecorder.appendCommandOutcomeHistory(
                        command.workflowId(),
                        WorkflowEntryType.COMMAND_STALE,
                        command.commandId(),
                        command.kind(),
                        reason,
                        tx))
                .map(done -> WorkflowSegments.Outcome.SETTLED_STALE);
    }

    /** The caller-supplied subject recorded on a start command, or {@code null}. */
    private static WorkflowSubjectRef recordedSubject(SegmentCommand command) {
        if (command.targetJson() == null) {
            return null;
        }
        return Json.decodeValue(command.targetJson(), SubjectTarget.class).subject();
    }

    /**
     * Resolves the subject ref for a new workflow instance using the ADR-0056 precedence rule:
     *
     * <ol>
     *   <li>If {@code cmd.subjectRef()} is non-null, use it directly — the resolver is NOT invoked
     *       even if one is configured on the plan.</li>
     *   <li>Else if {@code plan.subjectResolverCallbackId()} is non-null, invoke the registered
     *       resolver against the initial state and use the returned ref. If the resolver returns
     *       {@code null}, fail with {@link WorkflowDefinitionException} (resolver contract
     *       violation).</li>
     *   <li>Else return {@code null} — no subject ref is associated with this instance.</li>
     * </ol>
     *
     * <p>The resolver is invoked before the {@link WorkflowInstance} row is inserted, inside the
     * start transaction, because it is a pure function of the initial state.
     *
     * @param callerRef    the caller-supplied subject ref recorded with the start (precedence 1), or {@code null}
     * @param plan         the compiled workflow plan; provides the resolver callback id (precedence 2)
     * @param initialState the initial state derived from the start payload; passed to the resolver
     * @param callbacks    the callback registry used to look up the resolver function
     * @return the resolved {@link WorkflowSubjectRef}, or {@code null} if none applies
     * @throws WorkflowDefinitionException if the resolver is configured but returns {@code null}
     */
    private WorkflowSubjectRef resolveSubjectRef(
            @Nullable WorkflowSubjectRef callerRef,
            WorkflowPlan plan,
            Object initialState,
            WorkflowCallbackRegistry callbacks) {
        // Precedence 1: caller-supplied subject ref wins unconditionally.
        if (callerRef != null) {
            return callerRef;
        }

        // Precedence 2: plan-configured subject resolver.
        CallbackId resolverId = plan.subjectResolverCallbackId();
        if (resolverId == null) {
            return null;
        }

        WorkflowSubjectRef resolved;
        try {
            @SuppressWarnings("unchecked")
            var resolver = (java.util.function.Function<Object, WorkflowSubjectRef>)
                    (java.util.function.Function<?, ?>) callbacks.subjectResolver(resolverId);
            resolved = resolver.apply(initialState);
        } catch (Exception e) {
            throw new WorkflowDefinitionException(
                    "subjectResolver threw for definition '" + plan.definitionId() + "': " + e.getMessage(), e);
        }
        if (resolved == null) {
            throw new WorkflowDefinitionException(
                    "Subject resolver returned null for workflow definition '" + plan.definitionId() + "'");
        }
        return resolved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the signal as a {@code SIGNAL} command in the caller's transaction and returns once it is recorded; an
     * executor applies it afterwards, in its own segment, in queue order. The call is refused synchronously only for a
     * missing or ended instance, a name the instance's pinned definition row does not declare, a payload over the
     * record-time size or depth bounds, or an instance that already holds the configured number of open signals. A
     * committed duplicate returns success before any of that.
     *
     * @param id the workflow instance id; must not be null
     * @param signalName the name of the signal to deliver; must not be null
     * @param payload the signal payload; stored as recorded and bound to the declared type when it is applied
     * @param signalDedupKey caller-supplied dedup key; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the signal is recorded
     */
    @Override
    public Future<Void> signal(
            WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey, SqlClient tx) {
        return deliverSignal(id, null, signalName, payload, signalDedupKey, tx);
    }

    /**
     * Records an instance-level signal inside {@code tx}. A non-null {@code callerContractName} makes the recording
     * refuse an instance pinned to a definition of another contract.
     */
    private Future<Void> deliverSignal(
            WorkflowInstanceId id,
            @Nullable String callerContractName,
            String signalName,
            Object payload,
            String signalDedupKey,
            SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("signal", tx);
        if (invalidClient != null) {
            return invalidClient;
        }
        return recordSignal(id, callerContractName, signalName, payload, signalDedupKey, null, null, null, tx);
    }

    /**
     * Branch-aware signal overload (PRD-WF-002 §D11). When both {@code forkStepId} and
     * {@code branchId} are non-null, the signal is recorded for the matching branch; when both are null
     * this is the instance-level signal path; mixed-null is rejected.
     */
    @Override
    public Future<Void> signal(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            @jakarta.annotation.Nullable String forkStepId,
            @jakarta.annotation.Nullable String branchId,
            SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("signal", tx);
        if (invalidClient != null) {
            return invalidClient;
        }

        if (forkStepId == null && branchId == null) {
            return signal(id, signalName, payload, signalDedupKey, tx);
        }
        if (forkStepId == null || branchId == null) {
            return Future.failedFuture(
                    new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                            + EchoedNames.bounded(forkStepId) + ", branchId=" + EchoedNames.bounded(branchId)));
        }
        if (branchTokens == null || branchEngine == null) {
            return Future.failedFuture(new UnsupportedOperationException(
                    "branch-aware signal requires branch collaborators; engine constructed without them"));
        }
        return recordSignal(id, null, signalName, payload, signalDedupKey, forkStepId, branchId, null, tx);
    }

    /**
     * Explicit-carrier signal overload (PRD-WF-007, Contract Appendix C4). Overrides the interface
     * default so a non-null {@code signalMetadata} is honored instead of failing fast.
     *
     * <p>A non-null {@code signalMetadata} requires durable-context propagation to be wired (C4
     * no-silent-drop principle): when {@code propagator} is {@code null} this overload fails fast with an
     * {@link UnsupportedOperationException} instead of silently accepting and then discarding the caller's carrier.
     * This check runs first, before the duplicate lookup or sanitization.
     *
     * <p>A non-null {@code signalMetadata} is otherwise sanitized exactly once via
     * {@link DurableContextPropagator#sanitizeInboundCarrier(DurableMetadata)} (authenticated-only namespaces), stripping any namespace
     * whose registered decoder declares itself authenticated-only, and stripped of the identity-snapshot namespace.
     * The result is bounded like a payload and stored on the recorded command as its explicit carrier; the executor
     * binds it when it applies the signal. A losing delivery on a duplicate dedup key resolves to a no-op before any
     * carrier is stored (FR-WF-CTX-042).
     *
     * <p>A {@code null} {@code signalMetadata} delegates to the interface default, which in turn
     * calls the legacy 7-arg {@link #signal(WorkflowInstanceId, String, Object, String, String,
     * String, SqlClient)} overload — behavior unchanged for callers that supply no carrier.
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param forkStepId optional fork step id; required when {@code branchId} is set
     * @param branchId optional branch id; required when {@code forkStepId} is set
     * @param signalMetadata optional explicit durable-context carrier overriding the ambient capture
     *     as the drive's base context, or {@code null} to fall back to the legacy overloads
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the signal is recorded, or fails with
     *     {@link UnsupportedOperationException} when {@code signalMetadata} is non-null but durable
     *     context propagation is not wired (C4 no-silent-drop)
     */
    @Override
    public Future<Void> signal(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable DurableMetadata signalMetadata,
            SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("signal", tx);
        if (invalidClient != null) {
            return invalidClient;
        }

        if (signalMetadata == null) {
            return signal(id, signalName, payload, signalDedupKey, forkStepId, branchId, tx);
        }
        if (propagator == null) {
            // No-silent-drop (C4): an explicit carrier with no durable-context propagation wired has
            // nowhere to be bound. Silently accepting the carrier here would look like a successful
            // recording to the caller while the carrier is dropped on the floor; fail fast instead.
            return Future.failedFuture(new UnsupportedOperationException(
                    "explicit signal carrier requires durable context propagation to be wired"));
        }
        if ((forkStepId == null) != (branchId == null)) {
            return Future.failedFuture(
                    new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                            + EchoedNames.bounded(forkStepId) + ", branchId=" + EchoedNames.bounded(branchId)));
        }
        if (forkStepId != null && (branchTokens == null || branchEngine == null)) {
            return Future.failedFuture(new UnsupportedOperationException(
                    "branch-aware signal requires branch collaborators; engine constructed without them"));
        }
        // Sanitization seam: strip authenticated-only namespaces from the sender-supplied explicit carrier
        // exactly once, here at the entry. The identity snapshot is never stored in a command row.
        DurableMetadata sanitizedMetadata =
                propagator.sanitizeInboundCarrier(signalMetadata).without(IDENTITY_SNAPSHOT_NAMESPACE);
        return recordSignal(id, null, signalName, payload, signalDedupKey, forkStepId, branchId, sanitizedMetadata, tx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rejects a client that has no pending transaction before any collaborator is reached, then runs inside the
     * history announcement of every engine entry. In the caller's transaction it first looks up the {@code correlated}
     * dedup row of the signal's contract, signal name, scope and dedup key; a hit answers
     * {@link CorrelatedSignalReceipt.Outcome#DUPLICATE} and writes nothing. Otherwise it finds the open wait records of
     * the signal's contract, signal name and key (at most two, narrowed by the signal's definition id and scope, and
     * leaving out the records of ended instances), takes the execution lock of the matched instance, looks the dedup row
     * up again, re-checks that the record is still the one open, and records the signal as
     * {@link #signal(WorkflowInstanceId, String, Object, String, SqlClient)} does: admission, the size and depth bounds
     * of the payload, the actor of the caller, the open-signal cap, then the dedup claim and the {@code SIGNAL} command
     * in the same transaction. The dedup row and the command's dedup key hold SHA-256 digests, never the key. An
     * executor applies the command later.
     *
     * <p>A delivery that matches no record, a record that closed, whose instance ended or whose pinned definition does
     * not declare the signal before the lock, or several records fails the returned future with
     * {@link WorkflowSignalNoMatchException} or
     * {@link WorkflowSignalAmbiguousException} and records nothing. The failure names the contract and the signal and
     * never the key.
     *
     * @param signal the signal, addressed by contract, signal name and key; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} with a {@link CorrelatedSignalReceipt.Outcome#RECORDED} receipt that carries the id of
     *     the definition of the matched instance once the signal is recorded, or a
     *     {@link CorrelatedSignalReceipt.Outcome#DUPLICATE} receipt with no definition id when the same event was
     *     recorded before
     */
    @Override
    public Future<CorrelatedSignalReceipt> signalByKey(CorrelatedSignal signal, SqlClient tx) {
        Future<CorrelatedSignalReceipt> invalidClient = WorkflowTransactionClientGuard.reject("signalByKey", tx);
        if (invalidClient != null) {
            return invalidClient;
        }
        return historyPublisher.around(
                WorkflowHistoryFailure.Action.SIGNAL, null, tx, () -> recordSignalByKey(signal, tx));
    }

    /** The delivery of {@link #signalByKey}, inside the announcement of its failures. */
    private Future<CorrelatedSignalReceipt> recordSignalByKey(CorrelatedSignal signal, SqlClient tx) {
        String contractName = typeNames.contractNameOf(signal.contract());
        String signalName = signal.signalName();
        return deliverCorrelated(
                signal,
                contractName,
                () -> signalWaits.findOpen(
                        contractName, signalName, signal.key(), signal.definitionId(), signal.scopeKey(), tx),
                SignalWaitRecord::instanceId,
                (match, namespace, dedupKey) -> signalWaits
                        .findById(match.waitId(), tx)
                        .compose(current -> {
                            if (current.isEmpty()) {
                                return Future.<CorrelatedSignalReceipt>failedFuture(
                                        new WorkflowSignalNoMatchException(contractName, signalName));
                            }
                            return recordCorrelated(
                                    signal,
                                    contractName,
                                    match.instanceId(),
                                    match.definitionId(),
                                    match.forkStepId().isEmpty() ? null : match.forkStepId(),
                                    match.branchId().isEmpty() ? null : match.branchId(),
                                    match.waitId().toString(),
                                    namespace,
                                    dedupKey,
                                    tx);
                        }),
                tx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rejects a client that has no pending transaction before any collaborator is reached, then runs inside the
     * history announcement of every engine entry. It delivers as {@link #signalByKey(CorrelatedSignal, SqlClient)}
     * does, except for the match: it needs no key declaration on any wait. In the caller's transaction it looks up the
     * {@code correlated} dedup row (a hit answers {@link CorrelatedSignalReceipt.Outcome#DUPLICATE} and writes
     * nothing), then finds the live instances that carry the business key (at most two, across scopes and definitions,
     * narrowed by the signal's definition id and scope). An instance matches when the definition version it is pinned
     * to names the signal's contract and declares the signal among the instance's own signals, or holds no list of
     * names, and when it has not ended; a signal a version awaits only inside a fork branch does not match. It then
     * takes the execution lock of the instance, looks the dedup row up again, re-checks that the instance is still
     * live, and records the signal as {@link #signalByKey(CorrelatedSignal, SqlClient)} does, addressed to the
     * instance and never to a branch. The command keeps the target {@code {"name": ...}}, so a signal for an instance
     * that is still pending or not yet at its wait parks and applies when the wait is reached.
     *
     * <p>A delivery that matches no instance, an instance that ended before the lock, or several instances fails the
     * returned future with {@link WorkflowSignalNoMatchException} or {@link WorkflowSignalAmbiguousException} and
     * records nothing. The failure names the contract and the signal and never the business key.
     *
     * @param signal the signal, addressed by contract, signal name and business key; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} with a {@link CorrelatedSignalReceipt.Outcome#RECORDED} receipt that carries the id of
     *     the definition of the matched instance once the signal is recorded, or a
     *     {@link CorrelatedSignalReceipt.Outcome#DUPLICATE} receipt with no definition id when the same event was
     *     recorded before
     */
    @Override
    public Future<CorrelatedSignalReceipt> signalByBusinessKey(CorrelatedSignal signal, SqlClient tx) {
        Future<CorrelatedSignalReceipt> invalidClient =
                WorkflowTransactionClientGuard.reject("signalByBusinessKey", tx);
        if (invalidClient != null) {
            return invalidClient;
        }
        return historyPublisher.around(
                WorkflowHistoryFailure.Action.SIGNAL, null, tx, () -> recordSignalByBusinessKey(signal, tx));
    }

    /** The delivery of {@link #signalByBusinessKey}, inside the announcement of its failures. */
    private Future<CorrelatedSignalReceipt> recordSignalByBusinessKey(CorrelatedSignal signal, SqlClient tx) {
        String contractName = typeNames.contractNameOf(signal.contract());
        return deliverCorrelated(
                signal,
                contractName,
                () -> instances.findLiveByBusinessKey(
                        contractName, signal.signalName(), signal.key(), signal.definitionId(), signal.scopeKey(), tx),
                WorkflowInstance::id,
                // The instance is re-read under the lock: one that ended meanwhile answers no-match, in
                // recordCorrelated's admission.
                (match, namespace, dedupKey) -> recordCorrelated(
                        signal,
                        contractName,
                        match.id(),
                        match.definitionId(),
                        null,
                        null,
                        null,
                        namespace,
                        dedupKey,
                        tx),
                tx);
    }

    /** What a delivery does with the one match it found, once it holds the match's lock and no duplicate shows. */
    @FunctionalInterface
    private interface CorrelatedRecording<M> {
        Future<CorrelatedSignalReceipt> record(M match, String namespace, String dedupKey);
    }

    /**
     * The delivery both correlated entries share: the bound on the signal name, the committed duplicate lookup, the
     * match (at most two found; none answers no-match, several answer ambiguity), the execution lock of the matched
     * instance, the duplicate lookup again, and then the recording of the match.
     *
     * @param <M> what a match is: a wait record, or an instance
     * @param find reads at most two matches
     * @param instanceOf names the instance of a match, whose execution lock is taken
     * @param recording records the signal for the match
     */
    private <M> Future<CorrelatedSignalReceipt> deliverCorrelated(
            CorrelatedSignal signal,
            String contractName,
            Supplier<Future<List<M>>> find,
            Function<M, WorkflowInstanceId> instanceOf,
            CorrelatedRecording<M> recording,
            SqlClient tx) {
        String signalName = signal.signalName();
        if (signalName.length() > MAX_NAME_LENGTH) {
            return Future.failedFuture(new WorkflowInvalidPayloadException(
                    "Signal for contract '" + EchoedNames.bounded(contractName) + "' is refused: the signal name"
                            + " exceeds " + MAX_NAME_LENGTH + " characters",
                    List.of(ViolationDetail.unlocated("the signal name is too long", "payload"))));
        }
        String namespace = WorkflowDedupScopes.correlatedScope(contractName, signalName, signal.scopeKey());
        String dedupKey = WorkflowDedupScopes.correlatedKey(signal.dedupKey());
        return dedup.findCorrelated(namespace, dedupKey, tx).compose(duplicate -> {
            if (duplicate.isPresent()) {
                return Future.succeededFuture(duplicateReceipt());
            }
            return find.get().compose(found -> {
                if (found.isEmpty()) {
                    return Future.<CorrelatedSignalReceipt>failedFuture(
                            new WorkflowSignalNoMatchException(contractName, signalName));
                }
                if (found.size() > 1) {
                    return Future.<CorrelatedSignalReceipt>failedFuture(
                            new WorkflowSignalAmbiguousException(contractName, signalName));
                }
                M match = found.getFirst();
                return commands.lockInstance(instanceOf.apply(match), tx)
                        .compose(locked -> dedup.findCorrelated(namespace, dedupKey, tx))
                        .compose(again -> again.isPresent()
                                ? Future.succeededFuture(duplicateReceipt())
                                : recording.record(match, namespace, dedupKey));
            });
        });
    }

    /** The receipt of a delivery that an earlier delivery of the same event already recorded. */
    private static CorrelatedSignalReceipt duplicateReceipt() {
        return new CorrelatedSignalReceipt(CorrelatedSignalReceipt.Outcome.DUPLICATE, null);
    }

    /**
     * Records the signal for the addressed instance under the instance's execution lock, for a delivery that matched
     * an open wait (which also names the fork step, the branch and the wait) or an instance by business key (which
     * names none of them): the
     * admission, the bounds, the actor, the open-signal cap, the dedup claim and the command. A refusal before the
     * claim writes nothing; an instance that ended answers no-match. A claim that another delivery of the same event
     * won gives the open-signal slot back and answers a duplicate.
     */
    private Future<CorrelatedSignalReceipt> recordCorrelated(
            CorrelatedSignal signal,
            String contractName,
            WorkflowInstanceId id,
            String definitionId,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable String waitId,
            String namespace,
            String dedupKey,
            SqlClient tx) {
        String signalName = signal.signalName();
        return admitSignal(id, contractName, signalName, forkStepId, branchId, contractName, tx)
                .compose(admitted -> {
                    String payloadJson;
                    try {
                        recordValidator.validate(signal.payload());
                        payloadJson = signal.payload() == null ? null : Json.encode(signal.payload());
                    } catch (WorkflowInvalidPayloadException e) {
                        return Future.<CorrelatedSignalReceipt>failedFuture(new WorkflowInvalidPayloadException(
                                "Signal '" + EchoedNames.bounded(signalName) + "' of contract '"
                                        + EchoedNames.bounded(contractName) + "' is refused: " + e.getMessage(),
                                e.violations()));
                    }
                    return capturedForDelivery(signal, contractName).compose(actor -> commands.raiseOpenSignals(
                                    id, maxOpenSignals, tx)
                            .compose(raised -> {
                                if (!raised) {
                                    return Future.<CorrelatedSignalReceipt>failedFuture(
                                            new WorkflowSignalLimitException(
                                                    id, maxOpenSignals, contractName, signalName));
                                }
                                return dedup.claimOrResolveCorrelated(namespace, dedupKey, id, tx)
                                        .compose(inserted -> {
                                            if (!inserted) {
                                                // Another delivery of the event won the claim: give the slot back.
                                                return commands.lowerOpenSignals(id, tx)
                                                        .map(lowered -> duplicateReceipt());
                                            }
                                            return commands.recordSignal(
                                                            id,
                                                            signalName,
                                                            forkStepId,
                                                            branchId,
                                                            waitId,
                                                            dedupKey,
                                                            payloadJson,
                                                            null,
                                                            provenanceJson(signal.provenance()),
                                                            actor,
                                                            tx)
                                                    .onSuccess(recorded -> actors.reportAbsence(actor, "SIGNAL"))
                                                    .map(recorded -> new CorrelatedSignalReceipt(
                                                            CorrelatedSignalReceipt.Outcome.RECORDED, definitionId));
                                        });
                            }));
                });
    }

    /**
     * Takes the actor of a correlated delivery from the ambient identity. A delivery that carries a provenance is
     * refused with {@link WorkflowSignalActorException} when the capture yields no actor (no identity, or an anonymous
     * one) or the actor mapper refuses the identity, so a source never records a command that no actor stands behind. A
     * delivery without provenance keeps the capture as it is: an absent actor is recorded and a mapper failure
     * propagates unchanged.
     */
    private Future<CommandActor> capturedForDelivery(CorrelatedSignal signal, String contractName) {
        Future<CommandActor> captured = actors.captured();
        if (signal.provenance() == null) {
            return captured;
        }
        return captured.transform(result -> {
            if (result.failed()) {
                WorkflowSignalActorException refusal =
                        new WorkflowSignalActorException(contractName, signal.signalName());
                refusal.initCause(result.cause());
                return Future.failedFuture(refusal);
            }
            if (result.result().kind() == CommandActor.Kind.NONE) {
                return Future.failedFuture(new WorkflowSignalActorException(contractName, signal.signalName()));
            }
            return Future.succeededFuture(result.result());
        });
    }

    /**
     * The provenance as the JSON text stored in the command's own provenance column: the transport, the source and an
     * object of the string attributes, or {@code null} when the delivery carries none.
     */
    private static @Nullable String provenanceJson(@Nullable SignalProvenance provenance) {
        if (provenance == null) {
            return null;
        }
        JsonObject attributes = new JsonObject();
        provenance.attributes().forEach(attributes::put);
        return new JsonObject()
                .put("transport", provenance.transport())
                .put("source", provenance.source())
                .put("attributes", attributes)
                .encode();
    }

    /**
     * Records a {@code SIGNAL} command: the committed duplicate lookup first (a hit returns success and reads nothing
     * else); then the instance, its ended status and the declared name, read without the execution lock; then the size
     * and depth bounds of the payload and of the explicit carrier, which do no I/O; then, under the execution lock, the
     * duplicate lookup and the checks again, the open-signal cap through the queue row's counter, the dedup claim and the
     * command insert. A refusal before the claim writes nothing, so the caller may commit other work in the same
     * transaction and the refused key stays free.
     */
    private Future<Void> recordSignal(
            WorkflowInstanceId id,
            @Nullable String callerContractName,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable DurableMetadata carrier,
            SqlClient tx) {
        return historyPublisher.around(
                WorkflowHistoryFailure.Action.SIGNAL,
                id,
                tx,
                () -> recordSignalUnannounced(
                        id,
                        callerContractName,
                        signalName,
                        payload,
                        signalDedupKey,
                        forkStepId,
                        branchId,
                        carrier,
                        tx));
    }

    /** The recording of {@link #recordSignal}, inside the announcement of the history it writes. */
    private Future<Void> recordSignalUnannounced(
            WorkflowInstanceId id,
            @Nullable String callerContractName,
            String signalName,
            Object payload,
            String signalDedupKey,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable DurableMetadata carrier,
            SqlClient tx) {
        String oversized = oversizedName(signalName, forkStepId, branchId);
        if (oversized != null) {
            return Future.failedFuture(new WorkflowInvalidPayloadException(
                    "Signal for instance '" + id.value() + "' is refused: the " + oversized + " exceeds "
                            + MAX_NAME_LENGTH + " characters",
                    List.of(ViolationDetail.unlocated("the " + oversized + " is too long", "payload"))));
        }
        String key = WorkflowDedupScopes.signalKey(signalDedupKey, forkStepId, branchId);
        return dedup.findSignal(id, key, tx).compose(duplicate -> {
            if (duplicate.isPresent()) {
                return Future.<Void>succeededFuture();
            }
            return admitSignal(id, callerContractName, signalName, forkStepId, branchId, tx)
                    .compose(admitted -> {
                        String payloadJson;
                        String carrierJson;
                        try {
                            recordValidator.validate(payload);
                            JsonObject carrierDocument = carrier == null ? null : carrier.toCarrier();
                            if (carrierDocument != null) {
                                recordValidator.validate(carrierDocument);
                            }
                            payloadJson = payload == null ? null : Json.encode(payload);
                            carrierJson = carrierDocument == null ? null : carrierDocument.encode();
                        } catch (WorkflowInvalidPayloadException e) {
                            return Future.<Void>failedFuture(new WorkflowInvalidPayloadException(
                                    "Signal '" + EchoedNames.bounded(signalName) + "' for instance '" + id.value()
                                            + "' is refused: " + e.getMessage(),
                                    e.violations()));
                        }
                        return commands.lockInstance(id, tx)
                                .compose(locked -> dedup.findSignal(id, key, tx))
                                .compose(again -> again.isPresent()
                                        ? Future.<Void>succeededFuture()
                                        : admitSignal(id, callerContractName, signalName, forkStepId, branchId, tx)
                                                .compose(inst -> claimAndRecord(
                                                        id,
                                                        signalName,
                                                        signalDedupKey,
                                                        key,
                                                        forkStepId,
                                                        branchId,
                                                        payloadJson,
                                                        carrierJson,
                                                        tx)));
                    });
        });
    }

    /** The longest signal name, fork step id or branch id a recording accepts, in characters. */
    private static final int MAX_NAME_LENGTH = 255;

    /** Names the first of the signal name, fork step id and branch id that is too long, or {@code null}. */
    private static @Nullable String oversizedName(
            String signalName, @Nullable String forkStepId, @Nullable String branchId) {
        if (signalName != null && signalName.length() > MAX_NAME_LENGTH) {
            return "signal name";
        }
        if (forkStepId != null && forkStepId.length() > MAX_NAME_LENGTH) {
            return "fork step id";
        }
        if (branchId != null && branchId.length() > MAX_NAME_LENGTH) {
            return "branch id";
        }
        return null;
    }

    /** The cap, the dedup claim and the insert of a recording that passed every check, under the execution lock. */
    private Future<Void> claimAndRecord(
            WorkflowInstanceId id,
            String signalName,
            String signalDedupKey,
            String dedupKeyOfClaim,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable String payloadJson,
            @Nullable String carrierJson,
            SqlClient tx) {
        return actors.captured().compose(actor -> commands.raiseOpenSignals(id, maxOpenSignals, tx)
                .compose(raised -> {
                    if (!raised) {
                        return Future.<Void>failedFuture(new WorkflowSignalLimitException(id, maxOpenSignals));
                    }
                    return dedup.claimOrResolveSignalScoped(
                                    WorkflowDedupScopes.signalScope(id), dedupKeyOfClaim, id, tx)
                            .compose(inserted -> {
                                if (!inserted) {
                                    // A concurrent duplicate won the claim: undo the slot taken for this recording.
                                    return commands.lowerOpenSignals(id, tx);
                                }
                                return commands.recordSignal(
                                                id,
                                                signalName,
                                                forkStepId,
                                                branchId,
                                                null,
                                                signalDedupKey,
                                                payloadJson,
                                                carrierJson,
                                                null,
                                                actor,
                                                tx)
                                        .onSuccess(recorded -> actors.reportAbsence(actor, "SIGNAL"))
                                        .<Void>mapEmpty();
                            });
                }));
    }

    /**
     * Reads the instance a signal addresses and refuses what recording refuses: a missing instance, an ended one, a
     * caller of another contract and a name the pinned definition row does not declare for the addressed wait. A row
     * that stores no declaration admits every name; the executor then rejects an undeclared one.
     */
    private Future<WorkflowInstance> admitSignal(
            WorkflowInstanceId id,
            @Nullable String callerContractName,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            SqlClient tx) {
        return admitSignal(id, callerContractName, signalName, forkStepId, branchId, null, tx);
    }

    /**
     * As {@link #admitSignal(WorkflowInstanceId, String, String, String, String, SqlClient)}, except that when
     * {@code noMatchContractName} is not null an instance that ended, or whose pinned definition does not declare the
     * signal for the addressed wait, answers {@link WorkflowSignalNoMatchException} for that contract instead of a
     * rejection.
     */
    private Future<WorkflowInstance> admitSignal(
            WorkflowInstanceId id,
            @Nullable String callerContractName,
            String signalName,
            @Nullable String forkStepId,
            @Nullable String branchId,
            @Nullable String noMatchContractName,
            SqlClient tx) {
        return instances.findById(id, tx).compose(found -> {
            if (found.isEmpty()) {
                return Future.<WorkflowInstance>failedFuture(new WorkflowInstanceNotFoundException(id));
            }
            WorkflowInstance inst = found.get();
            if (switch (inst.status()) {
                case COMPLETED, COMPENSATED, CANCELLED, EXPIRED -> true;
                default -> false;
            }) {
                if (noMatchContractName != null) {
                    return Future.<WorkflowInstance>failedFuture(
                            new WorkflowSignalNoMatchException(noMatchContractName, signalName));
                }
                return Future.<WorkflowInstance>failedFuture(new WorkflowSignalRejectedException("Instance '"
                        + id.value() + "' cannot receive signal '" + EchoedNames.bounded(signalName)
                        + "': instance ended with status " + inst.status()));
            }
            DefinitionKey pin =
                    new DefinitionKey(inst.definitionScopeKey(), inst.definitionId(), inst.definitionVersion());
            return checkContract(inst, pin, callerContractName, tx)
                    .compose(ok -> commands.declaredSignalNames(pin, forkStepId, branchId, tx))
                    .compose(declared -> {
                        if (declared.isPresent() && !declared.get().contains(signalName)) {
                            if (noMatchContractName != null) {
                                return Future.<WorkflowInstance>failedFuture(
                                        new WorkflowSignalNoMatchException(noMatchContractName, signalName));
                            }
                            return Future.<WorkflowInstance>failedFuture(
                                    new WorkflowSignalRejectedException("Instance '" + id.value()
                                            + "' cannot receive signal '" + EchoedNames.bounded(signalName)
                                            + "': '" + inst.definitionId() + "' v" + inst.definitionVersion()
                                            + " does not declare it for the addressed wait"));
                        }
                        return Future.succeededFuture(inst);
                    });
        });
    }

    /**
     * Compares the caller's contract with the contract of the definition the instance is pinned to. The node's plan
     * names the contract when it holds one; otherwise the contract stored for the pinned row stands in for it, read
     * through the caller's transaction, so that recording needs no plan on this node.
     */
    private Future<Void> checkContract(
            WorkflowInstance inst, DefinitionKey pin, @Nullable String callerContractName, SqlClient tx) {
        if (callerContractName == null) {
            return Future.succeededFuture();
        }
        Optional<String> local = registry.find(pin).map(RuntimeWorkflow::contractName);
        Future<Optional<String>> contract =
                local.isPresent() ? Future.succeededFuture(local) : instances.findDefinitionContract(pin, tx);
        return contract.compose(pinned -> {
            if (pinned.isEmpty()) {
                return Future.<Void>failedFuture(new WorkflowVersionNotRunnableException(pin, inst.id()));
            }
            if (callerContractName.equals(pinned.get())) {
                return Future.<Void>succeededFuture();
            }
            return Future.<Void>failedFuture(new WorkflowDefinitionContractMismatchException(
                    pin,
                    "Instance '" + inst.id().value() + "' is pinned to '" + pin.definitionId() + "' v"
                            + pin.definitionVersion() + ", which implements contract '" + pinned.get()
                            + "'; a client of contract '" + EchoedNames.bounded(callerContractName)
                            + "' cannot signal it"));
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cancels the instance synchronously, in the caller's transaction, under the instance's execution lock, which it
     * waits for. An instance that has ended is refused; a {@code FAILED} instance, which a retry could resume, and an
     * instance that was never initialized ({@code PENDING}, or {@code FAILED} before its start ran) are cancelled. In
     * lock order it:
     *
     * <ol>
     *   <li>cancels, in one statement, every pending, deferred, claimed, parked and dead-lettered command, with its token
     *       and lease cleared and one {@code COMMAND_CANCELLED} entry each;</li>
     *   <li>cancels the timers those commands fire, writing {@code TIMER_CANCELLED} for each that was still scheduled;</li>
     *   <li>cancels the task the instance waits for (and its reminders), the tasks that cancelled completions reserved
     *       and the tasks of active branches;</li>
     *   <li>cancels the active branches;</li>
     *   <li>marks the instance {@code CANCELLED} and appends the cancellation entry.</li>
     * </ol>
     *
     * <p>An instance cancelled before it was initialized keeps no state and no current step, and no initial state is
     * computed. A segment that was running holds the lock, so the cancellation follows its commit; a command that was
     * claimed is cancelled, so its late settlement finds nothing to settle and writes nothing.
     *
     * @param id the workflow instance id; must not be null
     * @param callerReason human-readable reason for the cancellation; cut at 255 code points, with control characters
     *     replaced, and then stored in history
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the cancellation has been persisted
     */
    @Override
    public Future<Void> cancel(WorkflowInstanceId id, String callerReason, SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("cancel", tx);
        if (invalidClient != null) {
            return invalidClient;
        }
        // The reason is stored in the history, the instance and the events: bound it once, here.
        String reason = boundedReason(callerReason);
        return historyPublisher.around(
                WorkflowHistoryFailure.Action.CANCEL, id, tx, () -> cancelUnannounced(id, reason, tx));
    }

    /** The cancellation of {@link #cancel(WorkflowInstanceId, String, SqlClient)}, inside the announcement of its history. */
    private Future<Void> cancelUnannounced(WorkflowInstanceId id, String reason, SqlClient tx) {
        // Non-locking read: same lock-order rationale as signal(). The optimistic UPDATE catches
        // concurrent writes; using findByIdForUpdate would invert the timers-before-instances
        // invariant and cause deadlocks under cancel-vs-fire races.
        // The canceller is the caller's identity, taken before the instance's own context is bound.
        return actors.captured().compose(canceller -> underExecutionLock(id, tx, () -> instances.findById(id, tx))
                .compose((Optional<WorkflowInstance> optInst) -> {
                    if (optInst.isEmpty()) {
                        return Future.failedFuture(new WorkflowInstanceNotFoundException(id));
                    }
                    WorkflowInstance inst = optInst.get();
                    if (isTerminal(inst.status())) {
                        return Future.failedFuture(new WorkflowSignalRejectedException("Cannot cancel instance '"
                                + id.value() + "': already in terminal status " + inst.status()));
                    }

                    // Binder-row bind (Contract Appendix C2): instance-owned cancel drive. Every history entry the
                    // cancellation writes is logged with the canceller.
                    return history.nextSequence(id, tx).compose(from -> contextBinder
                            .withBound(inst, null, () -> doCancel(id, reason, inst, canceller, tx))
                            .compose(done -> commands.attributeHistory(id, from, canceller, tx)));
                }));
    }

    /** The most code points of a cancellation's reason that are stored. */
    static final int MAX_REASON_CODE_POINTS = 255;

    /**
     * Cuts a caller's cancellation reason at {@value #MAX_REASON_CODE_POINTS} code points and replaces every control
     * character and unpaired surrogate with a question mark, so that the reason can neither flood the history nor forge
     * a log line.
     */
    @Nullable
    static String boundedReason(@Nullable String reason) {
        if (reason == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(reason.length(), MAX_REASON_CODE_POINTS));
        int index = 0;
        int kept = 0;
        while (index < reason.length() && kept < MAX_REASON_CODE_POINTS) {
            int codePoint = reason.codePointAt(index);
            index += Character.charCount(codePoint);
            boolean unpairedSurrogate = codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
            out.appendCodePoint(Character.isISOControl(codePoint) || unpairedSurrogate ? '?' : codePoint);
            kept++;
        }
        return out.toString();
    }

    /**
     * Executes the cancel drive body after the instance-owned binder-row bind is in effect.
     * Extracted so {@link #cancel(WorkflowInstanceId, String, SqlClient)} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param id     the workflow instance id
     * @param reason human-readable reason for the cancellation
     * @param inst   the loaded (non-terminal) instance snapshot
     * @param canceller who cancelled the instance, logged on the entries of the cancelled commands
     * @param tx     the active transaction
     * @return a {@link Future} that completes when the cancellation has been persisted
     */
    private Future<Void> doCancel(
            WorkflowInstanceId id, String reason, WorkflowInstance inst, CommandActor canceller, SqlClient tx) {
        UUID waitTaskId = null;
        if (inst.waitType() == WaitType.TASK) {
            try {
                waitTaskId = UUID.fromString(inst.waitKey());
            } catch (IllegalArgumentException e) {
                return Future.failedFuture(new WorkflowDefinitionException(
                        "Invalid task UUID in wait_key for instance '" + id.value() + "'"));
            }
        }
        UUID taskId = waitTaskId;
        UUID activeTimerId = TimerLifecycleService.resolveActiveTimerId(inst);
        WorkflowActor cancelledByActor = new WorkflowActor.System("workflow-cancelled");
        Instant now = clock.instant();

        // Command rows first, in ascending id order: every command the instance still has is cancelled in one
        // statement.
        Future<Void> cancelTaskFuture = commands.cancelAll(id, canceller, tx).compose(cancelled -> {
            // Timers: the timer the instance waits on, the reminders of the task it waits for, then every other timer
            // whose command was cancelled and that is still scheduled.
            Future<Void> timersDone = Future.succeededFuture();
            if (activeTimerId != null) {
                timersDone = timerStore
                        .markCancelled(activeTimerId, clock.instant(), tx)
                        .compose(transition -> timerLifecycle.appendTimerCancelledHistory(
                                inst, activeTimerId, TimerCancelledCause.WORKFLOW_CANCELLED, tx));
            }
            if (taskId != null) {
                timersDone = timersDone.compose(v -> reminderScheduler.cancelPendingReminders(taskId, inst, tx));
            }
            for (SettledCommand command : cancelled) {
                UUID timerId = command.timerId();
                if (timerId != null && !timerId.equals(activeTimerId)) {
                    timersDone = timersDone
                            .compose(v -> timerStore.markCancelled(timerId, clock.instant(), tx))
                            .compose(transition -> transition == TimerStatusTransition.APPLIED
                                    ? timerLifecycle.appendTimerCancelledHistory(
                                            inst, timerId, TimerCancelledCause.WORKFLOW_CANCELLED, tx)
                                    : Future.<Void>succeededFuture());
                }
            }

            // Tasks: the task the instance waits for, whether open or reserved by a completion, with its history and
            // event, then every other task a cancelled completion reserved, then every task that is still live in the
            // task rows (the wait of an instance that failed may be gone).
            Future<Void> tasksDone = timersDone;
            if (taskId != null) {
                tasksDone = tasksDone.compose(v -> cancelOwnTask(inst, taskId, reason, now, cancelledByActor, tx));
            }
            for (SettledCommand command : cancelled) {
                UUID reserved = command.taskId();
                if ("COMPLETION".equals(command.kind()) && reserved != null && !reserved.equals(taskId)) {
                    tasksDone = tasksDone.compose(v -> taskStore
                            .markCancelled(reserved, reason, now, cancelledByActor, tx)
                            .<Void>mapEmpty());
                }
            }
            return tasksDone.compose(v -> commands.liveTasks(id, tx)).compose(live -> {
                Future<Void> leftovers = Future.succeededFuture();
                for (UUID leftover : live) {
                    leftovers = leftovers.compose(
                            v -> cancelLeftoverTask(inst, leftover, reason, now, cancelledByActor, tx));
                }
                return leftovers;
            });
        });

        // Cancel branch-owned waits for any active fan-out branches.
        // Lock order: timers → tasks (inside cancelBranchOwnedWaits) → branch tokens (below)
        // → instance (updateOptimistic below).
        // This block runs after the single-path timer/task cancellation above, which already
        // handles the instance-level wait (WAITING/TIMER, WAITING/SIGNAL, WAITING/TASK).
        // When the instance is parked at WAITING/JOIN, there are no instance-level waits to
        // close — but there may be branch-owned timers and tasks.
        Future<Void> cancelBranchesFuture = cancelTaskFuture.compose(v -> {
            if (branchTokens == null) {
                // Legacy test constructor without fork/join collaborators — no branches.
                return Future.succeededFuture();
            }
            return branchTokens.findActiveByWorkflow(id, tx).compose(activeBranches -> {
                Future<Void> branchChain = Future.succeededFuture();
                Instant branchNow = clock.instant();
                for (BranchToken branch : activeBranches) {
                    // timers → tasks (cancelBranchOwnedWaits), then branch token.
                    final BranchToken b = branch;
                    BranchToken cancelledBranch =
                            b.withClearedWait(BranchStatus.CANCELLED, b.currentStepId(), branchNow);
                    branchChain = branchChain
                            .compose(v2 -> cancelBranchOwnedWaits(b, tx))
                            .compose(v2 -> branchTokens
                                    .updateOptimistic(cancelledBranch, b.version(), tx)
                                    .mapEmpty());
                }
                return branchChain;
            });
        });

        // The instance ends: no wait record outlives it, the records of the branches still open included.
        return cancelBranchesFuture.compose(v -> waitRecords.closeAll(id, tx)).compose(v -> {
            long prevVersion = inst.version();
            WorkflowInstance cancelled = inst.withVersion(prevVersion + 1)
                    .withStatus(WorkflowStatus.CANCELLED)
                    .withWait(null, null)
                    .withError("CANCELLED", reason)
                    .withUpdatedAt(clock.instant());

            return instances
                    .updateOptimistic(cancelled, prevVersion, tx)
                    .compose(rowCount -> requireRowUpdated(rowCount, "instance '" + id.value() + "' during cancel"))
                    .compose(v2 -> history.nextSequence(id, tx))
                    .compose(seq -> {
                        String histPayload = Json.encode(new CancelledHistoryPayload(
                                decisions.redactReason(
                                        new DefinitionKey(
                                                inst.definitionScopeKey(),
                                                inst.definitionId(),
                                                inst.definitionVersion()),
                                        reason,
                                        null,
                                        inst.stateJson(),
                                        tx),
                                WorkflowPayloads.commandCorrelationId()));
                        return history.append(
                                        new WorkflowHistoryEntry(
                                                id, seq, WorkflowEntryType.CANCELLED, histPayload, clock.instant()),
                                        tx)
                                .compose(v2 -> emitEvent(
                                        cancelled,
                                        WorkflowEventType.WORKFLOW_CANCELLED,
                                        seq,
                                        null,
                                        null,
                                        attrs("reason", reason),
                                        tx));
                    });
        });
    }

    /**
     * Cancels a task the cancellation found in the task rows and nothing else named: its due-date timer and reminders,
     * then the task, with its history and event.
     */
    private Future<Void> cancelLeftoverTask(
            WorkflowInstance inst, UUID taskId, String reason, Instant now, WorkflowActor actor, SqlClient tx) {
        return taskStore.findById(taskId, tx).compose(found -> {
            Future<Void> timers = Future.succeededFuture();
            UUID dueTimerId = found.map(task -> task.dueDateTimerId()).orElse(null);
            if (dueTimerId != null) {
                timers = timerStore
                        .markCancelled(dueTimerId, clock.instant(), tx)
                        .compose(transition -> transition == TimerStatusTransition.APPLIED
                                ? timerLifecycle.appendTimerCancelledHistory(
                                        inst, dueTimerId, TimerCancelledCause.WORKFLOW_CANCELLED, tx)
                                : Future.<Void>succeededFuture());
            }
            return timers.compose(v -> reminderScheduler.cancelPendingReminders(taskId, inst, tx))
                    .compose(v -> cancelOwnTask(inst, taskId, reason, now, actor, tx));
        });
    }

    /** Cancels a task of the instance, open or reserved by a completion, and writes its history and event. */
    private Future<Void> cancelOwnTask(
            WorkflowInstance inst, UUID taskId, String reason, Instant now, WorkflowActor actor, SqlClient tx) {
        return taskStore.markCancelled(taskId, reason, now, actor, tx).compose(transition -> taskLifecycle
                .appendTaskCancelledHistory(inst, taskId, reason, actor, now, tx)
                .compose(taskCancelledSeq -> emitEvent(
                        inst,
                        WorkflowEventType.TASK_CANCELLED,
                        taskCancelledSeq,
                        taskId,
                        inst.currentStepId(),
                        attrs("cause", "WORKFLOW_CANCELLED", "cancelledBy", WorkflowActorMaps.toMap(actor)),
                        tx)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records a {@code RETRY} command in the caller's transaction and returns once it is recorded; no workflow code
     * runs here. An executor runs it afterwards, in its own segment, in queue order: it initializes an instance whose
     * start failed before initialization from the stored start payload, re-drives a {@code FAILED} instance from its
     * current step and applies the signals parked meanwhile at the wait it reaches, and then offers every dead-lettered
     * command of the instance again with its attempts reset. A retry that finds nothing to recover completes as stale.
     * The call is refused, under the instance's execution lock, when the instance has ended or is neither {@code FAILED}
     * nor holds a dead-lettered command.
     *
     * @param id the workflow instance id; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the retry is recorded
     */
    @Override
    public Future<Void> retry(WorkflowInstanceId id, SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("retry", tx);
        if (invalidClient != null) {
            return invalidClient;
        }
        return historyPublisher.around(WorkflowHistoryFailure.Action.RETRY, id, tx, () -> retryUnannounced(id, tx));
    }

    /** The retry of {@link #retry(WorkflowInstanceId, SqlClient)}, inside the announcement of its history. */
    private Future<Void> retryUnannounced(WorkflowInstanceId id, SqlClient tx) {
        return underExecutionLock(id, tx, () -> instances.findById(id, tx))
                .compose((Optional<WorkflowInstance> found) -> {
                    if (found.isEmpty()) {
                        return Future.<Void>failedFuture(new WorkflowInstanceNotFoundException(id));
                    }
                    WorkflowInstance inst = found.get();
                    if (EndedInstanceOutcomes.isEnded(inst.status())) {
                        return Future.<Void>failedFuture(retryRefusal(id, inst.status()));
                    }
                    if (inst.status() == WorkflowStatus.FAILED) {
                        return recordRetryOnce(id, tx);
                    }
                    return commands.hasDeadLetters(id, tx)
                            .compose(dead -> dead
                                    ? recordRetryOnce(id, tx)
                                    : Future.<Void>failedFuture(retryRefusal(id, inst.status())));
                });
    }

    /**
     * Records a retry unless an instance-level one is already on its way: a repeated call, for instance after an
     * uncertain response, then succeeds and records nothing, because one retry recovers everything a second would. A
     * retry addressed to one branch recovers only that branch, so it does not count.
     */
    private Future<Void> recordRetryOnce(WorkflowInstanceId id, SqlClient tx) {
        return commands.unsettledTargets(id, "RETRY", tx)
                .compose(pending -> pending.stream().noneMatch(String::isEmpty)
                        ? actors.recording("RETRY", actor -> commands.recordRetry(id, actor, tx))
                                .<Void>mapEmpty()
                        : Future.<Void>succeededFuture());
    }

    private static WorkflowSignalRejectedException retryRefusal(WorkflowInstanceId id, WorkflowStatus status) {
        return new WorkflowSignalRejectedException("Cannot retry instance '" + id.value() + "': status is " + status
                + " (must be FAILED or hold dead-lettered commands)");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction, then delegates to
     * {@link #migrate(WorkflowInstanceId, long, SqlClient)}.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetVersion the definition version to migrate to
     * @return a {@link Future} that completes when the migration is recorded
     */
    @Override
    public Future<Void> migrate(WorkflowInstanceId id, long targetVersion) {
        return txRunner.inTransaction(null, tx -> migrate(id, targetVersion, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction, then delegates to
     * {@link #migrate(WorkflowInstanceId, String, long, SqlClient)}.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetScope   the scope of the definition to migrate to; must not be null
     * @param targetVersion the definition version to migrate to
     * @return a {@link Future} that completes when the migration is recorded
     */
    @Override
    public Future<Void> migrate(WorkflowInstanceId id, String targetScope, long targetVersion) {
        return txRunner.inTransaction(null, tx -> migrate(id, targetScope, targetVersion, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records a {@code MIGRATION} command in the caller's transaction and returns once it is recorded; no workflow
     * code runs here. An executor that holds the source and the target plan runs it afterwards, in its own segment, in
     * queue order, and rejects it with the instance unchanged on its previous version when it is not valid. The call is
     * refused, under the instance's execution lock, only when the instance has ended; the executor owns the opt-in
     * {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry}, and if no registry was provided (the migration
     * module was not installed) the call fails immediately with {@link UnsupportedOperationException} before any
     * database work.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetVersion the definition version to migrate to
     * @param tx            the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the migration is recorded
     */
    @Override
    public Future<Void> migrate(WorkflowInstanceId id, long targetVersion, SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("migrate", tx);
        if (invalidClient != null) {
            return invalidClient;
        }

        return historyPublisher.around(
                WorkflowHistoryFailure.Action.MIGRATE,
                id,
                tx,
                () -> migrationExecutor.record(id, null, targetVersion, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the migration like {@link #migrate(WorkflowInstanceId, long, SqlClient)}; the executor resolves the
     * target in {@code targetScope}.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetScope   the scope of the definition to migrate to; must not be null
     * @param targetVersion the definition version to migrate to
     * @param tx            the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the migration is recorded
     */
    @Override
    public Future<Void> migrate(WorkflowInstanceId id, String targetScope, long targetVersion, SqlClient tx) {
        Future<Void> invalidClient = WorkflowTransactionClientGuard.reject("migrate", tx);
        if (invalidClient != null) {
            return invalidClient;
        }
        Objects.requireNonNull(targetScope, "targetScope");
        return historyPublisher.around(
                WorkflowHistoryFailure.Action.MIGRATE,
                id,
                tx,
                () -> migrationExecutor.record(id, targetScope, targetVersion, tx));
    }

    // --- State machine driver — extracted to WorkflowTransitionDriver (slice C5) ---
    //
    // driveTransitions/Step and the per-node handlers (handleServiceDispatch / handleWaitSignal
    // (+timeout) / handleDecision / handleComplete / handleFail / handleTimerNode /
    // handleHumanTaskNode (+due-date)) now live in WorkflowTransitionDriver. The facade injects the
    // driver eagerly and calls driver.driveTransitions(...) from its lifecycle entrypoints. The
    // driver↔fork/join cycle is broken with the coordinator holding a lazy
    // Provider<WorkflowTransitionDriver>. The task-creation helpers (resolveAssignment /
    // buildTaskRecord / appendTaskCreatedHistory) moved to TaskLifecycleService in slice C7; the
    // driver reaches them through a lazy Provider<TaskLifecycleService>. The timer helper
    // resolveFireAt moved to TimerLifecycleService in slice C8; the driver reaches it through a lazy
    // Provider<TimerLifecycleService> and no longer references this facade.

    // --- TransactionalTaskCallbacks (delegated to TaskLifecycleService, slice C7) ---

    /**
     * {@inheritDoc}
     *
     * <p>Records the completion as a {@code COMPLETION} command in the caller's transaction and reserves the task
     * ({@code OPEN} to {@code COMPLETING}); an executor applies it afterwards in its own segment. The steps, in order:
     * <ol>
     *   <li>A committed duplicate (same key and fingerprint) returns its earlier result.</li>
     *   <li>Non-locking task read; the payload's size and depth bounds and, when the task's snapshot stores a schema for
     *       the decision, the payload's conformance to it.</li>
     *   <li>The execution lock; the duplicate again (another fingerprint is a conflict), then the task row lock: the task
     *       must be {@code OPEN}, a supplied {@code expectedAssignment} must match, the reviewed subject version must
     *       match when the row requires stability, and the decision must be in the snapshot.</li>
     *   <li>The deduplication claim, the {@code COMPLETION} command and the reservation.</li>
     * </ol>
     *
     * @param cmd the completion command; must not be null
     * @param tx  the active transaction context
     * @return a {@link Future} resolving to the mutation result
     */
    @Override
    public Future<TaskMutationResult> taskCompleted(TaskCompletionCommand cmd, SqlClient tx) {
        Future<TaskMutationResult> invalidClient = WorkflowTransactionClientGuard.reject("taskCompleted", tx);
        if (invalidClient != null) {
            return invalidClient;
        }

        return aroundTask(
                WorkflowHistoryFailure.Action.TASK_COMPLETE,
                cmd.taskId(),
                tx,
                () -> taskLifecycle.taskCompleted(cmd, tx));
    }

    /**
     * {@inheritDoc}
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
    @Override
    public Future<TaskMutationResult> taskReassigned(TaskReassignmentCommand cmd, SqlClient tx) {
        Future<TaskMutationResult> invalidClient = WorkflowTransactionClientGuard.reject("taskReassigned", tx);
        if (invalidClient != null) {
            return invalidClient;
        }

        return aroundTask(
                WorkflowHistoryFailure.Action.TASK_REASSIGN,
                cmd.taskId(),
                tx,
                () -> taskLifecycle.taskReassigned(cmd, tx));
    }

    /**
     * Runs a task action inside the announcement of the history it writes to the instance that owns the task. The
     * owner is read only when a history listener is bound; a task that cannot be read leaves the action to refuse it,
     * and only its failure is then reported.
     */
    private <T> Future<T> aroundTask(
            WorkflowHistoryFailure.Action action, UUID taskId, SqlClient tx, Supplier<Future<T>> work) {
        if (!historyPublisher.hasListeners()) {
            return work.get();
        }
        return taskOwner(taskId, tx).compose(owner -> historyPublisher.around(action, owner, tx, work));
    }

    /** The instance that owns the task, or null when the task cannot be read. */
    private Future<WorkflowInstanceId> taskOwner(UUID taskId, SqlClient tx) {
        try {
            return taskStore
                    .findById(taskId, tx)
                    .map(task -> task.map(TaskRecord::workflowId).orElse(null))
                    .recover(failure -> Future.succeededFuture(null));
        } catch (RuntimeException failure) {
            return Future.succeededFuture(null);
        }
    }

    // --- Internal helpers ---

    /**
     * Takes the execution lock of the instance in the caller's transaction, then runs the body. Every
     * instance-changing entry point calls it before its first read-for-write or write, so one transaction at a time
     * changes an instance. A wait that times out, or a deadlock, fails the returned future with the database error.
     *
     * @param id   the instance to lock
     * @param tx   the caller's transaction
     * @param body the work to run once the lock is held
     * @param <T>  the result type of the body
     * @return the future of the body, or the failure of the lock
     */
    private <T> Future<T> underExecutionLock(WorkflowInstanceId id, SqlClient tx, Supplier<Future<T>> body) {
        return commands.lockInstance(id, tx).compose(locked -> body.get());
    }

    /**
     * Returns a successful {@code Future<Void>} when {@code rowCount > 0}, or a failed future with
     * {@link WorkflowConflictException} when {@code rowCount == 0}.
     *
     * <p>Delegates to {@link WorkflowPayloads#requireRowUpdated}.
     *
     * @param rowCount the number of rows updated by the optimistic update
     * @param context human-readable description of the operation (for the exception message)
     * @return a completed or failed {@link Future}
     */
    private static Future<Void> requireRowUpdated(int rowCount, String context) {
        return WorkflowPayloads.requireRowUpdated(rowCount, context);
    }

    /**
     * Verifies that the plan hash stored on the instance matches the hash of the currently-resolved
     * plan.
     *
     * <p>A mismatch indicates that the plan content changed between deployments without a version
     * bump — a packaging error. The method throws {@link WorkflowPlanHashDriftException} so the
     * caller can wrap it in a {@link Future#failedFuture(Throwable)} and propagate it.
     *
     * <p>Delegates to {@link WorkflowPayloads#requirePlanHashMatches}.
     *
     * @param inst the workflow instance whose {@code planHash} is to be checked
     * @param rw the resolved runtime workflow whose {@code planHash} is compared to the stored hash
     * @throws WorkflowPlanHashDriftException if the hashes do not match
     */
    private static void requirePlanHashMatches(WorkflowInstance inst, RuntimeWorkflow rw) {
        WorkflowPayloads.requirePlanHashMatches(inst, rw);
    }

    /**
     * Returns {@code true} if the given status is terminal: the instance has ended and takes no further transition. A
     * {@code FAILED} instance is not terminal, because a retry can resume it, and so it can be cancelled.
     *
     * @param status the status to check
     * @return {@code true} for ended statuses
     */
    private static boolean isTerminal(WorkflowStatus status) {
        return EndedInstanceOutcomes.isEnded(status);
    }

    // --- Event emission helpers (delegating to WorkflowEventEmitter) ---

    /**
     * Emits a {@code WORKFLOW_EVENT} side-effect intent for a lifecycle event. If no
     * {@code WORKFLOW_EVENT} recorder is registered (the kind is optional), the call is a silent
     * no-op that returns a succeeded future.
     *
     * <p>Delegates to {@link WorkflowEventEmitter#emitEvent}.
     *
     * @param inst       the workflow instance that produced the event
     * @param type       the event type
     * @param sequence   the {@code workflow_history} sequence number at which the event occurred
     * @param taskId     the task UUID for task-lifecycle events; {@code null} for workflow events
     * @param stepId     the plan step id that produced the event; {@code null} when not step-bound
     * @param attributes arbitrary key-value metadata; must not contain {@code null} values
     * @param tx         the active transaction
     * @return a {@link Future} that completes when the intent has been durably recorded (or is
     *     silently skipped because no event recorder is registered)
     */
    private Future<Void> emitEvent(
            WorkflowInstance inst,
            WorkflowEventType type,
            long sequence,
            UUID taskId,
            String stepId,
            Map<String, Object> attributes,
            SqlClient tx) {
        return eventEmitter.emitEvent(inst, type, sequence, taskId, stepId, attributes, tx);
    }

    /**
     * Builds a string-keyed attribute map, omitting entries whose value is {@code null}.
     * Use this instead of {@link Map#of} when any value may be null.
     *
     * <p>Delegates to {@link WorkflowEventEmitter#attrs}.
     *
     * @param pairs alternating key, value pairs; must be an even number of elements
     * @return a map containing only the non-null entries, in insertion order
     */
    private static Map<String, Object> attrs(Object... pairs) {
        return WorkflowEventEmitter.attrs(pairs);
    }

    // ===================================================================================
    // PRD-WF-002: Fork dispatch + join evaluation — extracted to ForkJoinCoordinator (slice C4).
    // The cancelBranchOwnedWaits helper below stays on the engine because the single-path cancel
    // flow also uses it; the coordinator reaches it via the lazy Provider<WorkflowEngine> edge.
    // ===================================================================================

    /**
     * Cancels every open timer and every open task owned by the given branch, enforcing the
     * lock-order invariant:
     * {@code workflow_timers → workflow_tasks → workflow_branch_tokens → workflow_instances}.
     *
     * <p>This helper does NOT touch {@code workflow_branch_tokens}. Each call site is responsible
     * for the branch-token state transition that runs after this helper returns (e.g.,
     * {@link BranchStatus#SUPERSEDED} in {@link #supersedeNonTerminalSiblings} or
     * {@link BranchStatus#CANCELLED} in {@link #cancel(WorkflowInstanceId, String, SqlClient)}).
     *
     * <p>Timers are cancelled first (by id, one per row lock) so that any concurrent
     * timer command, which holds a {@code workflow_timers} lock before it calls into the engine,
     * cannot race the branch-token update and deadlock.
     *
     * <p>Package-visible for integration testing; not part of any public API.
     *
     * @param branch the branch token identifying which timers and tasks to cancel
     * @param tx the active transaction
     * @return a {@link Future} that completes when all owned timers and tasks are in a terminal
     *     state
     */
    Future<Void> cancelBranchOwnedWaits(BranchToken branch, SqlClient tx) {
        // Step 1 — timers first (lock order: workflow_timers before workflow_tasks).
        return timerStore.findScheduledByBranchToken(branch.id(), tx).compose(timers -> {
            Future<Void> timerChain = Future.succeededFuture();
            Instant now = clock.instant();
            for (TimerRecord timer : timers) {
                timerChain = timerChain
                        .compose(v -> timerStore.markCancelled(timer.timerId(), now, tx))
                        .mapEmpty();
            }
            // Step 2 — tasks second (lock order: workflow_tasks before workflow_branch_tokens).
            return timerChain
                    .compose(v -> taskStore.findOpenByBranchToken(branch.id(), tx))
                    .compose(tasks -> {
                        Future<Void> taskChain = Future.succeededFuture();
                        WorkflowActor cancelActor = new WorkflowActor.System("branch-cancelled");
                        Instant now2 = clock.instant();
                        String reason = "BRANCH_CANCELLED";
                        for (TaskRecord task : tasks) {
                            taskChain = taskChain
                                    .compose(v -> taskStore.markCancelled(task.taskId(), reason, now2, cancelActor, tx))
                                    .mapEmpty();
                        }
                        return taskChain;
                    });
        });
    }
}
