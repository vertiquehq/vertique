// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.PayloadMapperContributor;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.it.fixture.FraudScreenedDoc;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderFanOutState;
import dev.vertique.workflow.definition.it.fixture.OrderFulfillmentDocumentWorkflowV1;
import dev.vertique.workflow.definition.it.fixture.OrderFulfillmentDocumentWorkflowV2;
import dev.vertique.workflow.definition.it.fixture.OrderFulfillmentFanOutDocumentWorkflowV1;
import dev.vertique.workflow.definition.it.fixture.PaymentAuthorizedDoc;
import dev.vertique.workflow.definition.it.fixture.PaymentCapturedDoc;
import dev.vertique.workflow.definition.it.fixture.PlaceFanOutOrder;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.it.fixture.ShipmentCreatedDoc;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.service.WorkflowDefinitionStore;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowDefinitionPropagationPoller;
import dev.vertique.workflow.engine.WorkflowDefinitionRegistryView;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real PostgreSQL, generated application graph and the full callbacks for the workflow fixture. */
public final class ManagedDefinitionDatabase {

    private ManagedDefinitionDatabase() {}

    public static Pool openPool(Vertx vertx, PostgresContainer database) {
        return openPool(vertx, database.toPoolConfig(), 10);
    }

    public static Pool openPool(Vertx vertx, PostgresContainer database, int maxPoolSize) {
        return openPool(vertx, database.toPoolConfig(), maxPoolSize);
    }

    static Pool openPool(Vertx vertx, PostgresContainer database, String databaseName) {
        DbPoolConfig original = database.toPoolConfig();
        DbPoolConfig config = DbPoolConfig.builder()
                .host(original.host())
                .port(original.port())
                .database(databaseName)
                .user(original.user())
                .password(original.password())
                .maxPoolSize(original.maxPoolSize())
                .build();
        return openPool(vertx, config, 10);
    }

    private static Pool openPool(Vertx vertx, DbPoolConfig config, int maxPoolSize) {
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(maxPoolSize))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    public static byte[] yamlFixture() {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("definitions/it/order-fulfillment.workflow.yaml")) {
            if (input == null) {
                throw new IllegalStateException("The workflow fixture is not available on the classpath");
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the workflow fixture", e);
        }
    }

    public static byte[] jsonFixture() {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("definitions/it/order-fulfillment.workflow.json")) {
            if (input == null) {
                throw new IllegalStateException("The workflow fixture is not available on the classpath");
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the workflow fixture", e);
        }
    }

    public static AppComponent app(Vertx vertx, Pool pool, BootObservation observation) {
        return app(vertx, pool, new JsonObject(), observation, List::of, false);
    }

    public static AppComponent app(
            Vertx vertx, Pool pool, JsonObject config, BootObservation observation, WorkflowDefinitionSource source) {
        return app(vertx, pool, config, observation, source, false);
    }

    static AppComponent app(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            BootObservation observation,
            WorkflowDefinitionSource source,
            boolean includeCodeDefinition) {
        return app(vertx, pool, config, observation, source, includeCodeDefinition, false);
    }

    static AppComponent app(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            BootObservation observation,
            WorkflowDefinitionSource source,
            boolean includeCodeDefinition,
            boolean includeLargeUnicodeMapper) {
        return app(
                vertx,
                pool,
                config,
                observation,
                source,
                includeCodeDefinition,
                includeLargeUnicodeMapper,
                new WorkflowDefinitionParserProbe());
    }

    static AppComponent app(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            BootObservation observation,
            WorkflowDefinitionSource source,
            boolean includeCodeDefinition,
            boolean includeLargeUnicodeMapper,
            WorkflowDefinitionParserProbe parserProbe) {
        return DaggerManagedDefinitionDatabase_AppComponent.builder()
                .vertxModule(new VertxModule(vertx, config))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(new BootObservationModule(observation))
                .definitionSourceModule(new DefinitionSourceModule(source))
                .codeCatalogModule(new CodeCatalogModule(includeCodeDefinition))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(includeLargeUnicodeMapper))
                .parserModule(parserProbe.module())
                .typeCatalogModule(new TypeCatalogModule(callingTestClass()))
                .build();
    }

    /** Builds a graph whose application contributes the given code-first definitions to the registry. */
    static AppComponent appWithCode(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            BootObservation observation,
            WorkflowDefinitionSource source,
            List<WorkflowDefinition<?, ?>> codeDefinitions) {
        return DaggerManagedDefinitionDatabase_AppComponent.builder()
                .vertxModule(new VertxModule(vertx, config))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(new BootObservationModule(observation))
                .definitionSourceModule(new DefinitionSourceModule(source))
                .codeCatalogModule(new CodeCatalogModule(false, codeDefinitions))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(new WorkflowDefinitionParserProbe().module())
                .typeCatalogModule(new TypeCatalogModule(callingTestClass()))
                .build();
    }

    static NoLoaderAppComponent appWithoutLoader(Vertx vertx, Pool pool, BootObservation observation) {
        return DaggerManagedDefinitionDatabase_NoLoaderAppComponent.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .noLoaderBootObservationModule(new NoLoaderBootObservationModule(observation))
                .build();
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                DeployerModule.class,
                CoreLifecycleStepsModule.class,
                WorkflowPostgresqlModule.class,
                WorkflowDefinitionModule.class,
                DefinitionSourceModule.class,
                CodeCatalogModule.class,
                LargeUnicodeMapperModule.class,
                CallbackCatalogModule.class,
                PostgresInfrastructureModule.class,
                BootObservationModule.class,
                WorkflowDefinitionParserProbe.ParserModule.class,
                TypeCatalogModule.class
            })
    public interface AppComponent extends VertiqueApplicationComponent {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder postgresInfrastructureModule(PostgresInfrastructureModule module);

            Builder bootObservationModule(BootObservationModule module);

            Builder definitionSourceModule(DefinitionSourceModule module);

            Builder codeCatalogModule(CodeCatalogModule module);

            Builder largeUnicodeMapperModule(LargeUnicodeMapperModule module);

            Builder parserModule(WorkflowDefinitionParserProbe.ParserModule module);

            Builder typeCatalogModule(TypeCatalogModule module);

            AppComponent build();
        }

        WorkflowDefinitionService definitionService();

        dev.vertique.workflow.ops.WorkflowOperations workflowOperations();

        dev.vertique.workflow.ops.TransactionalWorkflowOperations<io.vertx.sqlclient.SqlClient>
                transactionalOperations();

        WorkflowDefinitionPipeline definitionPipeline();

        WorkflowRegistry workflowRegistry();

        WorkflowDefinitionRegistryStore definitionRegistryStore();

        WorkflowDefinitionStore workflowDefinitionStore();

        WorkflowDefinitionsConfig workflowDefinitionsConfig();

        /** The committed records this node has applied to its registry, and its count of rows that fail to apply. */
        WorkflowDefinitionRegistryView definitionRegistryView();

        /** The periodic reconciliation, triggered by the tests directly instead of by waiting for its timer. */
        WorkflowDefinitionPropagationPoller definitionPropagationPoller();

        Pool pool();
    }

    /**
     * The test class that is building the graph: the first caller outside the fixture classes. Taken on the
     * calling thread because the graph resolves its bindings later, on other threads.
     */
    private static Class<?> callingTestClass() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .map(Class::getNestHost)
                        .filter(host -> host != ManagedDefinitionDatabase.class && host != DefinitionScenario.class)
                        .findFirst()
                        .orElse(ManagedDefinitionDatabase.class));
    }

    /**
     * Lists the classes a service-loaded document in these tests may name: the shared order fulfillment fixture
     * and every class nested in the test class that builds the graph (its contracts as contracts, the rest as
     * types). The service resolves names through the catalog only, so a test document can name exactly these.
     */
    @Module
    static class TypeCatalogModule {

        private final Class<?> testClass;

        TypeCatalogModule(Class<?> testClass) {
            this.testClass = testClass;
        }

        @Provides
        @IntoSet
        WorkflowTypeCatalog typeCatalog() {
            List<WorkflowTypeCatalogEntry> entries = new ArrayList<>();
            for (Class<?> contract : List.of(
                    OrderFulfillmentDocumentWorkflowV1.class,
                    OrderFulfillmentDocumentWorkflowV2.class,
                    OrderFulfillmentFanOutDocumentWorkflowV1.class)) {
                entries.add(new WorkflowTypeCatalogEntry(Kind.CONTRACT, contract.getName(), contract));
            }
            for (Class<?> type : List.of(
                    OrderDocState.class,
                    OrderFanOutState.class,
                    PlaceOrderDoc.class,
                    PlaceFanOutOrder.class,
                    InventoryReservedDoc.class,
                    PaymentAuthorizedDoc.class,
                    PaymentCapturedDoc.class,
                    FraudScreenedDoc.class,
                    ShipmentCreatedDoc.class)) {
                entries.add(new WorkflowTypeCatalogEntry(Kind.TYPE, type.getName(), type));
            }
            for (Class<?> member : testClass.getNestMembers()) {
                if (member == testClass || member.isAnonymousClass() || member.isSynthetic()) {
                    continue;
                }
                boolean contract = member.isAnnotationPresent(WorkflowContract.class);
                entries.add(
                        new WorkflowTypeCatalogEntry(contract ? Kind.CONTRACT : Kind.TYPE, member.getName(), member));
            }
            return () -> entries;
        }
    }

    @Module
    static class DefinitionSourceModule {

        private final WorkflowDefinitionSource source;

        DefinitionSourceModule(WorkflowDefinitionSource source) {
            this.source = source;
        }

        @Provides
        @IntoSet
        WorkflowDefinitionSource definitionSource() {
            return source;
        }
    }

    @Module
    static class CodeCatalogModule {

        private final boolean enabled;
        private final List<WorkflowDefinition<?, ?>> contributed;

        CodeCatalogModule(boolean enabled) {
            this(enabled, List.of());
        }

        CodeCatalogModule(boolean enabled, List<WorkflowDefinition<?, ?>> contributed) {
            this.enabled = enabled;
            this.contributed = List.copyOf(contributed);
        }

        @Provides
        @IntoSet
        WorkflowContributor codeDefinitionContributor() {
            return registry -> {
                if (enabled) {
                    registry.register(new RegistryProbeWorkflow());
                }
                contributed.forEach(registry::register);
            };
        }
    }

    static final String LONG_UNICODE_MAPPER_ID = "document-text-" + "\u0800".repeat(140_000);

    @Module
    static class LargeUnicodeMapperModule {

        private final boolean enabled;

        LargeUnicodeMapperModule(boolean enabled) {
            this.enabled = enabled;
        }

        @Provides
        @IntoSet
        StartStateMapperContributor largeUnicodeMapper() {
            return builder -> {
                if (enabled) {
                    builder.register(new NamedStartStateMapper<>(
                            LONG_UNICODE_MAPPER_ID,
                            PlaceOrderDoc.class,
                            OrderDocState.class,
                            OrderDocState::fromPayload));
                }
            };
        }
    }

    private record RegistryProbeState(String value) {}

    private record RegistryProbeStart(String value) {}

    @WorkflowContract(definitionId = "definition-policy-code", definitionVersion = 1)
    private interface RegistryProbeContract {}

    private static final class RegistryProbeWorkflow
            implements WorkflowDefinition<RegistryProbeState, RegistryProbeContract> {

        @Override
        public Class<RegistryProbeContract> contract() {
            return RegistryProbeContract.class;
        }

        @Override
        public Class<RegistryProbeState> stateType() {
            return RegistryProbeState.class;
        }

        @Override
        public String definitionId() {
            return "definition-policy-code";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<RegistryProbeState> workflow) {
            workflow.init(RegistryProbeStart.class, start -> new RegistryProbeState(start.value()))
                    .initialStep("done")
                    .complete("done");
        }
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                DeployerModule.class,
                CoreLifecycleStepsModule.class,
                WorkflowPostgresqlModule.class,
                PostgresInfrastructureModule.class,
                NoLoaderBootObservationModule.class
            })
    interface NoLoaderAppComponent extends VertiqueApplicationComponent {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder postgresInfrastructureModule(PostgresInfrastructureModule module);

            Builder noLoaderBootObservationModule(NoLoaderBootObservationModule module);

            NoLoaderAppComponent build();
        }

        WorkflowRegistry workflowRegistry();

        Pool pool();
    }

    @Module
    static class PostgresInfrastructureModule {

        private final Pool pool;

        PostgresInfrastructureModule(Pool pool) {
            this.pool = pool;
        }

        @Provides
        @Singleton
        Pool pool() {
            return pool;
        }

        @Provides
        @Singleton
        static Clock clock() {
            return Clock.systemUTC();
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        @Singleton
        static WorkflowSideEffectRecorder<SqlClient> serviceRecorder() {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.SERVICE;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient transaction) {
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
        }
    }

    @Module
    static class CallbackCatalogModule {

        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "doc.order.fromPlaceOrder", PlaceOrderDoc.class, OrderDocState.class, OrderDocState::fromPayload));
        }

        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static PayloadMapperContributor payloadMappers() {
            return builder -> {
                builder.register(new NamedPayloadMapper<>(
                        "doc.order.reserveInventoryPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId())));
                builder.register(new NamedPayloadMapper<>(
                        "doc.order.releaseInventoryPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of(
                                "orderId",
                                s.orderId(),
                                "reservationId",
                                s.reservationId() == null ? "" : s.reservationId())));
                builder.register(new NamedPayloadMapper<>(
                        "doc.order.authorizePaymentPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId())));
                builder.register(new NamedPayloadMapper<>(
                        "doc.order.voidAuthorizationPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId(), "authId", s.authId() == null ? "" : s.authId())));
                builder.register(new NamedPayloadMapper<>(
                        "doc.order.createShipmentPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId())));
            };
        }

        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static StateReducerContributor stateReducers() {
            return builder -> {
                builder.register(new NamedStateReducer<>(
                        "doc.order.applyInventoryReserved",
                        OrderDocState.class,
                        (state, signal) -> OrderDocState.applyInventoryReserved(
                                (OrderDocState) state, coerce(signal, InventoryReservedDoc.class))));
                builder.register(new NamedStateReducer<>(
                        "doc.order.applyPaymentCaptured",
                        OrderDocState.class,
                        (state, signal) -> OrderDocState.applyPaymentCaptured(
                                (OrderDocState) state, coerce(signal, PaymentCapturedDoc.class))));
                builder.register(new NamedStateReducer<>(
                        "doc.order.applyShipmentCreated",
                        OrderDocState.class,
                        (state, signal) -> OrderDocState.applyShipmentCreated(
                                (OrderDocState) state, coerce(signal, ShipmentCreatedDoc.class))));
                builder.register(new NamedStateReducer<>(
                        "doc.order.applyInventoryReservedDrift",
                        OrderDocState.class,
                        (state, signal) -> OrderDocState.applyInventoryReserved(
                                (OrderDocState) state, coerce(signal, InventoryReservedDoc.class))));
            };
        }

        private static <T> T coerce(Object value, Class<T> type) {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            return io.vertx.core.json.Json.decodeValue(io.vertx.core.json.Json.encode(value), type);
        }
    }

    @Module
    static class BootObservationModule {

        private final BootObservation observation;

        BootObservationModule(BootObservation observation) {
            this.observation = observation;
        }

        @Provides
        @Singleton
        BootObservation observation() {
            return observation;
        }

        @Provides
        @IntoSet
        static ApplicationStartupStep observeRestoredRegistry(
                WorkflowRegistry registry,
                BootObservation observation,
                Pool pool,
                WorkflowDefinitionService service,
                WorkflowDefinitionStore cache) {
            return new RegistryReadyStep(registry, observation, pool, service, cache);
        }

        @Provides
        @IntoSet
        static VerticleDeployment observeBootstrapDeployment(WorkflowRegistry registry, BootObservation observation) {
            return VerticleDeployment.of(
                    "definition-registry-observer",
                    () -> new RegistryObservedVerticle(registry, observation),
                    LifecyclePhase.BOOTSTRAP,
                    Integer.MAX_VALUE);
        }
    }

    @Module
    static class NoLoaderBootObservationModule {

        private final BootObservation observation;

        NoLoaderBootObservationModule(BootObservation observation) {
            this.observation = observation;
        }

        @Provides
        @Singleton
        BootObservation observation() {
            return observation;
        }

        @Provides
        @IntoSet
        static ApplicationStartupStep observeRestoredRegistry(WorkflowRegistry registry, BootObservation observation) {
            return new NoLoaderRegistryReadyStep(registry, observation);
        }

        @Provides
        @IntoSet
        static VerticleDeployment observeBootstrapDeployment(WorkflowRegistry registry, BootObservation observation) {
            return VerticleDeployment.of(
                    "definition-registry-observer",
                    () -> new RegistryObservedVerticle(registry, observation),
                    LifecyclePhase.BOOTSTRAP,
                    Integer.MAX_VALUE);
        }
    }

    public static final class BootObservation {

        private final String definitionId;
        private final long definitionVersion;
        private final String expectedPlanHash;
        private final boolean enabled;
        private final boolean verifyPinnedDefinition;
        private final List<ExpectedDefinition> expectedDefinitions;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private volatile MetadataObservation metadataObservation;

        public BootObservation(String definitionId, long definitionVersion, String expectedPlanHash, boolean enabled) {
            this.definitionId = definitionId;
            this.definitionVersion = definitionVersion;
            this.expectedPlanHash = expectedPlanHash;
            this.enabled = enabled;
            this.verifyPinnedDefinition = true;
            this.expectedDefinitions = List.of();
        }

        public BootObservation(String definitionId, List<ExpectedDefinition> expectedDefinitions) {
            this.definitionId = Objects.requireNonNull(definitionId, "definitionId");
            this.definitionVersion = 0;
            this.expectedPlanHash = null;
            this.enabled = true;
            this.verifyPinnedDefinition = false;
            this.expectedDefinitions = List.copyOf(expectedDefinitions);
        }

        public List<String> events() {
            return List.copyOf(events);
        }

        public MetadataObservation metadataObservation() {
            return metadataObservation;
        }

        void definitionIsPublished(WorkflowRegistry registry) {
            if (!enabled || !verifyPinnedDefinition) {
                return;
            }
            String actualHash = registry.resolvePinned(definitionId, definitionVersion)
                    .plan()
                    .planHash();
            if (!expectedPlanHash.equals(actualHash)) {
                throw new IllegalStateException("Restored workflow plan hash differs from the expected publication");
            }
        }

        Future<Void> observeMetadataBeforeDeployment(
                Pool pool,
                WorkflowDefinitionService service,
                WorkflowDefinitionStore cache,
                WorkflowRegistry registry) {
            if (expectedDefinitions.isEmpty()) return Future.succeededFuture();
            return pool.preparedQuery("""
                            SELECT tenant_key, definition_id, definition_version, kind, plan_hash, status,
                                document_format, source_type, source_uri, source_revision, source_author,
                                source_upload_id, loaded_at, created_at, status_changed_at
                            FROM workflow_definitions WHERE definition_id = $1
                            ORDER BY definition_version
                            """)
                    .execute(io.vertx.sqlclient.Tuple.of(definitionId))
                    .map(rows -> {
                        List<WorkflowDefinitionRecord> durable = new ArrayList<>();
                        for (Row row : rows) durable.add(metadata(row));
                        return List.copyOf(durable);
                    })
                    .compose(durable -> service.list(
                                    new DefinitionFilter(DefinitionFilter.TenantScope.GLOBAL, null, null))
                            .map(serviceRows -> {
                                List<WorkflowDefinitionRecord> scopedServiceRows = serviceRows.stream()
                                        .filter(record ->
                                                record.key().definitionId().equals(definitionId))
                                        .sorted(Comparator.comparingLong(
                                                record -> record.key().definitionVersion()))
                                        .toList();
                                List<CompiledDefinitionRef> compiled = cache.list().stream()
                                        .filter(ref -> ref.definitionId().equals(definitionId))
                                        .sorted(Comparator.comparingLong(CompiledDefinitionRef::definitionVersion))
                                        .toList();
                                List<RuntimeWorkflow> registered = registry.allRegistered().stream()
                                        .filter(runtime ->
                                                runtime.plan().definitionId().equals(definitionId))
                                        .toList();
                                MetadataObservation snapshot = new MetadataObservation(
                                        List.copyOf(durable),
                                        List.copyOf(scopedServiceRows),
                                        List.copyOf(compiled),
                                        registered.size());
                                metadataObservation = snapshot;
                                verifyMetadata(snapshot, registry, definitionId, expectedDefinitions);
                                events.add("metadata-observed-before-deployment");
                                return (Void) null;
                            }));
        }

        private static WorkflowDefinitionRecord metadata(Row row) {
            return new WorkflowDefinitionRecord(
                    new DefinitionKey(
                            row.getString("tenant_key"),
                            row.getString("definition_id"),
                            row.getLong("definition_version")),
                    DefinitionKind.valueOf(row.getString("kind")),
                    row.getString("plan_hash"),
                    DefinitionStatus.valueOf(row.getString("status")),
                    new DefinitionProvenance(
                            row.getString("source_type"),
                            row.getString("source_uri"),
                            row.getString("source_revision"),
                            row.getString("source_author"),
                            row.getString("source_upload_id"),
                            row.getOffsetDateTime("loaded_at").toInstant()),
                    row.getString("document_format"),
                    row.getOffsetDateTime("created_at").toInstant(),
                    row.getOffsetDateTime("status_changed_at").toInstant());
        }

        private static void verifyMetadata(
                MetadataObservation observation,
                WorkflowRegistry registry,
                String definitionId,
                List<ExpectedDefinition> expected) {
            require(
                    observation.durable().size() == expected.size(),
                    "durable definition rows differ from input versions");
            require(observation.service().size() == expected.size(), "service metadata differs from input versions");
            require(observation.compiled().size() == expected.size(), "compiled cache differs from input versions");
            require(
                    observation.registeredCount() == expected.size(),
                    "compiled registry contains duplicate or missing versions");

            Map<Long, WorkflowDefinitionRecord> durableByVersion =
                    byVersion(observation.durable(), record -> record.key().definitionVersion(), "durable metadata");
            Map<Long, WorkflowDefinitionRecord> serviceByVersion =
                    byVersion(observation.service(), record -> record.key().definitionVersion(), "service metadata");
            Map<Long, CompiledDefinitionRef> cacheByVersion =
                    byVersion(observation.compiled(), CompiledDefinitionRef::definitionVersion, "compiled cache");
            Map<Long, Long> registryVersions = new HashMap<>();
            for (RuntimeWorkflow runtime : registry.allRegistered()) {
                if (runtime.plan().definitionId().equals(definitionId)) {
                    registryVersions.merge(runtime.plan().definitionVersion(), 1L, Long::sum);
                }
            }
            require(registryVersions.size() == expected.size(), "compiled registry has missing or duplicate versions");
            require(
                    registryVersions.values().stream().allMatch(count -> count == 1L),
                    "compiled registry has duplicate versions");

            String previousHash = null;
            for (ExpectedDefinition item : expected) {
                long version = item.version();
                WorkflowDefinitionRecord durable = durableByVersion.get(version);
                WorkflowDefinitionRecord service = serviceByVersion.get(version);
                CompiledDefinitionRef compiled = cacheByVersion.get(version);
                require(durable != null, "missing durable metadata for version " + version);
                require(service != null, "missing service metadata for version " + version);
                require(compiled != null, "missing compiled cache entry for version " + version);
                require(durable.key().tenantKey() == null, "startup metadata is not global");
                require(durable.kind() == DefinitionKind.DOCUMENT, "startup row is not DOCUMENT");
                require(durable.status() == item.status(), "durable status differs for version " + version);
                require(service.status() == item.status(), "service status differs for version " + version);
                require(
                        compiled.status()
                                == ActivationStatus.valueOf(item.status().name()),
                        "cache status differs for version " + version);
                require(
                        item.documentFormat().equals(durable.documentFormat()),
                        "durable format differs for version " + version);
                require(
                        item.documentFormat().equals(service.documentFormat()),
                        "service format differs for version " + version);
                require(durable.planHash().matches("[0-9a-f]{64}"), "durable hash is missing for version " + version);
                require(durable.planHash().equals(service.planHash()), "service hash differs for version " + version);
                require(durable.planHash().equals(compiled.planHash()), "cache hash differs for version " + version);
                require(
                        item.metadata().sourceType().equals(durable.provenance().sourceType()),
                        "durable source type differs for version " + version);
                require(
                        item.metadata().uri().equals(durable.provenance().uri()),
                        "durable source URI differs for version " + version);
                require(
                        item.metadata().revision().equals(durable.provenance().revision()),
                        "durable source revision differs for version " + version);
                require(
                        item.metadata().author().equals(durable.provenance().author()),
                        "durable source author differs for version " + version);
                require(
                        Objects.equals(
                                item.metadata().uploadId(), durable.provenance().uploadId()),
                        "durable upload id differs for version " + version);
                require(
                        item.metadata().loadedAt().equals(durable.provenance().loadedAt()),
                        "durable load time differs for version " + version);
                require(
                        item.metadata().sourceType().equals(compiled.source().sourceType()),
                        "cache source type differs for version " + version);
                require(
                        item.metadata().uri().equals(compiled.source().uri()),
                        "cache source URI differs for version " + version);
                require(
                        item.metadata().revision().equals(compiled.source().revision()),
                        "cache source revision differs for version " + version);
                require(
                        item.metadata().author().equals(compiled.source().author()),
                        "cache source author differs for version " + version);
                require(
                        Objects.equals(
                                item.metadata().uploadId(), compiled.source().uploadId()),
                        "cache upload id differs for version " + version);
                require(
                        item.metadata().loadedAt().equals(compiled.source().loadedAt()),
                        "cache load time differs for version " + version);
                if (previousHash != null)
                    require(!previousHash.equals(durable.planHash()), "versions unexpectedly share a plan hash");
                previousHash = durable.planHash();
            }
        }

        private static <T> Map<Long, T> byVersion(
                List<T> values, java.util.function.ToLongFunction<T> version, String label) {
            Map<Long, T> result = new HashMap<>();
            for (T value : values) {
                require(result.put(version.applyAsLong(value), value) == null, label + " contains a duplicate version");
            }
            return result;
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }

    public record ExpectedDefinition(
            long version, DefinitionStatus status, String documentFormat, SourceMetadata metadata) {}

    public record MetadataObservation(
            List<WorkflowDefinitionRecord> durable,
            List<WorkflowDefinitionRecord> service,
            List<CompiledDefinitionRef> compiled,
            int registeredCount) {}

    private record RegistryReadyStep(
            WorkflowRegistry registry,
            BootObservation observation,
            Pool pool,
            WorkflowDefinitionService service,
            WorkflowDefinitionStore cache)
            implements ApplicationStartupStep {

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.BOOTSTRAP;
        }

        @Override
        public int priority() {
            return Integer.MAX_VALUE;
        }

        @Override
        public String orderKey() {
            return "test-registry-ready";
        }

        @Override
        public Future<Void> start() {
            try {
                observation.definitionIsPublished(registry);
            } catch (RuntimeException | AssertionError failure) {
                return Future.failedFuture(failure);
            }
            return observation
                    .observeMetadataBeforeDeployment(pool, service, cache, registry)
                    .map(ignored -> {
                        if (observation.enabled) observation.events.add("bootstrap-complete");
                        return (Void) null;
                    });
        }
    }

    private record NoLoaderRegistryReadyStep(WorkflowRegistry registry, BootObservation observation)
            implements ApplicationStartupStep {

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.BOOTSTRAP;
        }

        @Override
        public int priority() {
            return Integer.MAX_VALUE;
        }

        @Override
        public String orderKey() {
            return "test-registry-ready";
        }

        @Override
        public Future<Void> start() {
            try {
                observation.definitionIsPublished(registry);
                if (observation.enabled) observation.events.add("bootstrap-complete");
                return Future.succeededFuture();
            } catch (RuntimeException | AssertionError failure) {
                return Future.failedFuture(failure);
            }
        }
    }

    private static final class RegistryObservedVerticle extends AbstractVerticle {

        private final WorkflowRegistry registry;
        private final BootObservation observation;

        private RegistryObservedVerticle(WorkflowRegistry registry, BootObservation observation) {
            this.registry = registry;
            this.observation = observation;
        }

        @Override
        public void start(Promise<Void> startPromise) {
            try {
                observation.definitionIsPublished(registry);
                if (observation.enabled) {
                    observation.events.add("bootstrap-verticle-deployed");
                }
                startPromise.complete();
            } catch (RuntimeException | AssertionError failure) {
                startPromise.fail(failure);
            }
        }
    }
}
