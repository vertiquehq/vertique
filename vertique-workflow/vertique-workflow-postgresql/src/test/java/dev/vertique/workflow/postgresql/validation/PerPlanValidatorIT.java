// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.validation;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.PayloadMapperContributor;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/**
 * Verifies that the reminder check and the service-dispatch checks run on every plan the registry builds, not only on
 * the plans that are registered when the application graph is constructed: a document that is loaded and published at
 * runtime is refused when it fails a check, and a graph whose plans dispatch to no service starts without a
 * {@code SERVICE} outbox handler.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PerPlanValidatorIT {

    /** The one contract that the code definition and every document of these tests name. */
    @WorkflowContract
    public interface PerPlanContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_per_plan_validator_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Instant LOADED_AT = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    @DisplayName("a document with reminders is refused at runtime when no recorder carries workflow events")
    void runtimeDocumentWithRemindersIsRefusedWithoutAnEventRecorder() {
        Vertx vertx = Vertx.vertx();
        Pool pool = openPool(vertx);
        VertiqueApplicationHandle<PlainApp> handle = null;
        try {
            // Given a graph with the engine, PostgreSQL and definition modules and no WORKFLOW_EVENT recorder,
            // started with a code definition that declares no reminder
            PlainApp app = DaggerPerPlanValidatorIT_PlainApp.builder()
                    .vertxModule(new VertxModule(vertx, new JsonObject()))
                    .infrastructure(new Infrastructure(pool))
                    .parserModule(new WorkflowDefinitionParserProbe().module())
                    .build();
            handle = await(
                    VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app));
            WorkflowDefinitionService service = app.definitionService();
            DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, "escalate", 1);

            // When the document escalate v1, whose human task review declares a reminder, is loaded and published
            // in the default scope
            Throwable refusal = failureOf(
                    load(service, "escalate", reminderDocument("escalate")).compose(ignored -> service.publish(key)));

            // Then the document is refused with the offender text of the reminder check
            assertThat(refusal).isNotNull();
            assertThat(refusal.getMessage()).contains("(escalate v1 review)");

            // And no registration resolves for the key, and its row is not activated
            assertThat(app.workflowRegistry().find(key)).isEmpty();
            assertThat(await(app.definitionRegistryStore().find(key)).map(record -> record.status()))
                    .isNotEqualTo(Optional.of(DefinitionStatus.AVAILABLE));
            assertThat(await(service.activations(null)))
                    .noneMatch(activation -> activation.definitionId().equals("escalate"));
        } finally {
            close(handle, pool, vertx);
        }
    }

    @Test
    @DisplayName("a document that dispatches to an unknown service target is refused at runtime")
    void runtimeDocumentWithAnUnknownServiceTargetIsRefused() {
        Vertx vertx = Vertx.vertx();
        Pool pool = openPool(vertx);
        VertiqueApplicationHandle<ServicesApp> withHandler = null;
        VertiqueApplicationHandle<ServicesApp> withoutHandler = null;
        try {
            // Given a graph with the services module, a SERVICE handler and a target resolver that knows
            // billing.charge only
            ServicesApp first = servicesGraph(vertx, pool, Set.of(new StubServiceHandler()));
            withHandler = await(
                    VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> first));
            WorkflowDefinitionService service = first.definitionService();

            // When ship v1, which dispatches to billing.refund, is loaded and published
            DefinitionKey ship = new DefinitionKey(WorkflowScopes.DEFAULT, "ship", 1);
            Throwable unknownTarget = failureOf(load(service, "ship", serviceDocument("ship", "billing.refund"))
                    .compose(ignored -> service.publish(ship)));

            // Then it is refused with a message naming the definition, version, step and target
            assertThat(unknownTarget).isNotNull();
            assertThat(unknownTarget.getMessage())
                    .contains("'ship' v1 step 'dispatch'")
                    .contains("'billing.refund'");
            assertThat(first.workflowRegistry().find(ship)).isEmpty();

            // When settle v1, which dispatches to billing.charge, is loaded and published
            DefinitionKey settle = new DefinitionKey(WorkflowScopes.DEFAULT, "settle", 1);
            await(load(service, "settle", serviceDocument("settle", "billing.charge"))
                    .compose(ignored -> service.publish(settle)));

            // Then it is available and its plan builds
            assertThat(await(first.workflowRegistry().load(settle))).isNotNull();

            // And the first graph is shut down and the published row removed, so that the next graph, which has no
            // SERVICE handler, does not restore it
            await(withHandler.shutdown());
            withHandler = null;
            await(pool.query("DELETE FROM workflow_definitions WHERE definition_id IN ('ship', 'settle')")
                    .execute());

            // Given a graph with the services module but no SERVICE handler, started with a code definition that
            // dispatches to no service
            ServicesApp second = servicesGraph(vertx, pool, Set.of(new StubDelayedJobHandler()));
            withoutHandler = await(
                    VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> second));

            // When a document notify v1 that dispatches to billing.charge is loaded and published
            DefinitionKey notify = new DefinitionKey(WorkflowScopes.DEFAULT, "notify", 1);
            Throwable missingHandler =
                    failureOf(load(second.definitionService(), "notify", serviceDocument("notify", "billing.charge"))
                            .compose(ignored -> second.definitionService().publish(notify)));

            // Then the graph started, and the document is refused for the missing SERVICE handler
            assertThat(missingHandler).isNotNull();
            assertThat(missingHandler.getMessage()).contains("requires a SERVICE OutboxDestinationHandler");
            assertThat(second.workflowRegistry().find(notify)).isEmpty();
        } finally {
            try {
                close(withHandler, null, null);
            } finally {
                close(withoutHandler, pool, vertx);
            }
        }
    }

    // --- Graph ---

    /** What the tests read from the started graph. */
    interface Graph extends VertiqueApplicationComponent {

        WorkflowDefinitionService definitionService();

        WorkflowRegistry workflowRegistry();

        WorkflowDefinitionRegistryStore definitionRegistryStore();
    }

    /** The engine, PostgreSQL and definition modules, with no module that records workflow events. */
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
                WorkflowDefinitionParserProbe.ParserModule.class,
                Infrastructure.class,
                Catalog.class,
                WorkflowSchemaModule.class,
                ManagedDefinitionDatabase.SchemaBindings.class
            })
    interface PlainApp extends Graph {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder infrastructure(Infrastructure module);

            Builder parserModule(WorkflowDefinitionParserProbe.ParserModule module);

            PlainApp build();
        }
    }

    /** The same graph with the services module added. */
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
                WorkflowDefinitionParserProbe.ParserModule.class,
                Infrastructure.class,
                Catalog.class,
                WorkflowServicesModule.class,
                ServiceBindings.class,
                WorkflowSchemaModule.class,
                ManagedDefinitionDatabase.SchemaBindings.class
            })
    interface ServicesApp extends Graph {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder infrastructure(Infrastructure module);

            Builder parserModule(WorkflowDefinitionParserProbe.ParserModule module);

            Builder serviceBindings(ServiceBindings module);

            ServicesApp build();
        }
    }

    private static ServicesApp servicesGraph(Vertx vertx, Pool pool, Set<OutboxDestinationHandler> handlers) {
        return DaggerPerPlanValidatorIT_ServicesApp.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .infrastructure(new Infrastructure(pool))
                .parserModule(new WorkflowDefinitionParserProbe().module())
                .serviceBindings(new ServiceBindings(handlers))
                .build();
    }

    /** The pool, the clock and the recorders of a graph that has neither an events module nor a services module. */
    @Module
    static final class Infrastructure {

        private final Pool pool;

        Infrastructure(Pool pool) {
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
    }

    /** The type names, callbacks, code definition and sources that the documents of these tests use. */
    @Module
    static final class Catalog {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog typeCatalog() {
            List<WorkflowTypeCatalogEntry> entries = List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, PerPlanContract.class.getName(), PerPlanContract.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, OrderDocState.class.getName(), OrderDocState.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, PlaceOrderDoc.class.getName(), PlaceOrderDoc.class));
            return () -> entries;
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "doc.order.fromPlaceOrder", PlaceOrderDoc.class, OrderDocState.class, OrderDocState::fromPayload));
        }

        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static PayloadMapperContributor payloadMapper() {
            return builder -> builder.register(new NamedPayloadMapper<>(
                    "doc.perplan.payload", OrderDocState.class, state -> Map.of("orderId", state.orderId())));
        }

        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static StateReducerContributor stateReducer() {
            return builder -> builder.register(
                    new NamedStateReducer<>("doc.order.keepState", OrderDocState.class, (state, decision) -> state));
        }

        @Provides
        @IntoSet
        static WorkflowDefinitionSource noPackagedDocuments() {
            return List::of;
        }

        @Provides
        @IntoSet
        static WorkflowContributor codeDefinition() {
            return registry -> registry.register(new CodeWorkflow());
        }
    }

    /** The outbox and service collaborators of the services module, with the given destination handlers. */
    @Module
    static final class ServiceBindings {

        private final Set<OutboxDestinationHandler> handlers;

        ServiceBindings(Set<OutboxDestinationHandler> handlers) {
            this.handlers = Set.copyOf(handlers);
        }

        @Provides
        Set<OutboxDestinationHandler> handlers() {
            return handlers;
        }

        @Provides
        @Singleton
        static OutboxService outboxService() {
            return Mockito.mock(OutboxService.class);
        }

        @Provides
        @Singleton
        static InboxService inboxService() {
            return Mockito.mock(InboxService.class);
        }

        /** A resolver that knows billing.charge only. */
        @Provides
        @Singleton
        static ServiceTargetResolver targetResolver() {
            return new BillingChargeResolver();
        }
    }

    // --- Fixtures ---

    private record CodeState(String value) {}

    private record CodeStart(String value) {}

    /** A code definition that declares no reminder and dispatches to no service. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) PerPlanContract.class;
        }

        @Override
        public Class<CodeState> stateType() {
            return CodeState.class;
        }

        @Override
        public String definitionId() {
            return "per-plan-code";
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<CodeState> workflow) {
            workflow.init(CodeStart.class, start -> new CodeState(start.value()))
                    .initialStep("done")
                    .complete("done");
        }
    }

    private static byte[] reminderDocument(String id) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: review
                steps:
                  - id: review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewers
                    decisions:
                      - name: approve
                        payloadType: %s
                        applicator: doc.order.keepState
                        next: done
                    reminders:
                      interval: PT1H
                  - id: done
                    type: complete
                """.formatted(
                        id,
                        OrderDocState.class.getName(),
                        PerPlanContract.class.getName(),
                        PlaceOrderDoc.class.getName(),
                        PlaceOrderDoc.class.getName())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] serviceDocument(String id, String target) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: dispatch
                steps:
                  - id: dispatch
                    type: service
                    target: %s
                    payloadMapper: doc.perplan.payload
                    next: done
                  - id: done
                    type: complete
                """.formatted(
                        id,
                        OrderDocState.class.getName(),
                        PerPlanContract.class.getName(),
                        PlaceOrderDoc.class.getName(),
                        target)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A resolver whose only target is a request/reply operation that returns nothing and takes one payload. */
    private static final class BillingChargeResolver implements ServiceTargetResolver {

        private interface Contract {
            Object charge(Object payload);
        }

        @Override
        public ResolvedServiceTarget resolve(String targetId) {
            if (!"billing.charge".equals(targetId)) throw new IllegalArgumentException("not registered: " + targetId);
            ServiceMethodDescriptor descriptor;
            try {
                descriptor = ServiceMethodDescriptor.of(Contract.class.getMethod("charge", Object.class));
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            ServiceMethodMeta meta = ServiceMethodMeta.ofDirect(
                    new Object(),
                    descriptor,
                    "test/address",
                    targetId,
                    "test",
                    "billing",
                    "charge",
                    Object.class,
                    Void.class,
                    List.of(new ParamMeta("payload", ParamSource.PAYLOAD, Object.class, null)),
                    ResilienceAnnotations.NONE,
                    List.of(),
                    List.of(),
                    false);
            return new ResolvedServiceTarget(
                    targetId, Contract.class, "test", "billing", "charge", meta, "test/address");
        }

        @Override
        public ResolvedServiceTarget resolve(Class<?> contract, java.lang.reflect.Method method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResolvedServiceTarget resolve(Class<?> contract, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> supportedTargetIds() {
            return Set.of("billing.charge");
        }
    }

    private static final class StubServiceHandler extends StubHandler {
        @Override
        public DestinationType destinationType() {
            return DestinationType.SERVICE;
        }
    }

    private static final class StubDelayedJobHandler extends StubHandler {
        @Override
        public DestinationType destinationType() {
            return DestinationType.DELAYED_JOB;
        }
    }

    private abstract static class StubHandler implements OutboxDestinationHandler {
        @Override
        public ClaimScope claimScope() {
            return ClaimScope.all();
        }

        @Override
        public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
            return Future.succeededFuture(OutboxPublishResult.success());
        }
    }

    // --- Helpers ---

    private static Future<?> load(WorkflowDefinitionService service, String label, byte[] document) {
        return service.load(
                WorkflowScopes.DEFAULT,
                document,
                DocumentFormat.YAML,
                new SourceMetadata(
                        "digest-test", "memory:" + label, "revision-" + label, "test-suite", null, LOADED_AT));
    }

    private static Pool openPool(Vertx vertx) {
        var config = db.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    /** Shuts the application down when it started, then closes the pool and Vert.x when they are given. */
    private static void close(VertiqueApplicationHandle<?> handle, Pool pool, Vertx vertx) {
        try {
            if (handle != null) await(handle.shutdown());
        } finally {
            try {
                if (pool != null) await(pool.close());
            } finally {
                if (vertx != null) await(vertx.close());
            }
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within ten seconds", e);
        }
    }

    /** The failure of the future, whether it failed or the call that built it threw; {@code null} when it succeeded. */
    private static Throwable failureOf(Future<?> future) {
        Future<Throwable> outcome =
                future.transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null));
        return await(outcome);
    }
}
