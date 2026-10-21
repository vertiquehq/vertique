// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * Public assembly seam for the portable workflow engine — the non-Dagger parallel to the wiring
 * {@link WorkflowEngineModule} performs at runtime.
 *
 * <p>{@link #create} accepts the engine's externally-supplied dependencies (the repository and
 * transaction-runner SPIs, the task/timer stores, the workflow registry, a {@link Clock}, the set
 * of side-effect recorders, the set of optional intent kinds, an optional migration registry, and
 * an optional durable-context propagator) and constructs the entire package-private collaborator
 * graph internally — {@link RecorderRouter}, {@link WorkflowReminderComposeValidator},
 * {@link FingerprintCanonicalizer}, the leaf history/event/reminder/compensation collaborators, the
 * {@link BranchTransitionEngine}, and finally the {@link WorkflowEngine} itself (which in turn wires
 * the {@code WorkflowTransitionDriver} ↔ {@link ForkJoinCoordinator} mutual-recursion cycle by
 * hand). It returns the assembled engine as a {@link WorkflowEngineHandle} so callers outside this
 * package never touch the package-private concrete type.
 *
 * <p>This is the construction knowledge dialect/test code previously hand-wired (e.g. a
 * dialect adapter's test support); relocating it here keeps that knowledge in one
 * place and lets dialect modules assemble an engine over their own SPI implementations without
 * depending on Dagger. Production composition uses {@link WorkflowEngineModule}; this factory and
 * that module share the engine-owned {@link #DEFAULT_OPTIONAL_INTENT_KINDS} default so the two paths
 * cannot drift.
 */
public final class WorkflowEngineFactory {

    /**
     * The default set of {@link IntentKind} values the engine treats as optional — the single
     * source of truth shared by {@link WorkflowEngineModule}'s {@code @OptionalIntentKinds}
     * {@code @Provides} and by dialect/test callers of {@link #create}.
     *
     * <p>{@link IntentKind#WORKFLOW_EVENT} is optional so that apps without a workflow-events module
     * can still run as long as no registered plan declares reminders (the
     * {@link WorkflowReminderComposeValidator} enforces the reminder-requires-events rule at startup
     * for apps that do configure reminders).
     */
    public static final Set<IntentKind> DEFAULT_OPTIONAL_INTENT_KINDS = Set.of(IntentKind.WORKFLOW_EVENT);

    private WorkflowEngineFactory() {
        // static factory holder
    }

    /**
     * Assembles a fully wired workflow engine over the supplied dependencies.
     *
     * @param txRunner the transaction runner SPI that owns the transaction boundary and the layered
     *     DB-to-workflow exception mapping
     * @param registry the workflow registry used to resolve pinned runtime workflows
     * @param instances the instance repository SPI
     * @param history the history repository SPI
     * @param dedup the dedup repository SPI
     * @param branchTokens the branch-token repository SPI
     * @param joinStates the join-state repository SPI
     * @param timerStore the timer store
     * @param taskStore the task store
     * @param clock the clock used to timestamp entries and state transitions
     * @param recorders the set of side-effect recorders contributed by the application
     * @param optionalIntentKinds the set of intent kinds the recorder router treats as optional
     *     (use {@link #DEFAULT_OPTIONAL_INTENT_KINDS} for the engine default)
     * @param migrationRegistryOpt the optional migration registry; when empty, {@code migrate} fails
     *     with {@link UnsupportedOperationException}
     * @param propagator the durable-context propagator used to capture branch metadata; may be
     *     {@code null} for callers that do not exercise the branch durable-capture path
     * @return the assembled engine as a {@link WorkflowEngineHandle}
     */
    public static WorkflowEngineHandle create(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            Optional<WorkflowMigrationRegistry> migrationRegistryOpt,
            @Nullable DurableContextPropagator propagator) {
        return create(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                clock,
                recorders,
                optionalIntentKinds,
                migrationRegistryOpt,
                propagator,
                Set.of());
    }

    /**
     * Assembles a fully wired workflow engine over the supplied dependencies, additionally
     * threading a set of {@link InboundContextInitializer}s into the engine's shared
     * {@link dev.vertique.context.InboundExecutionContextScope} (factory initializer parity).
     *
     * <p>{@link #create(WorkflowTransactionRunner, WorkflowRegistry, WorkflowInstanceRepository,
     * WorkflowHistoryRepository, WorkflowDedupRepository, BranchTokenRepository, JoinStateRepository,
     * TimerStore, TaskStore, Clock, Set, Set, Optional, DurableContextPropagator) the no-initializer
     * overload} delegates here with an empty initializer set and assembles the engine
     * <b>without</b> inbound initializers — correlation seeding and any other registered
     * {@link InboundContextInitializer} are absent from both a binder-row bind and a branch-owned
     * drive, unlike production Dagger wiring ({@link WorkflowEngineModule}), which runs every
     * registered initializer via the injected {@link dev.vertique.context.InboundExecutionContextScope}.
     * Callers that need parity with production initializer behavior (e.g. dialect/test harnesses
     * exercising correlation seeding) should call this overload directly with the same initializer
     * set the application registers via Dagger multibindings.
     *
     * <p>This overload constructs exactly one
     * {@link dev.vertique.context.InboundExecutionContextScope} from {@code propagator} and
     * {@code inboundContextInitializers} (when {@code propagator} is non-{@code null}) and threads
     * that same instance into both the binder-row {@link WorkflowContextBinder} (instance-owned
     * single-path drives: signal, cancel, retry, migrate, task/timer lifecycle callbacks) and
     * {@link BranchTransitionEngine} (branch-owned drives: branch-targeted signals, fork
     * continuations, recovery via the bridge) — so every registered initializer runs identically on
     * both drive shapes, matching production Dagger wiring where both collaborators receive the same
     * injected singleton scope.
     *
     * @param txRunner the transaction runner SPI that owns the transaction boundary and the layered
     *     DB-to-workflow exception mapping
     * @param registry the workflow registry used to resolve pinned runtime workflows
     * @param instances the instance repository SPI
     * @param history the history repository SPI
     * @param dedup the dedup repository SPI
     * @param branchTokens the branch-token repository SPI
     * @param joinStates the join-state repository SPI
     * @param timerStore the timer store
     * @param taskStore the task store
     * @param clock the clock used to timestamp entries and state transitions
     * @param recorders the set of side-effect recorders contributed by the application
     * @param optionalIntentKinds the set of intent kinds the recorder router treats as optional
     *     (use {@link #DEFAULT_OPTIONAL_INTENT_KINDS} for the engine default)
     * @param migrationRegistryOpt the optional migration registry; when empty, {@code migrate} fails
     *     with {@link UnsupportedOperationException}
     * @param propagator the durable-context propagator used to capture branch metadata; may be
     *     {@code null} for callers that do not exercise the branch durable-capture path
     * @param inboundContextInitializers the set of {@link InboundContextInitializer}s to run on
     *     every bound drive (binder-row and branch-owned), mirroring the Dagger-registered
     *     {@code Set<InboundContextInitializer>} multibinding
     * @return the assembled engine as a {@link WorkflowEngineHandle}
     */
    public static WorkflowEngineHandle create(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            Optional<WorkflowMigrationRegistry> migrationRegistryOpt,
            @Nullable DurableContextPropagator propagator,
            Set<InboundContextInitializer> inboundContextInitializers) {
        return create(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                clock,
                recorders,
                optionalIntentKinds,
                migrationRegistryOpt,
                propagator,
                inboundContextInitializers,
                Optional.empty());
    }

    /**
     * Assembles a fully wired workflow engine over the supplied dependencies, additionally
     * threading an optional {@link BeanValidator} that switches on constraint enforcement for
     * coerced decision and start payloads.
     *
     * <p>The {@link #create(WorkflowTransactionRunner, WorkflowRegistry, WorkflowInstanceRepository,
     * WorkflowHistoryRepository, WorkflowDedupRepository, BranchTokenRepository, JoinStateRepository,
     * TimerStore, TaskStore, Clock, Set, Set, Optional, DurableContextPropagator, Set)
     * initializer overload} delegates here with an empty validator, which leaves payloads
     * unchecked beyond coercion. This overload builds exactly one payload constraint check and
     * threads that same instance into both the engine (start payloads) and the task lifecycle
     * service (decision payloads), matching production Dagger wiring where
     * {@link WorkflowEngineModule} declares the validator as an optional binding.
     *
     * <p>The engine shares the type-name index of {@code registry} when it is a
     * {@code DefaultWorkflowRegistry}; for any other registry it starts with an empty index, so pass
     * the index explicitly through the overload that takes a {@link WorkflowTypeNames}.
     *
     * @param txRunner the transaction runner SPI that owns the transaction boundary and the layered
     *     DB-to-workflow exception mapping
     * @param registry the workflow registry used to resolve pinned runtime workflows
     * @param instances the instance repository SPI
     * @param history the history repository SPI
     * @param dedup the dedup repository SPI
     * @param branchTokens the branch-token repository SPI
     * @param joinStates the join-state repository SPI
     * @param timerStore the timer store
     * @param taskStore the task store
     * @param clock the clock used to timestamp entries and state transitions
     * @param recorders the set of side-effect recorders contributed by the application
     * @param optionalIntentKinds the set of intent kinds the recorder router treats as optional
     *     (use {@link #DEFAULT_OPTIONAL_INTENT_KINDS} for the engine default)
     * @param migrationRegistryOpt the optional migration registry; when empty, {@code migrate} fails
     *     with {@link UnsupportedOperationException}
     * @param propagator the durable-context propagator used to capture branch metadata; may be
     *     {@code null} for callers that do not exercise the branch durable-capture path
     * @param inboundContextInitializers the set of {@link InboundContextInitializer}s to run on
     *     every bound drive (binder-row and branch-owned)
     * @param beanValidator the bean validator that checks coerced decision and start payloads; when
     *     empty, payload constraints are not enforced
     * @return the assembled engine as a {@link WorkflowEngineHandle}
     */
    public static WorkflowEngineHandle create(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            Optional<WorkflowMigrationRegistry> migrationRegistryOpt,
            @Nullable DurableContextPropagator propagator,
            Set<InboundContextInitializer> inboundContextInitializers,
            Optional<BeanValidator> beanValidator) {
        return create(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                clock,
                recorders,
                optionalIntentKinds,
                migrationRegistryOpt,
                propagator,
                inboundContextInitializers,
                beanValidator,
                registryTypeNames(registry));
    }

    /**
     * Returns the type-name index of a {@link DefaultWorkflowRegistry}, and an empty index for any
     * other registry, which a wrapped registry cannot share. Pass the index explicitly through the
     * overload that takes a {@link WorkflowTypeNames} when the registry is not a default registry.
     */
    static WorkflowTypeNames registryTypeNames(WorkflowRegistry registry) {
        return registry instanceof DefaultWorkflowRegistry defaultRegistry
                ? defaultRegistry.typeNames()
                : new DefaultWorkflowTypeNames();
    }

    /**
     * Assembles a fully wired workflow engine over the supplied dependencies, additionally
     * threading an optional {@link BeanValidator} that switches on constraint enforcement for
     * coerced decision and start payloads.
     *
     * <p>This overload takes the type-name index explicitly, so an engine over a registry that wraps or
     * decorates the default registry shares the index the wrapped registry fills. The overload without
     * the index uses the index of the registry when it is a {@code DefaultWorkflowRegistry} and an
     * empty one otherwise; with an empty index a decision payload type that is not a binary class name
     * cannot be resolved.
     *
     * <p>The {@link #create(WorkflowTransactionRunner, WorkflowRegistry, WorkflowInstanceRepository,
     * WorkflowHistoryRepository, WorkflowDedupRepository, BranchTokenRepository, JoinStateRepository,
     * TimerStore, TaskStore, Clock, Set, Set, Optional, DurableContextPropagator, Set)
     * initializer overload} delegates here with an empty validator, which leaves payloads
     * unchecked beyond coercion. This overload builds exactly one payload constraint check and
     * threads that same instance into both the engine (start payloads) and the task lifecycle
     * service (decision payloads), matching production Dagger wiring where
     * {@link WorkflowEngineModule} declares the validator as an optional binding.
     *
     * @param txRunner the transaction runner SPI that owns the transaction boundary and the layered
     *     DB-to-workflow exception mapping
     * @param registry the workflow registry used to resolve pinned runtime workflows
     * @param instances the instance repository SPI
     * @param history the history repository SPI
     * @param dedup the dedup repository SPI
     * @param branchTokens the branch-token repository SPI
     * @param joinStates the join-state repository SPI
     * @param timerStore the timer store
     * @param taskStore the task store
     * @param clock the clock used to timestamp entries and state transitions
     * @param recorders the set of side-effect recorders contributed by the application
     * @param optionalIntentKinds the set of intent kinds the recorder router treats as optional
     *     (use {@link #DEFAULT_OPTIONAL_INTENT_KINDS} for the engine default)
     * @param migrationRegistryOpt the optional migration registry; when empty, {@code migrate} fails
     *     with {@link UnsupportedOperationException}
     * @param propagator the durable-context propagator used to capture branch metadata; may be
     *     {@code null} for callers that do not exercise the branch durable-capture path
     * @param inboundContextInitializers the set of {@link InboundContextInitializer}s to run on
     *     every bound drive (binder-row and branch-owned)
     * @param beanValidator the bean validator that checks coerced decision and start payloads; when
     *     empty, payload constraints are not enforced
     * @param typeNames the type-name index the engine resolves a decision payload's declared type name
     *     through; pass the one index the registry fills, which is what Dagger wiring injects
     * @return the assembled engine as a {@link WorkflowEngineHandle}
     */
    public static WorkflowEngineHandle create(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            Clock clock,
            Set<WorkflowSideEffectRecorder<SqlClient>> recorders,
            Set<IntentKind> optionalIntentKinds,
            Optional<WorkflowMigrationRegistry> migrationRegistryOpt,
            @Nullable DurableContextPropagator propagator,
            Set<InboundContextInitializer> inboundContextInitializers,
            Optional<BeanValidator> beanValidator,
            WorkflowTypeNames typeNames) {
        PayloadConstraintCheck payloadConstraintCheck = new PayloadConstraintCheck(beanValidator);
        WorkflowReminderComposeValidator complianceCheck = new WorkflowReminderComposeValidator(registry, recorders);
        RecorderRouter recorderRouter = new RecorderRouter(recorders, optionalIntentKinds, complianceCheck);
        FingerprintCanonicalizer fingerprintCanonicalizer = new FingerprintCanonicalizer();
        WorkflowEventEmitter eventEmitter = new WorkflowEventEmitter(recorderRouter, clock);
        WorkflowHistoryRecorder historyRecorder = new WorkflowHistoryRecorder(history, eventEmitter, clock);
        ReminderScheduler reminderScheduler = new ReminderScheduler(history, recorderRouter, timerStore, clock);
        CompensationService compensationService =
                new CompensationService(instances, history, recorderRouter, eventEmitter, clock);
        // Build the substrate lifecycle helper ONCE from the (possibly-null) propagator and the
        // supplied initializer set, and thread the same instance into BranchTransitionEngine so
        // binder-row drives (WorkflowContextBinder, wired inside the WorkflowEngine constructor
        // below) and branch-owned drives (BranchTransitionEngine.driveBranchTransitions) share
        // identical scope/initializer wiring in factory assembly — factory initializer parity now
        // covers branch drives too, not just binder rows (review finding: the two-constructor split
        // previously left branchEngine's inboundExecScope null, silently dropping registered
        // InboundContextInitializers — e.g. correlation seeding — from every factory-assembled
        // branch drive). A null propagator preserves legacy behavior exactly: no scope is built and
        // BranchTransitionEngine falls back to its no-initializer constructor (plain
        // propagator.bindFrom path, itself skipped when propagator is null).
        dev.vertique.context.InboundExecutionContextScope inboundExecScope = propagator == null
                ? null
                : new dev.vertique.context.InboundExecutionContextScope(
                        new dev.vertique.context.InboundDispatchScope(), propagator, inboundContextInitializers);
        BranchTransitionEngine branchEngine = inboundExecScope == null
                ? new BranchTransitionEngine(branchTokens, dedup, history, recorderRouter, taskStore, clock, propagator)
                : new BranchTransitionEngine(
                        branchTokens, dedup, history, recorderRouter, taskStore, clock, propagator, inboundExecScope);
        BranchCompensationOrchestrator branchCompensation =
                new BranchCompensationOrchestrator(history, recorderRouter, clock);
        return new WorkflowEngine(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                recorderRouter,
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
                typeNames);
    }
}
