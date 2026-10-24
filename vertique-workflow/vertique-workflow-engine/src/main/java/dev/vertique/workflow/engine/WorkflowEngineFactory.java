// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.workflow.engine.executor.DefaultWorkflowExecutionProbe;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.util.Set;

/**
 * Public assembly seam for the portable workflow engine — the non-Dagger parallel to the wiring
 * {@link WorkflowEngineModule} performs at runtime.
 *
 * <p>{@link #create} accepts the engine's externally-supplied dependencies as one
 * {@link WorkflowEngineParts} (the repository and transaction-runner SPIs, the task/timer stores, the workflow
 * registry, the durable definition store, a {@link Clock}, the side-effect recorders and the other optional parts)
 * and constructs the entire package-private collaborator graph internally — {@link RecorderRouter},
 * {@link FingerprintCanonicalizer}, the leaf
 * history/event/reminder/compensation collaborators, the {@link BranchTransitionEngine}, and finally the
 * {@link WorkflowEngine} itself (which in turn wires the {@code WorkflowTransitionDriver} ↔
 * {@link ForkJoinCoordinator} mutual-recursion cycle by hand). It returns the assembled engine as a
 * {@link WorkflowEngineHandle} so callers outside this package never touch the package-private concrete type.
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
     * {@link WorkflowReminderComposeValidator} enforces the reminder-requires-events rule on each plan
     * the registry builds, for apps that do configure reminders).
     */
    public static final Set<IntentKind> DEFAULT_OPTIONAL_INTENT_KINDS = Set.of(IntentKind.WORKFLOW_EVENT);

    private WorkflowEngineFactory() {
        // static factory holder
    }

    /**
     * Assembles a fully wired workflow engine over the supplied parts.
     *
     * <p>The engine shares one payload constraint check between start payloads and decision payloads, and one
     * {@link dev.vertique.context.InboundExecutionContextScope} between binder-row drives (signal, cancel, retry,
     * migrate, task and timer lifecycle callbacks) and branch-owned drives (branch-targeted signals, fork
     * continuations, recovery) when the parts carry a propagator, so every registered initializer runs identically on
     * both drive shapes, matching production Dagger wiring. Without a propagator no scope is built and the drives
     * run without initializers.
     *
     * <p>The engine this method assembles runs no startup step, so the activation records that
     * {@link WorkflowEngineParts#definitionStore()} holds when this method is called seed the view that a start
     * without a version reads. Records written to the store later reach the engine only through its lifecycle.
     *
     * @param parts the engine's externally supplied dependencies, including the durable definition store
     * @return the assembled engine as a {@link WorkflowEngineHandle}
     */
    public static WorkflowEngineHandle create(WorkflowEngineParts parts) {
        WorkflowTransactionRunner<SqlClient> txRunner = parts.txRunner();
        WorkflowRegistry registry = parts.registry();
        WorkflowInstanceRepository<SqlClient> instances = parts.instances();
        WorkflowHistoryRepository<SqlClient> history = parts.history();
        WorkflowDedupRepository<SqlClient> dedup = parts.dedup();
        BranchTokenRepository<SqlClient> branchTokens = parts.branchTokens();
        JoinStateRepository<SqlClient> joinStates = parts.joinStates();
        TimerStore<SqlClient> timerStore = parts.timerStore();
        TaskStore<SqlClient> taskStore = parts.taskStore();
        Clock clock = parts.clock();
        DurableContextPropagator propagator = parts.propagator();
        WorkflowExecutionProbe executionProbe = new DefaultWorkflowExecutionProbe(parts.commands());
        PayloadConstraintCheck payloadConstraintCheck = new PayloadConstraintCheck(parts.beanValidator());
        RecorderRouter recorderRouter = new RecorderRouter(parts.recorders(), parts.optionalIntentKinds());
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
                        new dev.vertique.context.InboundDispatchScope(),
                        propagator,
                        parts.inboundContextInitializers());
        BranchTransitionEngine branchEngine = inboundExecScope == null
                ? new BranchTransitionEngine(branchTokens, dedup, history, recorderRouter, taskStore, clock, propagator)
                : new BranchTransitionEngine(
                        branchTokens, dedup, history, recorderRouter, taskStore, clock, propagator, inboundExecScope);
        BranchCompensationOrchestrator branchCompensation =
                new BranchCompensationOrchestrator(history, recorderRouter, clock);
        // A factory-built engine runs no startup step, so the view of the activation records is seeded from the
        // records the store holds now; a store that answers later seeds it when it does.
        DefinitionActivations activations = new DefinitionActivations(parts.definitionStore());
        parts.definitionStore().listActivations(null).onSuccess(records -> records.forEach(activations::put));
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
                parts.migrationRegistry(),
                eventEmitter,
                historyRecorder,
                reminderScheduler,
                compensationService,
                inboundExecScope,
                payloadConstraintCheck,
                parts.typeNames(),
                activations,
                parts.stateCompatibility(),
                new FailingDefinitions(),
                executionProbe);
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
}
