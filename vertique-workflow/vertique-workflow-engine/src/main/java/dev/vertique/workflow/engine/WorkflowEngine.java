// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.IsolationLevel;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMaps;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
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
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    // --- Dependencies ---

    private final WorkflowTransactionRunner<SqlClient> txRunner;
    private final WorkflowRegistry registry;
    private final WorkflowInstanceRepository<SqlClient> instances;
    private final WorkflowHistoryRepository<SqlClient> history;
    private final WorkflowDedupRepository<SqlClient> dedup;
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
     * ({@code taskCompleted}/{@code taskDueFired}/{@code taskReassigned}/{@code taskReminderFired})
     * delegate their bodies to this service. It drives the state machine forward after a task
     * mutation via the {@link WorkflowTransitionDriver} and {@link ForkJoinCoordinator}; the driver
     * reaches its task-creation helpers ({@code resolveAssignment}, {@code appendTaskCreatedHistory},
     * static {@code buildTaskRecord}) through a lazy {@code Provider<TaskLifecycleService>} that
     * breaks the driver↔task constructor cycle.
     */
    private final TaskLifecycleService taskLifecycle;

    /**
     * Collaborator owning the timer-fire callback paths (PRD-WF-006, Slice C8). The facade's
     * {@code TransactionalTimerCallbacks} entrypoints ({@code timerFired}/{@code timerFiringFailed})
     * delegate their bodies to this service. It drives the state machine forward after a timer fires
     * via the {@link WorkflowTransitionDriver} and {@link ForkJoinCoordinator}, and dispatches the
     * single-path {@code TASK} due-date wait to {@link TaskLifecycleService} (so it injects the task
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

    @Inject
    WorkflowEngine(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
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
            WorkflowContextBinder contextBinder) {
        this.txRunner = txRunner;
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.dedup = dedup;
        this.timerStore = timerStore;
        this.taskStore = taskStore;
        this.clock = clock;
        this.branchTokens = branchTokens;
        this.branchEngine = branchEngine;
        this.eventEmitter = eventEmitter;
        this.historyRecorder = historyRecorder;
        this.reminderScheduler = reminderScheduler;
        this.forkJoin = forkJoin;
        this.driver = driver;
        this.signalHandler = signalHandler;
        this.taskLifecycle = taskLifecycle;
        this.timerLifecycle = timerLifecycle;
        this.migrationExecutor = migrationExecutor;
        this.propagator = propagator;
        this.contextBinder = contextBinder;
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
     * {@link WorkflowEngineFactory#create(WorkflowTransactionRunner, WorkflowRegistry,
     * WorkflowInstanceRepository, WorkflowHistoryRepository, WorkflowDedupRepository,
     * BranchTokenRepository, JoinStateRepository, TimerStore, TaskStore, Clock, java.util.Set,
     * java.util.Set, Optional, DurableContextPropagator, java.util.Set)}): production Dagger wiring
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
     * <p>Package-visible (not {@code private}) so the test-scope factory in the same package can
     * invoke it; it is not part of any production API and is unused outside test code.
     */
    WorkflowEngine(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
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
            @Nullable dev.vertique.context.InboundExecutionContextScope inboundExecScope) {
        this.txRunner = txRunner;
        this.registry = registry;
        this.instances = instances;
        this.history = history;
        this.dedup = dedup;
        this.timerStore = timerStore;
        this.taskStore = taskStore;
        this.clock = clock;
        this.branchTokens = branchTokens;
        this.branchEngine = branchEngine;
        this.eventEmitter = eventEmitter;
        this.historyRecorder = historyRecorder;
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
                clock);
        this.driver = new WorkflowTransitionDriver(
                instances,
                history,
                recorders,
                taskStore,
                eventEmitter,
                reminderScheduler,
                compensationService,
                this.forkJoin,
                clock,
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
                builtContextBinder);
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
                eventEmitter,
                this.forkJoin,
                this.driver,
                this.taskLifecycle,
                clock,
                builtContextBinder);
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
                clock);
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
                builtContextBinder);
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
     * <p>Opens a new database transaction and re-executes the current step of a {@code FAILED}
     * instance.
     *
     * @param id the workflow instance id; must not be null
     * @return a {@link Future} that completes when the retry attempt has been initiated
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
     * Concurrent transitions cannot interleave between the two reads. Does not require the
     * definition version to still be registered.
     *
     * @param id the workflow instance id; must not be null
     * @return a {@link Future} resolving to a {@link WorkflowView} for the instance
     */
    @Override
    public Future<WorkflowView> query(WorkflowInstanceId id) {
        return txRunner.inTransaction(
                IsolationLevel.REPEATABLE_READ, tx -> instances.findById(id, tx).compose(optInst -> {
                    if (optInst.isEmpty()) {
                        return Future.<WorkflowView>failedFuture(new WorkflowInstanceNotFoundException(id));
                    }
                    return history.listByInstance(id, tx).map(entries -> new WorkflowView(optInst.get(), entries));
                }));
    }

    // --- TransactionalWorkflowOperations ---

    /**
     * {@inheritDoc}
     *
     * <p>Atomically claims or resolves the start dedup slot. If a prior caller already committed a
     * matching row, returns the winning instance id without creating a new instance. Otherwise,
     * coerces the payload, derives the initial state, persists the instance and history, and drives
     * initial transitions.
     *
     * @param cmd the start command; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} resolving to the id of the created (or existing) instance
     */
    @Override
    public Future<WorkflowInstanceId> start(StartCommand cmd, SqlClient tx) {
        RuntimeWorkflow rw;
        try {
            if (cmd.requestedDefinitionVersion() != null) {
                // Explicit version pin (FR-WF-DEF-069): resolve the requested version exactly.
                rw = registry.resolvePinned(cmd.definitionId(), cmd.requestedDefinitionVersion());
            } else {
                // Default: resolve the current (highest registered) version.
                rw = registry.resolveCurrent(cmd.definitionId());
            }
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        long resolvedVersion = rw.plan().definitionVersion();
        String fingerprint = "definitionVersion=" + resolvedVersion;

        UUID newId = UUID.randomUUID();
        WorkflowInstanceId proposedId = new WorkflowInstanceId(newId);

        return dedup.claimOrResolveStart(rw.plan().definitionId(), cmd.idempotencyKey(), proposedId, fingerprint, tx)
                .compose((StartDedupResult result) -> {
                    if (!result.didInsert()) {
                        // Loser: an existing dedup row was found. Apply idempotency semantics.
                        if (cmd.requestedDefinitionVersion() != null) {
                            // Strict mode: the caller pinned an explicit version. If the stored
                            // fingerprint differs from the incoming one, reject as a conflict
                            // (FR-WF-DEF-070 / AC #14). A null existing fingerprint (legacy row)
                            // is treated as "no version recorded" — lenient, no conflict.
                            String existingFp = result.existingFingerprint();
                            if (existingFp != null && !existingFp.equals(fingerprint)) {
                                return Future.failedFuture(new WorkflowIdempotencyConflictException(
                                        "start", cmd.idempotencyKey(), existingFp, fingerprint));
                            }
                        }
                        // Lenient: no requested version, or fingerprints match — return existing.
                        return Future.succeededFuture(result.workflowId());
                    }
                    // Winner: proceed with instance creation.
                    return doCreateInstance(cmd, rw, proposedId, tx);
                });
    }

    /**
     * Creates the workflow instance row, persists the START history entry, and drives initial
     * transitions. Called only by the dedup winner in {@link #start(StartCommand, SqlClient)}.
     *
     * @param cmd the start command
     * @param rw the resolved runtime workflow
     * @param id the instance id to use
     * @param tx the active transaction
     * @return a {@link Future} resolving to the new instance id after transitions complete
     */
    private Future<WorkflowInstanceId> doCreateInstance(
            StartCommand cmd, RuntimeWorkflow rw, WorkflowInstanceId id, SqlClient tx) {
        // Coerce payload to the declared start payload type.
        Object coercedPayload = coercePayload(cmd.payload(), rw.startPayloadType());

        // Derive initial state via the init function.
        Object initialState;
        try {
            initialState = rw.initialState().apply(coercedPayload);
        } catch (Exception e) {
            return Future.failedFuture(new WorkflowDefinitionException(
                    "initialState function threw for definition '" + cmd.definitionId() + "': " + e.getMessage()));
        }
        String stateJson = Json.encode(initialState);

        // Resolve subject ref: caller-supplied wins; fall back to plan resolver if configured.
        WorkflowSubjectRef subjectRef;
        try {
            subjectRef = resolveSubjectRef(cmd, rw.plan(), initialState, rw.callbacks());
        } catch (WorkflowDefinitionException e) {
            return Future.failedFuture(e);
        }

        Instant now = clock.instant();

        // FR-WF-CTX-010/012 (PRD-WF-007 AC-1): capture-only — no new durable scope is installed
        // around the start drive; the ambient context bound by the caller IS the capture. An empty
        // capture persists as NULL (never an empty DurableMetadata document, per FR-WF-CTX-010). A
        // null propagator (legacy test construction without durable-context wiring, mirroring
        // BranchTransitionEngine's tolerance) captures nothing.
        DurableMetadata captured =
                propagator == null ? DurableMetadata.empty() : propagator.capture(DispatchBoundary.WORKFLOW);
        DurableMetadata instanceMetadata = captured.isEmpty() ? null : captured;

        WorkflowInstance fresh = new WorkflowInstance(
                id,
                rw.plan().definitionId(),
                rw.plan().definitionVersion(),
                rw.plan().planHash(),
                0L,
                WorkflowStatus.RUNNING,
                cmd.businessKey(),
                subjectRef,
                rw.plan().initialStepId(),
                null,
                null,
                null,
                stateJson,
                null,
                null,
                now,
                now,
                instanceMetadata);

        return instances
                .insert(fresh, tx)
                .compose(v -> appendStartHistory(fresh, cmd, tx))
                .compose(v -> driver.driveTransitions(fresh, rw, tx))
                .map(v -> id);
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
     * @param cmd          the start command; provides the caller-supplied subject ref (precedence 1)
     * @param plan         the compiled workflow plan; provides the resolver callback id (precedence 2)
     * @param initialState the initial state derived from the start payload; passed to the resolver
     * @param callbacks    the callback registry used to look up the resolver function
     * @return the resolved {@link WorkflowSubjectRef}, or {@code null} if none applies
     * @throws WorkflowDefinitionException if the resolver is configured but returns {@code null}
     */
    private WorkflowSubjectRef resolveSubjectRef(
            StartCommand cmd, WorkflowPlan plan, Object initialState, WorkflowCallbackRegistry callbacks) {
        // Precedence 1: caller-supplied subject ref wins unconditionally.
        if (cmd.subjectRef() != null) {
            return cmd.subjectRef();
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
     * Appends the {@code START} history entry for a newly created instance and emits the
     * {@link WorkflowEventType#WORKFLOW_STARTED} event.
     *
     * <p>Delegates to {@link WorkflowHistoryRecorder#appendStartHistory}.
     *
     * @param inst the fresh instance
     * @param cmd the original start command
     * @param tx the active transaction
     * @return a {@link Future} that completes when the entry is inserted and the event is emitted
     */
    private Future<Void> appendStartHistory(WorkflowInstance inst, StartCommand cmd, SqlClient tx) {
        return historyRecorder.appendStartHistory(inst, cmd, tx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs a signal dedup claim, loads the instance row, validates the waiting state, coerces
     * the payload, applies the state updater, and drives transitions. Returns a no-op if the signal
     * was already applied — without ever loading the instance row. Throws
     * {@link WorkflowVersionPinUnavailableException} when the pinned plan version is no longer
     * registered.
     *
     * <p>When the instance is WAITING on a SIGNAL with an associated timeout timer
     * ({@code wait_aux_id != null}), the timeout timer is cancelled transactionally before
     * the signal transition is applied. This follows the lock-order invariant: the timer row
     * is marked CANCELLED before the instance row is updated.
     *
     * @param id the workflow instance id; must not be null
     * @param signalName the name of the signal to deliver; must not be null
     * @param payload the signal payload; coerced to the declared payload type
     * @param signalDedupKey caller-supplied dedup key; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @Override
    public Future<Void> signal(
            WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey, SqlClient tx) {
        // Claim before load: the workflow_dedup upsert (kind='signal') locks only its own row —
        // (kind, scope, key) is not part of the workflow_timers -> workflow_tasks ->
        // workflow_branch_tokens -> workflow_instances lock-order chain — so claiming first cannot
        // invert that invariant. Race-safe via the dedup repository's atomic upsert: exactly one
        // caller observes inserted=true. The previous findSignal + insertSignal two-step left a
        // snapshot-visibility window where two concurrent callers could both observe an empty dedup
        // row, both apply the signal, and the loser of the workflow_instances optimistic-version race
        // would surface a WorkflowConflictException to a caller that the public contract promises is
        // idempotent. Claiming before the load also spares the loser an unnecessary instance read.
        return dedup.claimOrResolveSignal(id, signalDedupKey, tx).compose(inserted -> {
            if (!inserted) {
                // Prior committed transaction already applied the signal — idempotent no-op. The
                // instance row is never read on this path.
                return Future.<Void>succeededFuture();
            }
            // Non-locking read: optimistic-version check on the subsequent UPDATE catches concurrent
            // writes. Using findByIdForUpdate here would lock workflow_instances before
            // workflow_timers, inverting the lock-order invariant (timers must be locked first) and
            // causing deadlocks under signal-vs-fire / cancel-vs-fire races.
            return instances.findById(id, tx).compose((Optional<WorkflowInstance> optInst) -> {
                if (optInst.isEmpty()) {
                    return Future.failedFuture(new WorkflowInstanceNotFoundException(id));
                }
                WorkflowInstance inst = optInst.get();
                // Binder-row bind (Contract Appendix C2): instance-owned no-branch signal drive. No
                // explicit carrier on this arity — the 8-arg explicit-carrier overload threads one
                // instead.
                return contextBinder.withBound(
                        inst,
                        null,
                        () -> signalHandler.doApplySignal(
                                inst, new SignalDeliveryContext(signalName, payload, signalDedupKey), tx));
            });
        });
    }

    /**
     * Branch-aware signal overload (PRD-WF-002 §D11). When both {@code forkStepId} and
     * {@code branchId} are non-null, the engine routes to the matching branch token and drives
     * its branch via {@link BranchTransitionEngine#driveBranchTransitions}; when both are null
     * this is the existing instance-level signal path; mixed-null is rejected.
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
        if (forkStepId == null && branchId == null) {
            return signal(id, signalName, payload, signalDedupKey, tx);
        }
        if (forkStepId == null || branchId == null) {
            return Future.failedFuture(
                    new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                            + forkStepId + ", branchId=" + branchId));
        }
        if (branchTokens == null || branchEngine == null) {
            return Future.failedFuture(new UnsupportedOperationException(
                    "branch-aware signal requires branch collaborators; engine constructed without them"));
        }
        // Branch dedup is scoped by (workflowId, forkStepId, branchId, dedupKey) — see
        // WorkflowDedupScopes.signalKey. Use the same dedup repository as the instance path.
        String scope = dev.vertique.workflow.dedup.WorkflowDedupScopes.signalScope(id);
        String key = dev.vertique.workflow.dedup.WorkflowDedupScopes.signalKey(signalDedupKey, forkStepId, branchId);
        return dedup.claimOrResolveSignalScoped(scope, key, id, tx).compose(inserted -> {
            if (!inserted) {
                return Future.<Void>succeededFuture();
            }
            return instances.findById(id, tx).compose(optInst -> {
                if (optInst.isEmpty()) {
                    return Future.<Void>failedFuture(new WorkflowInstanceNotFoundException(id));
                }
                WorkflowInstance inst = optInst.get();
                RuntimeWorkflow rw;
                try {
                    rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
                    // Codex review fix H2: every other engine entry-point that resolves rw
                    // also asserts plan-hash drift. Branch signals must do the same so a
                    // redeployed-but-same-version plan doesn't silently run new callbacks
                    // against a persisted instance.
                    requirePlanHashMatches(inst, rw);
                } catch (Exception e) {
                    return Future.<Void>failedFuture(e);
                }
                return branchTokens
                        .findByWaitForBranch(id, forkStepId, branchId, WaitType.SIGNAL.name(), signalName, tx)
                        .compose(opt -> {
                            if (opt.isEmpty()) {
                                return Future.<Void>failedFuture(new WorkflowSignalRejectedException(
                                        "No branch waiting for signal '" + signalName + "' on workflow '"
                                                + id.value() + "' fork '" + forkStepId + "' branch '"
                                                + branchId + "'"));
                            }
                            // Apply the signal payload to the branch-local state slice via the
                            // wait-signal node's stateUpdater callback, then drive the branch
                            // forward (which will trigger evaluateJoin if it terminates).
                            BranchToken waiting = opt.get();
                            return signalHandler.applyBranchSignal(inst, waiting, rw, signalName, payload, tx, null);
                        });
            });
        });
    }

    /**
     * Explicit-carrier signal overload (PRD-WF-007, Contract Appendix C4). Overrides the interface
     * default so a non-null {@code signalMetadata} is honored instead of failing fast.
     *
     * <p>A non-null {@code signalMetadata} requires durable-context propagation to be wired (C4
     * no-silent-drop principle): when {@code propagator} is {@code null} — the same condition under
     * which the {@link WorkflowEngineFactory} assembly seam substitutes
     * {@link WorkflowContextBinder#noop()} for the instance-level binder, and under which
     * {@link BranchTransitionEngine}'s branch-owned bind seam runs unbound — this overload fails
     * fast with an {@link UnsupportedOperationException} instead of silently accepting and then
     * discarding the caller's carrier. This check runs first, before dedup claim or sanitization, so
     * neither path is reached at all: an accepted-but-ignored carrier would otherwise look like a
     * successful delivery to the caller while never being bound anywhere.
     *
     * <p>A non-null {@code signalMetadata} is otherwise first sanitized exactly once via
     * {@link DurableContextPropagator#sanitizeInboundCarrier(DurableMetadata)} (ADR-0147), stripping
     * any namespace whose registered decoder declares itself authenticated-only, before either
     * explicit-carrier path below runs.
     *
     * <p>Instance-level requests ({@code forkStepId} and {@code branchId} both {@code null}) mirror
     * the 5-arg {@link #signal(WorkflowInstanceId, String, Object, String, SqlClient)} path exactly,
     * except the dedup-winner's drive is bound via {@link WorkflowContextBinder#withBound} with the
     * sanitized {@code signalMetadata} threaded through as the explicit carrier (instead of
     * {@code null}). The dedup claim ({@link WorkflowDedupRepository#claimOrResolveSignal}) runs
     * <em>before</em> the bind, exactly as in the 5-arg path — a losing delivery on a duplicate dedup
     * key resolves to a no-op before any carrier is ever bound (FR-WF-CTX-042); its carrier is
     * discarded with it.
     *
     * <p>A {@code null} {@code signalMetadata} delegates to the interface default, which in turn
     * calls the legacy 7-arg {@link #signal(WorkflowInstanceId, String, Object, String, String,
     * String, SqlClient)} overload — behavior unchanged for callers that supply no carrier.
     *
     * <p>Branch-targeted requests ({@code forkStepId} and {@code branchId} both non-null) with a
     * non-null {@code signalMetadata} drive the targeted branch through the
     * {@link BranchTransitionEngine} override seam (Contract Appendix C3): the branch-scoped dedup
     * claim ({@link WorkflowDedupRepository#claimOrResolveSignalScoped}) runs first — mirroring the
     * 7-arg branch-signal path's ordering — so a losing delivery on a duplicate dedup key resolves
     * to a no-op before any carrier is ever bound (FR-WF-CTX-042), discarding the loser's carrier
     * with it. On a claim win, the effective base is computed as
     * {@code signalMetadata.merge(instance.metadata(), MergePolicy.CALLER_WINS)} (or
     * {@code signalMetadata} unchanged when {@code instance.metadata()} is {@code null} — a no-op
     * merge) and passed as {@link SignalHandler#applyBranchSignal}'s {@code effectiveBaseOverride},
     * which threads it into {@code driveBranchTransitions} so the branch's inner bind uses it
     * instead of the token's own {@code metadata()} — the carrier is authoritative over the token
     * metadata unconditionally, with no fallback to the token even when the carrier's namespaces
     * happen to be a subset of the token's.
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
     * @return a {@link Future} that completes when the signal has been applied, or fails with
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
        if (signalMetadata == null) {
            return signal(id, signalName, payload, signalDedupKey, forkStepId, branchId, tx);
        }
        if (propagator == null) {
            // No-silent-drop (C4): an explicit carrier with no durable-context propagation wired has
            // nowhere to be bound — the instance-level binder is WorkflowContextBinder.noop() and the
            // branch-owned bind seam runs unbound (see WorkflowEngine's package-private constructor
            // javadoc). Silently accepting the carrier here would look like a successful delivery to
            // the caller while the carrier is dropped on the floor; fail fast instead, before the
            // dedup claim or sanitization run.
            return Future.failedFuture(new UnsupportedOperationException(
                    "explicit signal carrier requires durable context propagation to be wired"));
        }
        // Sanitization seam (ADR-0147): strip authenticated-only namespaces from the sender-supplied
        // explicit carrier exactly once, here at the dispatcher entry, before either explicit-carrier
        // path (instance-level or branch-targeted) below is reached. Persisted carriers (timer/branch
        // /instance metadata) are framework-captured and are never routed through this seam.
        DurableMetadata sanitizedMetadata = propagator.sanitizeInboundCarrier(signalMetadata);
        if (forkStepId == null && branchId == null) {
            return signalInstanceWithExplicitCarrier(id, signalName, payload, signalDedupKey, sanitizedMetadata, tx);
        }
        if (forkStepId == null || branchId == null) {
            return Future.failedFuture(
                    new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                            + forkStepId + ", branchId=" + branchId));
        }
        if (branchTokens == null || branchEngine == null) {
            return Future.failedFuture(new UnsupportedOperationException(
                    "branch-aware signal requires branch collaborators; engine constructed without them"));
        }
        return signalBranchWithExplicitCarrier(
                id, signalName, payload, signalDedupKey, forkStepId, branchId, sanitizedMetadata, tx);
    }

    /**
     * Instance-level explicit-carrier signal drive (PRD-WF-007, Contract Appendix C4, AC-4
     * instance-level case). Mirrors the 5-arg {@link #signal(WorkflowInstanceId, String, Object,
     * String, SqlClient)} path exactly, except the dedup-winner's drive is bound via
     * {@link WorkflowContextBinder#withBound} with {@code signalMetadata} threaded through as the
     * explicit carrier (instead of {@code null}).
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param signalMetadata the explicit durable-context carrier (validated non-null by the caller)
     * @param tx the active SQL transaction
     * @return a {@link Future} that completes when the signal has been applied
     */
    private Future<Void> signalInstanceWithExplicitCarrier(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            DurableMetadata signalMetadata,
            SqlClient tx) {
        // Dedup claim MUST run before the load/bind (FR-WF-CTX-042): a losing delivery on a
        // duplicate dedup key resolves to a no-op here without ever reading the instance row, so the
        // loser's carrier is discarded with it rather than ever being bound. Claiming first is safe
        // under the lock-order invariant: the workflow_dedup upsert (kind='signal') locks only its own
        // row and is not part of the workflow_timers -> workflow_tasks -> workflow_branch_tokens ->
        // workflow_instances chain — same rationale as the 5-arg signal() overload.
        return dedup.claimOrResolveSignal(id, signalDedupKey, tx).compose(inserted -> {
            if (!inserted) {
                return Future.<Void>succeededFuture();
            }
            // Non-locking read: same lock-order rationale as the 5-arg signal() overload.
            return instances.findById(id, tx).compose((Optional<WorkflowInstance> optInst) -> {
                if (optInst.isEmpty()) {
                    return Future.failedFuture(new WorkflowInstanceNotFoundException(id));
                }
                WorkflowInstance inst = optInst.get();
                return contextBinder.withBound(
                        inst,
                        signalMetadata,
                        () -> signalHandler.doApplySignal(
                                inst, new SignalDeliveryContext(signalName, payload, signalDedupKey), tx));
            });
        });
    }

    /**
     * Branch-targeted explicit-carrier signal drive (PRD-WF-007, Contract Appendix C3, AC-4
     * branch-targeted case). Mirrors the 7-arg branch-signal path's dedup-then-load-then-drive
     * ordering exactly, adding only the effective-base computation and its threading into
     * {@link SignalHandler#applyBranchSignal}'s override parameter.
     *
     * @param id workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param signalDedupKey caller-supplied dedup key
     * @param forkStepId the fork step id (validated non-null by the caller)
     * @param branchId the branch id (validated non-null by the caller)
     * @param signalMetadata the explicit durable-context carrier (validated non-null by the caller)
     * @param tx the active SQL transaction
     * @return a {@link Future} that completes when the branch signal has been applied
     */
    private Future<Void> signalBranchWithExplicitCarrier(
            WorkflowInstanceId id,
            String signalName,
            Object payload,
            String signalDedupKey,
            String forkStepId,
            String branchId,
            DurableMetadata signalMetadata,
            SqlClient tx) {
        // Branch dedup is scoped by (workflowId, forkStepId, branchId, dedupKey) — same encoding as
        // the 7-arg branch-signal path. The claim MUST run before the merge/bind (FR-WF-CTX-042): a
        // losing delivery on a duplicate dedup key resolves to a no-op here, so the loser's carrier
        // never reaches driveBranchTransitions.
        String scope = dev.vertique.workflow.dedup.WorkflowDedupScopes.signalScope(id);
        String key = dev.vertique.workflow.dedup.WorkflowDedupScopes.signalKey(signalDedupKey, forkStepId, branchId);
        return dedup.claimOrResolveSignalScoped(scope, key, id, tx).compose(inserted -> {
            if (!inserted) {
                return Future.<Void>succeededFuture();
            }
            return instances.findById(id, tx).compose(optInst -> {
                if (optInst.isEmpty()) {
                    return Future.<Void>failedFuture(new WorkflowInstanceNotFoundException(id));
                }
                WorkflowInstance inst = optInst.get();
                RuntimeWorkflow rw;
                try {
                    rw = registry.resolvePinned(inst.definitionId(), inst.definitionVersion());
                    requirePlanHashMatches(inst, rw);
                } catch (Exception e) {
                    return Future.<Void>failedFuture(e);
                }
                return branchTokens
                        .findByWaitForBranch(id, forkStepId, branchId, WaitType.SIGNAL.name(), signalName, tx)
                        .compose(opt -> {
                            if (opt.isEmpty()) {
                                return Future.<Void>failedFuture(new WorkflowSignalRejectedException(
                                        "No branch waiting for signal '" + signalName + "' on workflow '"
                                                + id.value() + "' fork '" + forkStepId + "' branch '"
                                                + branchId + "'"));
                            }
                            BranchToken waiting = opt.get();
                            // Carrier-authoritative + instance-fill (Contract Appendix C2/C3): the
                            // explicit carrier wins for any namespace present on both sides; the
                            // instance only fills namespaces the carrier lacks. NULL instance
                            // metadata is a no-op merge — the effective base is the carrier alone,
                            // with no fallback to the branch token's own metadata.
                            DurableMetadata effectiveBase = inst.metadata() == null
                                    ? signalMetadata
                                    : signalMetadata.merge(inst.metadata(), DurableMetadata.MergePolicy.CALLER_WINS);
                            return signalHandler.applyBranchSignal(
                                    inst, waiting, rw, signalName, payload, tx, effectiveBase);
                        });
            });
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Transitions a non-terminal instance to {@code CANCELLED} and appends a history entry.
     * When the instance is WAITING on a timer ({@code wait_type=TIMER}) or a signal with a timeout
     * branch ({@code wait_type=SIGNAL} and {@code wait_aux_id != null}), the associated timer is
     * cancelled first before the instance row is updated.
     *
     * <p>When the instance is WAITING on a human task ({@code wait_type=TASK}), the task row is
     * cancelled via {@link TaskStore#markCancelled} and a {@code TASK_CANCELLED} history entry is
     * appended. If the task also has a due-date timer ({@code wait_aux_id != null}), that timer is
     * cancelled first, preserving the lock-order invariant: timers → tasks → instances.
     *
     * <p>When the instance has active fan-out branch tokens (status {@code WAITING/JOIN}), every
     * active branch's owned timers and tasks are closed via
     * {@link #cancelBranchOwnedWaits(BranchToken, SqlClient)} and each branch token is transitioned
     * to {@code CANCELLED} before the instance row is updated. Full lock order:
     * timers → tasks → branch tokens → instance.
     *
     * @param id the workflow instance id; must not be null
     * @param reason human-readable reason for the cancellation; stored in history
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the cancellation has been persisted
     */
    @Override
    public Future<Void> cancel(WorkflowInstanceId id, String reason, SqlClient tx) {
        // Non-locking read: same lock-order rationale as signal(). The optimistic UPDATE catches
        // concurrent writes; using findByIdForUpdate would invert the timers-before-instances
        // invariant and cause deadlocks under cancel-vs-fire races.
        return instances.findById(id, tx).compose((Optional<WorkflowInstance> optInst) -> {
            if (optInst.isEmpty()) {
                return Future.failedFuture(new WorkflowInstanceNotFoundException(id));
            }
            WorkflowInstance inst = optInst.get();
            if (isTerminal(inst.status())) {
                return Future.failedFuture(new WorkflowSignalRejectedException(
                        "Cannot cancel instance '" + id.value() + "': already in terminal status " + inst.status()));
            }

            // Binder-row bind (Contract Appendix C2): instance-owned cancel drive.
            return contextBinder.withBound(inst, null, () -> doCancel(id, reason, inst, tx));
        });
    }

    /**
     * Executes the cancel drive body after the instance-owned binder-row bind is in effect.
     * Extracted so {@link #cancel(WorkflowInstanceId, String, SqlClient)} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param id     the workflow instance id
     * @param reason human-readable reason for the cancellation
     * @param inst   the loaded (non-terminal) instance snapshot
     * @param tx     the active transaction
     * @return a {@link Future} that completes when the cancellation has been persisted
     */
    private Future<Void> doCancel(WorkflowInstanceId id, String reason, WorkflowInstance inst, SqlClient tx) {
        // Determine whether an active timer must be cancelled alongside the workflow.
        UUID activeTimerId = TimerLifecycleService.resolveActiveTimerId(inst);
        Future<Void> cancelTimerFuture = Future.succeededFuture();
        if (activeTimerId != null) {
            cancelTimerFuture = timerStore
                    .markCancelled(activeTimerId, clock.instant(), tx)
                    .compose(transition -> timerLifecycle.appendTimerCancelledHistory(
                            inst, activeTimerId, TimerCancelledCause.WORKFLOW_CANCELLED, tx));
        }

        // When waiting on a TASK, cancel pending reminder timers (lock order: timers first),
        // then cancel the task row, append TASK_CANCELLED history, and emit TASK_CANCELLED event.
        // Lock order: due-date timer (above) → reminder timers (here) → task → instance (below).
        Future<Void> cancelTaskFuture = cancelTimerFuture;
        if (inst.waitType() == WaitType.TASK) {
            UUID taskId;
            try {
                taskId = UUID.fromString(inst.waitKey());
            } catch (IllegalArgumentException e) {
                return Future.failedFuture(new WorkflowDefinitionException(
                        "Invalid task UUID in wait_key for instance '" + id.value() + "'"));
            }
            WorkflowActor cancelledByActor = new WorkflowActor.System("workflow-cancelled");
            Instant now = clock.instant();
            cancelTaskFuture = cancelTimerFuture
                    .compose(v -> reminderScheduler.cancelPendingReminders(taskId, inst, tx))
                    .compose(v -> taskStore.markCancelled(taskId, reason, now, cancelledByActor, tx))
                    .compose(transition -> taskLifecycle
                            .appendTaskCancelledHistory(inst, taskId, reason, cancelledByActor, now, tx)
                            .compose(taskCancelledSeq -> emitEvent(
                                    inst,
                                    WorkflowEventType.TASK_CANCELLED,
                                    taskCancelledSeq,
                                    taskId,
                                    inst.currentStepId(),
                                    attrs(
                                            "cause",
                                            "WORKFLOW_CANCELLED",
                                            "cancelledBy",
                                            WorkflowActorMaps.toMap(cancelledByActor)),
                                    tx)));
        }

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
                Instant now = clock.instant();
                for (BranchToken branch : activeBranches) {
                    // timers → tasks (cancelBranchOwnedWaits), then branch token.
                    final BranchToken b = branch;
                    BranchToken cancelledBranch = b.withClearedWait(BranchStatus.CANCELLED, b.currentStepId(), now);
                    branchChain = branchChain
                            .compose(v2 -> cancelBranchOwnedWaits(b, tx))
                            .compose(v2 -> branchTokens
                                    .updateOptimistic(cancelledBranch, b.version(), tx)
                                    .mapEmpty());
                }
                return branchChain;
            });
        });

        return cancelBranchesFuture.compose(v -> {
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
                        String histPayload = Json.encode(
                                new CancelledHistoryPayload(reason, WorkflowPayloads.commandCorrelationId()));
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
     * {@inheritDoc}
     *
     * <p>Resets a {@code FAILED} instance to {@code RUNNING} and re-drives transitions from the
     * current step.
     *
     * @param id the workflow instance id; must not be null
     * @param tx the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the retry attempt has been initiated
     */
    @Override
    public Future<Void> retry(WorkflowInstanceId id, SqlClient tx) {
        // retry() keeps findByIdForUpdate (unlike signal() / cancel() which switched to non-locking
        // findById to honor the workflow_timers-before-workflow_instances lock-order invariant).
        // retry() does not touch workflow_timers — a retry only fires for FAILED instances, which
        // are terminal w.r.t. timer firing — so the lock-order constraint does not apply.
        return instances.findByIdForUpdate(id, tx).compose((Optional<WorkflowInstance> optInst) -> {
            if (optInst.isEmpty()) {
                return Future.failedFuture(new WorkflowInstanceNotFoundException(id));
            }
            WorkflowInstance inst = optInst.get();
            if (inst.status() != WorkflowStatus.FAILED) {
                return Future.failedFuture(new WorkflowSignalRejectedException("Cannot retry instance '" + id.value()
                        + "': status is " + inst.status() + " (must be FAILED)"));
            }

            // Binder-row bind (Contract Appendix C2): instance-owned retry drive.
            return contextBinder.withBound(inst, null, () -> doRetry(id, inst, tx));
        });
    }

    /**
     * Executes the retry drive body after the instance-owned binder-row bind is in effect.
     * Extracted so {@link #retry(WorkflowInstanceId, SqlClient)} can pass it to
     * {@link WorkflowContextBinder#withBound} as a {@link java.util.function.Supplier}.
     *
     * @param id   the workflow instance id
     * @param inst the loaded (FAILED-status) instance snapshot
     * @param tx   the active transaction
     * @return a {@link Future} that completes when the retry attempt has been initiated
     */
    private Future<Void> doRetry(WorkflowInstanceId id, WorkflowInstance inst, SqlClient tx) {
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
            requirePlanHashMatches(inst, rw);
        } catch (WorkflowPlanHashDriftException e) {
            return Future.failedFuture(e);
        }

        long prevVersion = inst.version();
        WorkflowInstance retried = inst.withVersion(prevVersion + 1)
                .withStatus(WorkflowStatus.RUNNING)
                .withWait(null, null)
                .withError(null, null)
                .withUpdatedAt(clock.instant());

        return instances
                .updateOptimistic(retried, prevVersion, tx)
                .compose(rowCount -> requireRowUpdated(rowCount, "instance '" + id.value() + "' during retry"))
                .compose(v -> history.nextSequence(id, tx))
                .compose(seq -> {
                    String histPayload =
                            Json.encode(new RetriedHistoryPayload("FAILED", WorkflowPayloads.commandCorrelationId()));
                    return history.append(
                            new WorkflowHistoryEntry(id, seq, WorkflowEntryType.RETRIED, histPayload, clock.instant()),
                            tx);
                })
                .compose(v -> driver.driveTransitions(retried, rw, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a new database transaction, then delegates to
     * {@link #migrate(WorkflowInstanceId, long, SqlClient)}.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetVersion the definition version to migrate to
     * @return a {@link Future} that completes when the instance has been re-pinned and advanced
     */
    @Override
    public Future<Void> migrate(WorkflowInstanceId id, long targetVersion) {
        return txRunner.inTransaction(null, tx -> migrate(id, targetVersion, tx));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates the migration algorithm to {@link MigrationExecutor#migrate}. The executor owns
     * the opt-in {@link dev.vertique.workflow.migration.WorkflowMigrationRegistry} and the
     * null-registry {@link UnsupportedOperationException} fail-fast guard: if no registry was
     * provided (the migration module was not installed), the call fails immediately before any
     * database work.
     *
     * @param id            the workflow instance id; must not be null
     * @param targetVersion the definition version to migrate to
     * @param tx            the active SQL transaction; must not be null
     * @return a {@link Future} that completes when the instance has been re-pinned and advanced
     */
    @Override
    public Future<Void> migrate(WorkflowInstanceId id, long targetVersion, SqlClient tx) {
        return migrationExecutor.migrate(id, targetVersion, tx);
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
    @Override
    public Future<TaskMutationResult> taskCompleted(TaskCompletionCommand cmd, SqlClient tx) {
        return taskLifecycle.taskCompleted(cmd, tx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Dispatched by {@link #timerFired} when a timer matches the {@code wait_type=TASK} +
     * {@code wait_aux_id=timerId} slot. Steps:
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
    @Override
    public Future<TaskMutationResult> taskDueFired(WorkflowInstanceId workflowId, UUID taskId, SqlClient tx) {
        return taskLifecycle.taskDueFired(workflowId, taskId, tx);
    }

    /**
     * {@inheritDoc}
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
    @Override
    public Future<TaskMutationResult> taskReassigned(TaskReassignmentCommand cmd, SqlClient tx) {
        return taskLifecycle.taskReassigned(cmd, tx);
    }

    /**
     * {@inheritDoc}
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
     * @param tx         the active transaction context
     * @return succeeded future on both applied and no-op paths; failed future on transient errors
     */
    @Override
    public Future<Void> taskReminderFired(
            WorkflowInstanceId workflowId, UUID taskId, UUID timerId, Instant scheduledFireAt, SqlClient tx) {
        return taskLifecycle.taskReminderFired(workflowId, taskId, timerId, scheduledFireAt, tx);
    }

    // --- TransactionalTimerCallbacks ---

    /**
     * {@inheritDoc}
     *
     * <p>Reads the instance without locking (no FOR UPDATE), then classifies the wait slot:
     * <ul>
     *   <li>{@code wait_type=TIMER, wait_key=timerId} — standalone-timer path: advances to
     *       {@code tn.nextStepId()} and appends {@code TIMER_FIRED}.</li>
     *   <li>{@code wait_type=SIGNAL, wait_aux_id=timerId} — signal-timeout path: applies the
     *       {@code onTimeout} mutator, advances to {@code timeoutNextStepId}, and appends
     *       {@code TIMEOUT}.</li>
     *   <li>Otherwise — {@link TimerFiringResult#STALE_NOOP}.</li>
     * </ul>
     *
     * @param workflowId the workflow instance this timer belongs to
     * @param timerId the stable UUID of the timer that fired
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the firing result
     */
    @Override
    public Future<TimerFiringResult> timerFired(WorkflowInstanceId workflowId, UUID timerId, SqlClient tx) {
        return timerLifecycle.timerFired(workflowId, timerId, tx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock-order: acquires the {@code workflow_timers} row lock first via
     * {@link TimerStore#lockForFiring}, then reads the instance. On slot match, marks the timer
     * {@code FAILED}, appends {@code TIMER_FAILED} history, and transitions the
     * workflow to {@code FAILED}. On slot mismatch or non-WAITING instance, marks the timer
     * {@code FAILED} with {@code "TIMER_INCONSISTENCY: ..."} and returns
     * {@link TimerFiringResult#STALE_NOOP} without mutating the workflow instance.
     *
     * @param workflowId the workflow instance this timer belongs to
     * @param timerId the stable UUID of the timer that failed to fire
     * @param errorType the error category reported by the delayed job executor
     * @param errorMessage human-readable description of the failure cause
     * @param tx the active transaction context; all writes must use this context
     * @return a {@link Future} resolving to the firing result
     */
    @Override
    public Future<TimerFiringResult> timerFiringFailed(
            WorkflowInstanceId workflowId, UUID timerId, String errorType, String errorMessage, SqlClient tx) {
        return timerLifecycle.timerFiringFailed(workflowId, timerId, errorType, errorMessage, tx);
    }

    // --- Internal helpers ---

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
     * Coerces {@code payload} to {@code targetType} using {@code Json.encode → Json.decodeValue}
     * when the payload is not already an instance of the target type.
     *
     * <p>Delegates to {@link WorkflowPayloads#coercePayload}.
     *
     * @param payload the incoming payload; may be null
     * @param targetType the target class to coerce to; if null, returns payload unchanged
     * @return the coerced payload, or the original payload if no coercion is needed
     */
    private static Object coercePayload(Object payload, Class<?> targetType) {
        return WorkflowPayloads.coercePayload(payload, targetType);
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
     * Returns {@code true} if the given status is a terminal state (no further transitions
     * possible).
     *
     * @param status the status to check
     * @return {@code true} for terminal statuses
     */
    private static boolean isTerminal(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, COMPENSATED, CANCELLED, EXPIRED -> true;
            default -> false;
        };
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
     * {@code WorkflowTimerFireExecutor} path — which holds a {@code workflow_timers} lock before
     * calling back into the engine — cannot race the branch-token update and deadlock.
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
