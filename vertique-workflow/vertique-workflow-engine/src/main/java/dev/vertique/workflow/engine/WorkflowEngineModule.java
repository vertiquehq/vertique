// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.engine.executor.DefaultWorkflowExecutionProbe;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module for the portable workflow engine.
 *
 * <p>This module owns the engine-internal bindings that were previously declared by the dialect
 * module: it binds the four public ops/callback interfaces to the package-private
 * {@link WorkflowEngine}, binds the {@link WorkflowRecoveryBridge} SPI to its package-private
 * implementation, declares the {@code @WorkflowRecorders} multibinding, provides the engine-owned
 * default set of optional intent kinds, and declares the optional {@link WorkflowMigrationRegistry}
 * and {@link BeanValidator} bindings. Dialect modules {@code include} this module so they only
 * contribute the dialect-specific repository / store / runner bindings.
 *
 * <p>The module is {@code public} so a dialect module in another package can {@code include} it;
 * {@link WorkflowEngine} and the collaborators it binds stay package-private (Dagger generates the
 * binding code inside this package, where it can reach them).
 *
 * <p>It {@code include}s {@link WorkflowCoreModule} and {@link ContextRuntimeModule} so the engine's
 * core and durable-context collaborators are available; logging-context wiring is left to the
 * dialect module.
 */
@Module(includes = {WorkflowCoreModule.class, ContextRuntimeModule.class})
public abstract class WorkflowEngineModule {

    /**
     * Binds {@link WorkflowEngine} as the {@link WorkflowOperations} implementation.
     *
     * @param impl the engine singleton
     * @return the engine as the public operations interface
     */
    @Binds
    @Singleton
    abstract WorkflowOperations bindOps(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowEngine} as the {@link TransactionalWorkflowOperations} implementation for
     * the {@link SqlClient}-typed stack.
     *
     * @param impl the engine singleton
     * @return the engine as the transactional operations interface
     */
    @Binds
    @Singleton
    abstract TransactionalWorkflowOperations<SqlClient> bindTxOps(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowEngine} as the {@link TransactionalTimerCallbacks} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the engine singleton
     * @return the engine as the timer callbacks interface
     */
    @Binds
    @Singleton
    abstract TransactionalTimerCallbacks<SqlClient> bindTimerCallbacks(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowEngine} as the {@link TransactionalTaskCallbacks} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the engine singleton
     * @return the engine as the task callbacks interface
     */
    @Binds
    @Singleton
    abstract TransactionalTaskCallbacks<SqlClient> bindTaskCallbacks(WorkflowEngine impl);

    /**
     * Provides the execution probe over the command repository, pacing its polling with timers of the application's
     * Vert.x instance when the graph binds one and with a shared daemon thread otherwise.
     *
     * @param commands the command repository
     * @param vertx    the optional Vert.x instance
     * @param config   the executor configuration, or empty for the defaults; its stuck age applies to the report
     * @return the probe
     */
    @Provides
    @Singleton
    static WorkflowExecutionProbe executionProbe(
            WorkflowCommandRepository<SqlClient> commands,
            Optional<Vertx> vertx,
            Optional<WorkflowExecutorConfig> config) {
        long stuckAfterSeconds =
                config.orElseGet(WorkflowExecutorConfig::defaults).stuckAfterSeconds();
        return vertx.map(instance -> new DefaultWorkflowExecutionProbe(commands, instance, stuckAfterSeconds))
                .orElseGet(() -> new DefaultWorkflowExecutionProbe(commands, stuckAfterSeconds));
    }

    /**
     * Provides the engine's side of a segment for the executor.
     *
     * @param engine the engine, resolved lazily because the engine reaches the executor control through this graph
     * @return the segments seam
     */
    @Provides
    @Singleton
    static WorkflowSegments segments(Provider<WorkflowEngine> engine) {
        return new WorkflowSegments() {
            @Override
            public boolean makeResident(dev.vertique.workflow.engine.spi.CommandHead head) {
                return engine.get().segments().makeResident(head);
            }

            @Override
            public io.vertx.core.Future<Outcome> run(
                    dev.vertique.workflow.engine.spi.CommandHead head,
                    dev.vertique.workflow.engine.spi.ClaimedCommand claim,
                    dev.vertique.workflow.engine.spi.SegmentCommand command,
                    SqlClient tx) {
                return engine.get().segments().run(head, claim, command, tx);
            }

            @Override
            public io.vertx.core.Future<Outcome> run(
                    dev.vertique.workflow.engine.spi.CommandHead head,
                    dev.vertique.workflow.engine.spi.ClaimedCommand claim,
                    dev.vertique.workflow.engine.spi.SegmentCommand command,
                    SqlClient tx,
                    int maxSteps) {
                return engine.get().segments().run(head, claim, command, tx, maxSteps);
            }

            @Override
            public io.vertx.core.Future<Void> instanceFailed(
                    dev.vertique.workflow.ops.WorkflowInstanceId instance,
                    long historySequence,
                    String reasonCode,
                    String message,
                    SqlClient tx) {
                return engine.get().segments().instanceFailed(instance, historySequence, reasonCode, message, tx);
            }
        };
    }

    /**
     * Provides the executor: the description of the {@code workflow-executor} deployment, and the control that
     * deploys it for an owner that has no deployer.
     *
     * @param config   the executor configuration, or empty for the defaults
     * @param vertx    the optional Vert.x instance
     * @param commands the command repository
     * @param runner   the transaction runner segments run through
     * @param segments the engine's side of a segment
     * @param observers the observers of the executor's events; empty unless the application contributes some
     * @return the executor
     */
    @Provides
    @Singleton
    static WorkflowExecutor executor(
            Optional<WorkflowExecutorConfig> config,
            Optional<Vertx> vertx,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowTransactionRunner<SqlClient> runner,
            WorkflowSegments segments,
            Set<WorkflowExecutorObserver> observers) {
        return new WorkflowExecutor(
                vertx.orElse(null),
                config.orElseGet(WorkflowExecutorConfig::defaults),
                commands,
                runner,
                segments,
                WorkflowExecutor.newNodeId(),
                observers);
    }

    /**
     * Binds the executor as the control the engine exposes.
     *
     * @param executor the executor
     * @return the executor as the control
     */
    @Binds
    abstract WorkflowExecutorControl executorControl(WorkflowExecutor executor);

    /**
     * Contributes the {@code workflow-executor} deployment to the {@code SERVICES} phase, like the outbox relay:
     * {@code workflow.executor.instances} instances on the event loop at priority 100.
     *
     * @param executor the executor
     * @return a set holding the deployment
     */
    @Provides
    @Singleton
    @ElementsIntoSet
    static Set<VerticleDeployment> executorDeployments(WorkflowExecutor executor) {
        return Set.of(executor.deployment());
    }

    /**
     * Declares the Vert.x instance as an optional binding, so a graph that binds none still provides the probe.
     *
     * @return the optional Vert.x binding declaration
     */
    @BindsOptionalOf
    abstract Vertx optionalVertx();

    /**
     * Binds {@link WorkflowRecoveryBridgeImpl} as the {@link WorkflowRecoveryBridge} SPI used by
     * dialect-side branch recovery sweeps.
     *
     * @param impl the recovery bridge singleton
     * @return the bridge implementation as the public SPI
     */
    @Binds
    @Singleton
    abstract WorkflowRecoveryBridge bindRecoveryBridge(WorkflowRecoveryBridgeImpl impl);

    /**
     * Declares the {@code @WorkflowRecorders}-qualified multibinding for side-effect recorders.
     *
     * <p>The {@link SqlClient}-typed multibinding lives here (not in {@code workflow-core}) so that
     * {@code workflow-core} remains SQL-free. Application modules contribute concrete recorders via
     * {@code @Provides @IntoSet @WorkflowRecorders WorkflowSideEffectRecorder<SqlClient>}.
     *
     * @return the (initially empty) set of recorders
     */
    @Multibinds
    @WorkflowRecorders
    abstract Set<WorkflowSideEffectRecorder<SqlClient>> recorders();

    /**
     * Declares {@link WorkflowMigrationRegistry} as an optional binding.
     *
     * <p>When the application installs {@code WorkflowMigrationModule}, Dagger satisfies the
     * {@code Optional<WorkflowMigrationRegistry>} injection point in {@link WorkflowEngine} with the
     * registered registry. Without that module the optional is empty and {@code migrate} fails with
     * {@link UnsupportedOperationException}, keeping migration opt-in.
     *
     * @return the optional migration registry binding declaration
     */
    @BindsOptionalOf
    abstract WorkflowMigrationRegistry optionalMigrationRegistry();

    /**
     * Declares the optional {@link BeanValidator} binding.
     *
     * <p>When the application installs a module that binds a {@link BeanValidator} (for example the
     * validation module), the engine validates coerced decision and start payloads against their
     * declared constraints and rejects violations with {@code WorkflowInvalidPayloadException}.
     * Without a binding the optional is empty and payloads are not constraint-checked, keeping the
     * enforcement opt-in.
     *
     * @return the optional bean validator binding declaration
     */
    @BindsOptionalOf
    abstract BeanValidator optionalBeanValidator();

    /**
     * Provides the set of {@link IntentKind} values that {@link RecorderRouter} treats as optional:
     * if no recorder is registered for an optional kind, the router returns an empty result instead
     * of failing.
     *
     * <p>Delegates to {@link WorkflowEngineFactory#DEFAULT_OPTIONAL_INTENT_KINDS} — the single source
     * of truth shared with the non-Dagger {@link WorkflowEngineFactory#create} assembly path.
     *
     * @return the set of optional intent kinds
     */
    @Provides
    @Singleton
    @OptionalIntentKinds
    static Set<IntentKind> optionalKinds() {
        return WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS;
    }

    /**
     * Contributes the {@link WorkflowReminderComposeValidator} into the
     * {@code Set<WorkflowDefinitionValidator>} multibinding so the registry runs the
     * reminders-require-events check on every plan it builds.
     *
     * @param impl the validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link WorkflowDefinitionValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowDefinitionValidator reminderComposeValidator(WorkflowReminderComposeValidator impl) {
        return impl;
    }
    /** Provides durable publication without changing standalone registry construction. */
    @Binds
    @Singleton
    abstract dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle definitionLifecycle(
            DefaultWorkflowDefinitionLifecycle lifecycle);
    /** Restores durable document membership before any BOOTSTRAP verticle deploys. */
    @Provides
    @Singleton
    @IntoSet
    static dev.vertique.core.lifecycle.ApplicationStartupStep definitionRegistryBootstrapStep(
            WorkflowDefinitionRegistryBootstrapStep step) {
        return step;
    }

    /** Exposes what this node has applied from the durable definition table. */
    @Binds
    @Singleton
    abstract WorkflowDefinitionRegistryView definitionRegistryView(DefaultWorkflowDefinitionRegistryView view);

    /** Arms the periodic reconciliation of the registry with the durable table once every verticle has deployed. */
    @Provides
    @Singleton
    @IntoSet
    static dev.vertique.core.lifecycle.ApplicationStartupStep definitionPropagationStartupStep(
            WorkflowDefinitionPropagationPoller poller) {
        return poller.startupStep();
    }

    /** Cancels the periodic reconciliation on shutdown and waits for the pass that is running. */
    @Provides
    @Singleton
    @IntoSet
    static dev.vertique.core.lifecycle.ApplicationShutdownStep definitionPropagationShutdownStep(
            WorkflowDefinitionPropagationPoller poller) {
        return poller.shutdownStep();
    }
}
