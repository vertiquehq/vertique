// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.engine.executor.DefaultWorkflowExecutionProbe;
import dev.vertique.workflow.engine.executor.HoldingSegments;
import dev.vertique.workflow.engine.executor.OutboxRelayCarriage;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.engine.executor.WorkflowSegments;
import dev.vertique.workflow.engine.registry.FailingMarks;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.engine.spi.CommandWakeups;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module for the portable workflow engine.
 *
 * <p>This module owns the engine-internal bindings that were previously declared by the dialect
 * module: it binds the public ops/callback interfaces to the package-private
 * {@link WorkflowEngine}, declares the {@code @WorkflowRecorders} multibinding, provides the engine-owned
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
     * Provides the plan cache: document plans this node does not hold are read from their rows and built into it on
     * demand, at most {@code workflow.definitions.planCache.maxEntries} at a time, and the registry answers document
     * keys through it. The cache is bound as the registry's document plan source when it is built, so every graph
     * that builds the segments seam, and so every executor, has it bound.
     *
     * @param store          the durable definition rows
     * @param loaders        the document loaders
     * @param registry       the registry whose type names and validators the build uses and which the cache serves
     * @param vertx          the optional Vert.x instance whose worker threads run the builds
     * @param schemaProducer the optional producer of the schemas that fill a legacy row
     * @param config         the optional definitions configuration, or empty for the defaults
     * @param observers      the observers of the cache's events; empty unless the application contributes some
     * @return the plan cache
     */
    @Provides
    @Singleton
    static PlanCache planCache(
            WorkflowDefinitionRegistryStore store,
            Set<PersistedDefinitionLoader> loaders,
            WorkflowRegistry registry,
            Optional<Vertx> vertx,
            Optional<WorkflowPlanSchemaProducer> schemaProducer,
            Optional<WorkflowDefinitionsConfig> config,
            Set<CacheObserver> observers) {
        return PlanCache.bound(
                store,
                loaders,
                registry,
                vertx.orElse(null),
                schemaProducer,
                config.orElseGet(WorkflowDefinitionsConfig::defaults).planCacheMaxEntries(),
                observers);
    }

    /**
     * Provides the failing marks of the plan cache, which a migration reads when it runs.
     *
     * @param cache the plan cache
     * @return the marks
     */
    @Provides
    @Singleton
    static FailingMarks failingMarks(PlanCache cache) {
        return cache.marks();
    }

    /**
     * Declares the observers of cache events as a multibinding, so that an application without a cache provider still
     * builds its graph. A graph that also installs the cache core module declares the same multibinding; Dagger merges
     * the two.
     *
     * @return the (initially empty) set of observers
     */
    @Multibinds
    abstract Set<CacheObserver> cacheObservers();

    /**
     * Provides the engine's side of a segment for the executor, with the plan cache holding the plans it warms.
     *
     * @param engine the engine, resolved lazily because the engine reaches the executor control through this graph
     * @param cache  the plan cache, resolved lazily because building it builds the registry, whose contributors parse
     *               documents and which a graph that never runs an executor need not build
     * @return the segments seam
     */
    @Provides
    @Singleton
    static WorkflowSegments segments(Provider<WorkflowEngine> engine, Provider<PlanCache> cache) {
        return new HoldingSegments(() -> engine.get().segments(), cache::get);
    }

    /**
     * Builds the plan cache, and so binds it to the registry, once the document registry has been restored at startup,
     * so that {@code WorkflowRegistry.load} answers document keys from the moment the application runs and not only
     * from the executor's first pass.
     *
     * @param cache the plan cache, resolved when the step runs, after the registry exists
     * @return the startup step
     */
    @Provides
    @Singleton
    @IntoSet
    static dev.vertique.core.lifecycle.ApplicationStartupStep planCacheStartupStep(Provider<PlanCache> cache) {
        return new dev.vertique.core.lifecycle.ApplicationStartupStep() {
            @Override
            public dev.vertique.core.lifecycle.LifecyclePhase phase() {
                return dev.vertique.core.lifecycle.LifecyclePhase.BOOTSTRAP;
            }

            @Override
            public int priority() {
                // right after the step that restores the registry, which builds it
                return Integer.MIN_VALUE / 2 + 1;
            }

            @Override
            public io.vertx.core.Future<Void> start() {
                cache.get();
                return io.vertx.core.Future.succeededFuture();
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
     * @param wakeups   the optional source that wakes the instances on a commit; absent means polling alone
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
            Set<WorkflowExecutorObserver> observers,
            Optional<CommandWakeups> wakeups,
            OutboxRelayCarriage carriage) {
        return new WorkflowExecutor(
                vertx.orElse(null),
                config.orElseGet(WorkflowExecutorConfig::defaults),
                commands,
                runner,
                segments,
                WorkflowExecutor.newNodeId(),
                observers,
                wakeups.orElseGet(CommandWakeups::none),
                carriage);
    }

    /** The carriage requirements of {@code identity.snapshot.carriageRequirements} the executor start warns about. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CarriageRequirements(
            @JsonProperty("outbox-relay") String outboxRelay, String workflow) {}

    /**
     * Binds the {@code outbox-relay} and {@code workflow} entries of {@code identity.snapshot.carriageRequirements}
     * as a typed value: an entry counts only when it is {@code REQUIRED}, and an absent entry, section or other
     * key is not required and is not an error. Only the framework's typed configuration binding reads the
     * configuration; the security runtime is not a dependency.
     *
     * @param config the application configuration
     * @param parser the typed configuration binding
     * @return the carriage requirements
     */
    public static OutboxRelayCarriage outboxRelayCarriage(JsonObject config, ConfigParser parser) {
        CarriageRequirements requirements = parser.parse(
                JsonConfigPaths.navigateObject(config, "identity", "snapshot", "carriageRequirements"),
                CarriageRequirements.class);
        return new OutboxRelayCarriage(
                "REQUIRED".equals(requirements.outboxRelay()), "REQUIRED".equals(requirements.workflow()));
    }

    /**
     * Provides the carriage requirements for the executor start; an application without a configuration binding
     * requires none.
     *
     * @param config the optional application configuration
     * @param parser the optional typed configuration binding
     * @return the carriage requirements
     */
    @Provides
    @Singleton
    static OutboxRelayCarriage carriage(@VertxConfig Optional<JsonObject> config, Optional<ConfigParser> parser) {
        return config.isPresent() && parser.isPresent()
                ? outboxRelayCarriage(config.get(), parser.get())
                : OutboxRelayCarriage.none();
    }

    /**
     * Declares the application configuration as an optional binding.
     *
     * @return the optional configuration declaration
     */
    @BindsOptionalOf
    @VertxConfig
    abstract JsonObject optionalConfig();

    /**
     * Declares the typed configuration binding as an optional binding.
     *
     * @return the optional parser declaration
     */
    @BindsOptionalOf
    abstract ConfigParser optionalConfigParser();

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
     * {@code workflow.executor.instances} instances on the event loop at priority 100. A recording-only node
     * ({@code workflow.executor.enabled=false}) contributes none.
     *
     * @param config   the executor configuration, or empty for the defaults
     * @param executor the executor, built only when the node runs one
     * @return a set holding the deployment, or an empty set on a recording-only node
     */
    @Provides
    @Singleton
    @ElementsIntoSet
    static Set<VerticleDeployment> executorDeployments(
            Optional<WorkflowExecutorConfig> config, Provider<WorkflowExecutor> executor) {
        if (!config.orElseGet(WorkflowExecutorConfig::defaults).enabled()) {
            return Set.of();
        }
        return Set.of(executor.get().deployment());
    }

    /**
     * Declares the wake-up source as an optional binding; the PostgreSQL module binds the listener, and a graph that
     * binds none polls alone.
     *
     * @return the optional wake-up source declaration
     */
    @BindsOptionalOf
    abstract CommandWakeups optionalCommandWakeups();

    /**
     * Declares the transactional reads of definition rows as an optional binding; the PostgreSQL module binds them.
     * Without them a start of an instance fails, because it selects its version from the rows.
     *
     * @return the optional reads declaration
     */
    @BindsOptionalOf
    abstract TransactionalDefinitionReads<SqlClient> optionalDefinitionReads();

    /**
     * Declares the Vert.x instance as an optional binding, so a graph that binds none still provides the probe.
     *
     * @return the optional Vert.x binding declaration
     */
    @BindsOptionalOf
    abstract Vertx optionalVertx();

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
    /** Records the code and the packaged documents in the definition table before any BOOTSTRAP verticle deploys. */
    @Provides
    @Singleton
    @IntoSet
    static dev.vertique.core.lifecycle.ApplicationStartupStep definitionRegistryBootstrapStep(
            WorkflowDefinitionRegistryBootstrapStep step) {
        return step;
    }
}
