// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule;
import dev.vertique.job.cron.dagger.CronPersistenceModule;
import dev.vertique.job.postgresql.JobPostgresqlModule;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.IdentitySnapshotReconstructionModule;
import dev.vertique.security.runtime.ServiceIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * Application graphs with the outbox relay, the workflow signal endpoint and the workflow executor over one migrated
 * database: with identity carriage and the framework's relay identity, with identity carriage and a bound
 * {@link ServiceIdentityResolver}, and with no identity carriage at all.
 */
final class RelayGraph {

    /** The reason the relay's identity carries, which is the event type of the outbox row it delivers. */
    static final String EVENT_TYPE = "post";

    /** The definition the graphs register. */
    static final String DEFINITION_ID = "relayed-signal-actor";

    /** The definition whose first step dispatches {@code billing.charge} through the outbox. */
    static final String CHARGE_DEFINITION_ID = "relayed-charge";

    /** The signal the definition waits for. */
    static final String SIGNAL = "go";

    private RelayGraph() {}

    // --- The workflow the graphs run ---

    /**
     * Start payload.
     *
     * @param ref the label of the instance
     */
    record Start(String ref) {}

    /**
     * State.
     *
     * @param ref  the label of the instance
     * @param done whether the signal arrived
     */
    record State(String ref, boolean done) {}

    /**
     * Signal payload.
     *
     * @param value an opaque value
     */
    record Go(String value) {}

    /** Contract of the workflow. */
    interface Contract {
        @WorkflowSignal(SIGNAL)
        Future<Void> go(WorkflowInstanceId id, Go payload, @SignalDedupKey String dedupKey);
    }

    /** Waits for one signal and ends. */
    static final class WaitForGo implements WorkflowDefinition<State, Contract> {
        @Override
        public Class<Contract> contract() {
            return Contract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref(), false))
                    .initialStep("wait")
                    .waitFor("wait", SIGNAL, Go.class, (state, payload) -> new State(state.ref(), true), "done")
                    .complete("done");
        }
    }

    /** Contract of the workflow that dispatches; it takes no signals. */
    interface ChargeContract {}

    /** Dispatches {@code billing.charge} as its first step and ends. */
    static final class ChargeFirst implements WorkflowDefinition<State, ChargeContract> {
        @Override
        public Class<ChargeContract> contract() {
            return ChargeContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return CHARGE_DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref(), false))
                    .initialStep("charge")
                    .dispatch(
                            "charge", "billing.charge", state -> new BillingService.ChargeRequest(state.ref()), "done")
                    .complete("done");
        }
    }

    // --- Modules ---

    /** What every graph shares. */
    @Module(
            includes = {
                ConfigParsingModule.class,
                DeployerModule.class,
                CoreLifecycleStepsModule.class,
                DispatchModule.class,
                TransactionalMessagingPostgresqlModule.class,
                TransactionalMessagingServiceModule.class,
                WorkflowPostgresqlModule.class,
                WorkflowDefinitionsConfigModule.class,
                WorkflowExecutorConfigModule.class,
                WorkflowSchemaModule.class,
                WorkflowServicesModule.class,
                CronPersistenceModule.class,
                JobPostgresqlModule.class
            })
    abstract static class Shared {

        @Provides
        @Singleton
        static Clock clock() {
            return Clock.systemUTC();
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        @Singleton
        static WorkflowSideEffectRecorder<SqlClient> eventRecorder() {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.WORKFLOW_EVENT;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient transaction) {
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
        }

        @Provides
        @IntoSet
        static WorkflowContributor definitions() {
            return registry -> registry.register(new WaitForGo());
        }

        @Provides
        @IntoSet
        static WorkflowContributor chargeDefinition() {
            return registry -> registry.register(new ChargeFirst());
        }

        @Provides
        @IntoSet
        @Singleton
        static ServiceContractContributor billing(BillingProbe.Contributor contributor) {
            return contributor;
        }
    }

    /** The database the graph runs over. */
    @Module
    static final class Infrastructure {
        private final Pool pool;
        private final PgConnectOptions connectOptions;

        Infrastructure(Pool pool, PgConnectOptions connectOptions) {
            this.pool = pool;
            this.connectOptions = connectOptions;
        }

        @Provides
        @Singleton
        Pool pool() {
            return pool;
        }

        @Provides
        @Singleton
        PgConnectOptions connectOptions() {
            return connectOptions;
        }
    }

    /** A bound identity resolver, as an application that names a dedicated relay service binds. */
    @Module
    static final class ResolverModule {
        private final ServiceIdentityResolver resolver;

        ResolverModule(ServiceIdentityResolver resolver) {
            this.resolver = resolver;
        }

        @Provides
        @Singleton
        ServiceIdentityResolver resolver() {
            return resolver;
        }
    }

    // --- Components ---

    /** The operations every graph exposes. */
    interface Graph extends VertiqueApplicationComponent {
        TransactionalWorkflowOperations<SqlClient> transactionalOperations();

        WorkflowExecutionProbe executionProbe();

        OutboxService outboxService();

        Pool pool();

        ContextHolder contextHolder();

        BillingProbe billingProbe();
    }

    /** Identity carriage and reconstruction, the framework's relay identity. */
    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                Shared.class,
                Infrastructure.class,
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class
            })
    interface Carrying extends Graph {
        IdentitySnapshotCapture identitySnapshotCapture();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            Builder infrastructure(Infrastructure module);

            Carrying build();
        }
    }

    /** Identity carriage and reconstruction with a bound {@link ServiceIdentityResolver}. */
    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                Shared.class,
                Infrastructure.class,
                ResolverModule.class,
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class
            })
    interface CarryingWithResolver extends Graph {
        IdentitySnapshotCapture identitySnapshotCapture();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            Builder infrastructure(Infrastructure module);

            Builder resolverModule(ResolverModule module);

            CarryingWithResolver build();
        }
    }

    /** No identity carriage at all. */
    @Singleton
    @Component(modules = {VertxModule.class, Shared.class, Infrastructure.class})
    interface Plain extends Graph {
        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            Builder infrastructure(Infrastructure module);

            Plain build();
        }
    }

    // --- Starting ---

    /** The application config shared by the graphs; the policy and requirements are the parts a proof varies. */
    static JsonObject config(String degradationPolicy) {
        return config(degradationPolicy, Map.of());
    }

    /**
     * The application config with identity carriage requirements by durable-target kind; a required kind needs finite
     * freshness budgets.
     */
    static JsonObject config(String degradationPolicy, Map<String, String> carriageRequirements) {
        JsonObject snapshot = new JsonObject()
                .put(
                        "hmacKeys",
                        new JsonObject()
                                .put(
                                        "active",
                                        new JsonObject()
                                                .put("keyId", "key-1")
                                                .put("secretRef", "super-secret-signing-key-material")));
        if (degradationPolicy != null) {
            snapshot.put("onDegradation", degradationPolicy);
        }
        if (!carriageRequirements.isEmpty()) {
            JsonObject requirements = new JsonObject();
            carriageRequirements.forEach(requirements::put);
            snapshot.put("carriageRequirements", requirements)
                    .put("maxCarrierLifetimeMs", 3_600_000L)
                    .put("maxSnapshotLifetimeMs", 86_400_000L);
        }
        return new JsonObject()
                .put("identity", new JsonObject().put("snapshot", snapshot))
                .put("inboxOutbox", new JsonObject().put("relay", new JsonObject().put("pollingIntervalMs", 100)))
                .put("workflow", new JsonObject().put("executor", new JsonObject().put("pollIntervalMs", 100)));
    }

    /** A pool on the database, with the connect options it was built from. */
    static Pool pool(Vertx vertx, DbPoolConfig config, PgConnectOptions options) {
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(12))
                .connectingTo(options)
                .using(vertx)
                .build();
    }

    /** The connect options of the database. */
    static PgConnectOptions options(PostgresContainer db) {
        DbPoolConfig config = db.toPoolConfig();
        return new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.database())
                .setUser(config.user())
                .setPassword(config.password());
    }

    /** Starts the graph's lifecycle and returns the handle. */
    static <G extends Graph> Future<VertiqueApplicationHandle<G>> start(Vertx vertx, G graph) {
        return VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> graph);
    }
}
