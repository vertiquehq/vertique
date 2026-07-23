// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.PayloadMapperContributor;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.PaymentCapturedDoc;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.it.fixture.ShipmentCreatedDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end integration test proving a YAML-defined workflow document executes through the same
 * {@link dev.vertique.workflow.postgresql.engine.PgWorkflowEngine} as code-first definitions, with
 * no engine awareness of the document syntax.
 *
 * <p>The test fixture ({@code order-fulfillment.workflow.yaml}) declares a saga with:
 * <ul>
 *   <li>3 service dispatches: {@code reserve-inventory}, {@code authorize-payment},
 *       {@code create-shipment} — each with a no-op side-effect recorder</li>
 *   <li>2 compensation steps: {@code release-inventory}, {@code void-authorization}</li>
 *   <li>3 wait-signal steps: {@code wait-inventory}, {@code wait-payment}, {@code wait-shipment}</li>
 *   <li>1 complete step: {@code done}</li>
 * </ul>
 *
 * <p>Signals are manually driven via {@link WorkflowOperations#signal} so the test is free of
 * external service infrastructure.
 *
 * <p>Assertions verified:
 * <ul>
 *   <li>AC #1 — document-defined workflow executes end-to-end via the same engine as code-first.</li>
 *   <li>AC #4 — instance reaches {@link WorkflowStatus#COMPLETED}.</li>
 *   <li>AC #9 — engine never imports the definition schema or parser packages (verified by a
 *       grep gate documented in the commit message).</li>
 * </ul>
 *
 * <p>This IT uses an independent Dagger component (component A) from
 * {@link DocumentDefinitionEndToEndJsonIT} (component B). Version 1 of
 * {@code order-fulfillment-doc} is registered here; version 2 is registered in the JSON IT.
 * The registry duplicate-rejection applies per instance — the two ITs share no state.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DocumentDefinitionEndToEndYamlIT {

    // --- Testcontainers setup ---

    /** Shared PostgreSQL container; started once by {@link DatabaseExtension}. */
    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("wf_def_yaml_it").withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowOperations engine;
    static WorkflowDefinitionService definitionService;

    // --- Dagger component A ---

    /**
     * Test-only Dagger module that contributes the YAML definition source (version 1).
     */
    @Module
    static class YamlDefinitionSourceModule {

        /**
         * Contributes the YAML order-fulfillment fixture as the sole definition source.
         *
         * @return a source that returns the YAML bytes for {@code order-fulfillment-doc} v1
         */
        @Provides
        @IntoSet
        static WorkflowDefinitionSource yamlSource() {
            return () -> {
                try (InputStream is = Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("definitions/it/order-fulfillment.workflow.yaml")) {
                    if (is == null) {
                        throw new IllegalStateException("YAML fixture not found on classpath");
                    }
                    byte[] bytes = is.readAllBytes();
                    SourceMetadata meta = new SourceMetadata(
                            "test-yaml",
                            "classpath:definitions/it/order-fulfillment.workflow.yaml",
                            null,
                            null,
                            null,
                            Instant.now());
                    return List.of(new DefinitionResource(bytes, DocumentFormat.YAML, meta));
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to load YAML fixture", e);
                }
            };
        }
    }

    /**
     * Test-only Dagger module that contributes the named callbacks required by the YAML fixture.
     */
    @Module
    static class YamlCallbackModule {

        /**
         * Contributes the start-state mapper that maps {@link PlaceOrderDoc} to
         * {@link OrderDocState}.
         *
         * @return the start-state mapper contributor
         */
        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMapper() {
            return b -> b.register(new NamedStartStateMapper<>(
                    "doc.order.fromPlaceOrder", PlaceOrderDoc.class, OrderDocState.class, OrderDocState::fromPayload));
        }

        /**
         * Contributes the payload mappers for all five service/compensation targets.
         *
         * @return the payload mapper contributor
         */
        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static PayloadMapperContributor payloadMappers() {
            return b -> {
                b.register(new NamedPayloadMapper<>(
                        "doc.order.reserveInventoryPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId())));
                b.register(new NamedPayloadMapper<>(
                        "doc.order.releaseInventoryPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of(
                                "orderId",
                                s.orderId(),
                                "reservationId",
                                s.reservationId() != null ? s.reservationId() : "")));
                b.register(new NamedPayloadMapper<>(
                        "doc.order.authorizePaymentPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId())));
                b.register(new NamedPayloadMapper<>(
                        "doc.order.voidAuthorizationPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId(), "authId", s.authId() != null ? s.authId() : "")));
                b.register(new NamedPayloadMapper<>(
                        "doc.order.createShipmentPayload",
                        OrderDocState.class,
                        s -> java.util.Map.of("orderId", s.orderId())));
            };
        }

        /**
         * Contributes the state reducers that fold signal payloads into {@link OrderDocState}.
         *
         * @return the state reducer contributor
         */
        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static StateReducerContributor stateReducers() {
            return b -> {
                b.register(new NamedStateReducer<>(
                        "doc.order.applyInventoryReserved",
                        OrderDocState.class,
                        (s, sig) -> OrderDocState.applyInventoryReserved(
                                (OrderDocState) s, coerce(sig, InventoryReservedDoc.class))));
                b.register(new NamedStateReducer<>(
                        "doc.order.applyPaymentCaptured",
                        OrderDocState.class,
                        (s, sig) -> OrderDocState.applyPaymentCaptured(
                                (OrderDocState) s, coerce(sig, PaymentCapturedDoc.class))));
                b.register(new NamedStateReducer<>(
                        "doc.order.applyShipmentCreated",
                        OrderDocState.class,
                        (s, sig) -> OrderDocState.applyShipmentCreated(
                                (OrderDocState) s, coerce(sig, ShipmentCreatedDoc.class))));
            };
        }
    }

    /**
     * Test-only Dagger module providing the infrastructure bindings required by
     * {@link WorkflowPostgresqlModule}: {@link Pool}, {@link Clock}, and
     * {@link WorkflowPlanValidator}.
     */
    @Module
    static class YamlInfrastructureModule {

        private final Pool pool;

        /**
         * Constructs the module with the pre-built connection pool.
         *
         * @param pool the Vert.x SQL pool; must not be {@code null}
         */
        YamlInfrastructureModule(Pool pool) {
            this.pool = pool;
        }

        /**
         * Provides the PostgreSQL connection pool.
         *
         * @return the pool
         */
        @Provides
        @Singleton
        Pool pool() {
            return pool;
        }

        /**
         * Provides the system UTC clock.
         *
         * @return the clock
         */
        @Provides
        @Singleton
        Clock clock() {
            return Clock.systemUTC();
        }

        /**
         * Provides the {@link WorkflowPlanValidator} that checks fork/join pairing
         * and race-safety target configuration.
         *
         * @return the plan validator
         */
        @Provides
        @Singleton
        WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        /**
         * Provides the PostgreSQL exception mapper used by the workflow repositories.
         *
         * @return the exception mapper
         */
        @Provides
        @Singleton
        PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }

        /**
         * Contributes a no-op {@code SERVICE} recorder so the engine can dispatch service nodes
         * without requiring a real outbox infrastructure in the test.
         *
         * @return a no-op recorder for {@link IntentKind#SERVICE}
         */
        @Provides
        @IntoSet
        @WorkflowRecorders
        @Singleton
        static WorkflowSideEffectRecorder<SqlClient> noOpServiceRecorder() {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.SERVICE;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
        }
    }

    /**
     * Dagger component A — independent stack for the YAML end-to-end test.
     *
     * <p>Installing {@link WorkflowDefinitionModule} plus callback and source test modules gives the
     * bootstrap everything it needs. Installing {@link WorkflowPostgresqlModule} (which includes
     * {@link dev.vertique.workflow.di.WorkflowCoreModule}) provides the PostgreSQL-backed engine.
     */
    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                dev.vertique.context.ContextRuntimeModule.class,
                LoggingContextModule.class,
                WorkflowPostgresqlModule.class,
                YamlDefinitionSourceModule.class,
                YamlCallbackModule.class,
                YamlInfrastructureModule.class
            })
    interface YamlTestComponent {

        /**
         * Returns the workflow operations facade backed by the PostgreSQL engine.
         *
         * @return workflow operations
         */
        WorkflowOperations workflowOperations();

        /**
         * Returns the definition service for querying compiled definition refs.
         *
         * @return the definition service
         */
        WorkflowDefinitionService workflowDefinitionService();
    }

    // --- Bootstrap ---

    /**
     * Builds the pool, then constructs the Dagger component before tests run.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx   the test context used to signal completion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig cfg = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(cfg.host())
                        .setPort(cfg.port())
                        .setDatabase(cfg.database())
                        .setUser(cfg.user())
                        .setPassword(cfg.password()))
                .using(vertx)
                .build();

        YamlTestComponent component = DaggerDocumentDefinitionEndToEndYamlIT_YamlTestComponent.builder()
                .yamlInfrastructureModule(new YamlInfrastructureModule(pool))
                .build();

        engine = component.workflowOperations();
        definitionService = component.workflowDefinitionService();

        ctx.completeNow();
    }

    /** Truncates all workflow tables before each test so tests are isolated. */
    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    /**
     * Drives the YAML-defined order-fulfillment saga from start to COMPLETED by manually sending
     * the three expected signals.
     *
     * <p>Verifies AC #1 (document-defined workflow executes via the engine), AC #4 (instance
     * reaches COMPLETED), and that the definition version recorded in the DB matches the
     * YAML-declared version (1).
     */
    @Test
    @DisplayName("YAML-defined order fulfillment runs to COMPLETED through manual signal chain")
    void endToEnd_yamlOrderFulfillment_completesThroughSignalChain(VertxTestContext ctx) {
        PlaceOrderDoc payload = new PlaceOrderDoc("yaml-order-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("order-fulfillment-doc", payload, payload.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(this::driveToCompletion)
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "saga must succeed end-to-end, got: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertNotNull(view, "view must not be null");
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "instance must reach COMPLETED");
                    assertEquals(
                            1L,
                            view.instance().definitionVersion(),
                            "DB definitionVersion must match YAML-declared version 1");
                    OrderDocState finalState = Json.decodeValue(view.instance().stateJson(), OrderDocState.class);
                    assertNotNull(finalState.reservationId(), "reservationId must be set after inventory signal");
                    assertNotNull(finalState.authId(), "authId must be set after payment signal");
                    assertNotNull(finalState.trackingNumber(), "trackingNumber must be set after shipment signal");
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that the compiled {@code planHash} for the YAML-defined workflow is queryable via
     * {@link WorkflowDefinitionService#list()} after bootstrap.
     */
    @Test
    @DisplayName("planHash is accessible via WorkflowDefinitionService.list() after YAML bootstrap")
    void yamlDefinitionPlanHashIsAccessibleViaService(VertxTestContext ctx) {
        Collection<CompiledDefinitionRef> refs = definitionService.list();
        ctx.verify(() -> {
            assertEquals(1, refs.size(), "exactly one definition must be in the service");
            CompiledDefinitionRef ref = refs.iterator().next();
            assertEquals("order-fulfillment-doc", ref.definitionId());
            assertEquals(1L, ref.definitionVersion());
            assertEquals(ActivationStatus.ACTIVE, ref.status());
            assertNotNull(ref.planHash(), "planHash must not be null");
            assertTrue(!ref.planHash().isBlank(), "planHash must not be blank");
            ctx.completeNow();
        });
    }

    // --- Private helpers ---

    /**
     * Drives the saga instance through the three wait-signal steps sequentially.
     *
     * @param id the workflow instance id to drive
     * @return a {@code Future} that completes with the instance id after all signals are delivered
     */
    private Future<WorkflowInstanceId> driveToCompletion(WorkflowInstanceId id) {
        return engine.signal(id, "inventory.reserved", new InventoryReservedDoc("res-123"), "inv-" + UUID.randomUUID())
                .compose(v -> engine.signal(
                        id, "payment.captured", new PaymentCapturedDoc("auth-456"), "pay-" + UUID.randomUUID()))
                .compose(v -> engine.signal(
                        id, "shipment.created", new ShipmentCreatedDoc("track-789"), "ship-" + UUID.randomUUID()))
                .map(v -> id);
    }

    /**
     * Coerces a signal payload to the target type using a Vert.x JSON round-trip when needed.
     *
     * @param <T>        the target type
     * @param payload    the raw signal payload
     * @param targetType the target class
     * @return the coerced value
     */
    @SuppressWarnings("unchecked")
    static <T> T coerce(Object payload, Class<T> targetType) {
        if (targetType.isInstance(payload)) {
            return (T) payload;
        }
        return Json.decodeValue(Json.encode(payload), targetType);
    }
}
