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
import dev.vertique.workflow.definition.callbacks.BranchResultReducerContributor;
import dev.vertique.workflow.definition.callbacks.FailMessageFactoryContributor;
import dev.vertique.workflow.definition.callbacks.NamedBranchResultReducer;
import dev.vertique.workflow.definition.callbacks.NamedFailMessageFactory;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.PayloadMapperContributor;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.it.fixture.FraudScreenedDoc;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderFanOutState;
import dev.vertique.workflow.definition.it.fixture.PaymentAuthorizedDoc;
import dev.vertique.workflow.definition.it.fixture.PlaceFanOutOrder;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.ActivationStatus;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
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
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * End-to-end integration test proving that a YAML-defined fan-out workflow drives the same
 * ALL_REQUIRED fork/join engine path as the code-first
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition}, without requiring
 * the example module as a dependency.
 *
 * <p>The fixture ({@code order-fulfillment-fanout.workflow.yaml}) declares a three-branch fan-out:
 * <ul>
 *   <li>Branch {@code inventory} — dispatch {@code inventory.reserve} → wait
 *       {@code inventory.reserved.doc} → complete</li>
 *   <li>Branch {@code payment} — dispatch {@code payment.authorize} → wait
 *       {@code payment.authorized.doc} → complete</li>
 *   <li>Branch {@code fraud} — dispatch {@code fraud.screen} → wait {@code fraud.screened.doc}
 *       → complete</li>
 * </ul>
 * All three branches converge at an {@code all-required} join, then a final
 * {@code shipping.create-shipment} service dispatch leads to {@code done}.
 *
 * <p>Assertions verified:
 * <ul>
 *   <li>AC #2 — a document-defined approval workflow uses parallel legal/finance review branches
 *       from PRD-WF-002 (demonstrated here with three parallel pre-fulfillment checks).</li>
 *   <li>After {@code start()}, the instance is in {@link WorkflowStatus#RUNNING} (parked at the
 *       fork join with {@code waitType=JOIN}) with all three branch tokens present in
 *       {@code workflow_branch_tokens}.</li>
 *   <li>After delivering the first branch signal, the instance remains in {@code RUNNING}
 *       (other branches still pending).</li>
 *   <li>After all three signals and the final ship step, the instance is {@code COMPLETED}.</li>
 *   <li>All three branch state fields are populated in the final workflow state.</li>
 * </ul>
 *
 * <p>This IT uses an independent Dagger component ({@code FanOutTestComponent}) from the Slice H
 * components so that the two registry populations never collide.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DocumentDefinitionFanOutIT {

    // --- Testcontainers setup ---

    /** Shared PostgreSQL container; started once by {@link DatabaseExtension}. */
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("wf_def_fanout_it")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowOperations engine;
    static TransactionalWorkflowOperations<SqlClient> txEngine;
    static WorkflowDefinitionService definitionService;

    // --- Dagger modules ---

    /**
     * Test-only Dagger module that contributes the YAML fan-out definition source.
     */
    @Module
    static class FanOutDefinitionSourceModule {

        /**
         * Contributes the YAML fan-out fixture as the sole definition source for this component.
         *
         * @return a source that returns the YAML bytes for {@code order-fulfillment-fanout-doc} v1
         */
        @Provides
        @IntoSet
        static WorkflowDefinitionSource fanOutSource() {
            return () -> {
                try (InputStream is = Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("definitions/it/order-fulfillment-fanout.workflow.yaml")) {
                    if (is == null) {
                        throw new IllegalStateException("fan-out YAML fixture not found on classpath");
                    }
                    byte[] bytes = is.readAllBytes();
                    SourceMetadata meta = new SourceMetadata(
                            "test-fanout-yaml",
                            "classpath:definitions/it/order-fulfillment-fanout.workflow.yaml",
                            null,
                            null,
                            null,
                            Instant.now());
                    return List.of(new DefinitionResource(bytes, DocumentFormat.YAML, meta));
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to load fan-out YAML fixture", e);
                }
            };
        }
    }

    /**
     * Test-only Dagger module that contributes the named callbacks required by the fan-out
     * YAML fixture: start-state mapper, payload mappers for all service targets, state reducers
     * for the three branch signals, branch-result reducer for the join, and a fail message
     * factory for the failure terminal.
     */
    @Module
    static class FanOutCallbackModule {

        /**
         * Contributes the start-state mapper that maps {@link PlaceFanOutOrder} to
         * {@link OrderFanOutState}.
         *
         * @return the start-state mapper contributor
         */
        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMapper() {
            return b -> b.register(new NamedStartStateMapper<>(
                    "fanout.doc.fromPlaceFanOutOrder",
                    PlaceFanOutOrder.class,
                    OrderFanOutState.class,
                    OrderFanOutState::fromPayload));
        }

        /**
         * Contributes payload mappers for the four service targets: inventory.reserve,
         * payment.authorize, fraud.screen, and shipping.create-shipment.
         *
         * @return the payload mapper contributor
         */
        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static PayloadMapperContributor payloadMappers() {
            return b -> {
                b.register(new NamedPayloadMapper<>(
                        "fanout.doc.inventoryPayload", OrderFanOutState.class, s -> Map.of("orderId", s.orderId())));
                b.register(new NamedPayloadMapper<>(
                        "fanout.doc.paymentPayload", OrderFanOutState.class, s -> Map.of("orderId", s.orderId())));
                b.register(new NamedPayloadMapper<>(
                        "fanout.doc.fraudPayload", OrderFanOutState.class, s -> Map.of("orderId", s.orderId())));
                b.register(new NamedPayloadMapper<>(
                        "fanout.doc.shipmentPayload", OrderFanOutState.class, s -> Map.of("orderId", s.orderId())));
            };
        }

        /**
         * Contributes state reducers for the three branch signal steps.
         *
         * @return the state reducer contributor
         */
        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static StateReducerContributor stateReducers() {
            return b -> {
                b.register(new NamedStateReducer<>(
                        "fanout.doc.applyInventoryReserved",
                        OrderFanOutState.class,
                        (s, sig) -> OrderFanOutState.applyInventoryReserved(
                                (OrderFanOutState) s, coerce(sig, InventoryReservedDoc.class))));
                b.register(new NamedStateReducer<>(
                        "fanout.doc.applyPaymentAuthorized",
                        OrderFanOutState.class,
                        (s, sig) -> OrderFanOutState.applyPaymentAuthorized(
                                (OrderFanOutState) s, coerce(sig, PaymentAuthorizedDoc.class))));
                b.register(new NamedStateReducer<>(
                        "fanout.doc.applyFraudScreened",
                        OrderFanOutState.class,
                        (s, sig) -> OrderFanOutState.applyFraudScreened(
                                (OrderFanOutState) s, coerce(sig, FraudScreenedDoc.class))));
            };
        }

        /**
         * Contributes the branch-result reducer for the {@code all-reservations} join node.
         *
         * @return the branch-result reducer contributor
         */
        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static BranchResultReducerContributor branchResultReducer() {
            return b -> b.register(new NamedBranchResultReducer<>(
                    "fanout.doc.mergeBranchResults", OrderFanOutState.class, OrderFanOutState::mergeBranchResults));
        }

        /**
         * Contributes the fail message factory for the {@code order-failed} terminal step.
         *
         * @return the fail message factory contributor
         */
        @Provides
        @IntoSet
        @SuppressWarnings({"rawtypes", "unchecked"})
        static FailMessageFactoryContributor failMessageFactory() {
            return b -> b.register(new NamedFailMessageFactory<>(
                    "fanout.doc.failureMessage",
                    OrderFanOutState.class,
                    state -> "Fan-out order fulfillment failed for orderId=" + state.orderId()));
        }
    }

    /**
     * Test-only Dagger module providing the infrastructure bindings required by
     * {@link WorkflowPostgresqlModule}: {@link Pool}, {@link Clock},
     * {@link WorkflowPlanValidator}, and the no-op SERVICE side-effect recorder.
     */
    @Module
    static class FanOutInfrastructureModule {

        private final Pool pool;

        /**
         * Constructs the module with the pre-built connection pool.
         *
         * @param pool the Vert.x SQL pool; must not be {@code null}
         */
        FanOutInfrastructureModule(Pool pool) {
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
         * Provides the {@link WorkflowPlanValidator} that checks fork/join pairing.
         *
         * @return the plan validator
         */
        @Provides
        @Singleton
        WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        /**
         * Provides the PostgreSQL exception mapper.
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
         * (inventory.reserve, payment.authorize, fraud.screen, shipping.create-shipment) without
         * requiring a real outbox infrastructure in the test.
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
     * Dagger component for the fan-out IT — independent from the Slice H components.
     *
     * <p>Installing {@link WorkflowDefinitionModule} (which auto-bootstraps definitions from the
     * registered {@link WorkflowDefinitionSource} set) with {@link WorkflowPostgresqlModule}
     * (which includes {@link dev.vertique.workflow.di.WorkflowCoreModule}) gives a fully wired
     * engine. The test modules contribute the YAML source, named callbacks, and infrastructure.
     */
    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                dev.vertique.context.ContextRuntimeModule.class,
                LoggingContextModule.class,
                WorkflowPostgresqlModule.class,
                FanOutDefinitionSourceModule.class,
                FanOutCallbackModule.class,
                FanOutInfrastructureModule.class
            })
    interface FanOutTestComponent {

        /**
         * Returns the workflow operations facade backed by the PostgreSQL engine.
         *
         * @return workflow operations
         */
        WorkflowOperations workflowOperations();

        /**
         * Returns the transactional workflow operations, required for branch-aware signal delivery.
         *
         * @return transactional workflow operations
         */
        TransactionalWorkflowOperations<SqlClient> transactionalWorkflowOperations();

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
    private static Vertx vertxRef;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        vertxRef = vertx;
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

        FanOutTestComponent component = DaggerDocumentDefinitionFanOutIT_FanOutTestComponent.builder()
                .fanOutInfrastructureModule(new FanOutInfrastructureModule(pool))
                .build();

        engine = component.workflowOperations();
        txEngine = component.transactionalWorkflowOperations();
        definitionService = component.workflowDefinitionService();

        ctx.completeNow();
    }

    /** Truncates all workflow tables before each test so tests are isolated. */
    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_history, workflow_dedup, workflow_instances"
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
     * Drives the document-defined fan-out workflow from start to COMPLETED by manually sending
     * three branch signals (one per branch) and asserting intermediate and final state.
     *
     * <p>Verifies AC #2: a document-defined workflow uses parallel branches from PRD-WF-002.
     * After start, the instance is in RUNNING (parked at the fork join with waitType=JOIN)
     * with all three branch tokens present. Delivering each branch signal one at a time keeps
     * the instance in RUNNING until all three branches are done and the join fires.
     * After the final ship step the instance is COMPLETED and all three branch state fields
     * are populated.
     */
    @Test
    @DisplayName("AC #2 — document-defined fan-out drives ALL_REQUIRED branches to COMPLETED via branch signals")
    void documentDefinedFanOut_allRequiredBranches_completesAfterThreeSignals(VertxTestContext ctx) {
        PlaceFanOutOrder payload = new PlaceFanOutOrder("fanout-" + UUID.randomUUID());
        StartCommand cmd =
                new StartCommand("order-fulfillment-fanout-doc", payload, payload.idempotencyKey(), null, null);

        // Re-enter on a duplicated Vert.x context so the entire async chain — including the
        // pool.withTransaction(tx -> txEngine.signal(...)) bodies that hit
        // DurableContextPropagator.bindFrom in branch drive — runs on a duplicate. Production
        // dispatch entries (event-bus consumer, Kafka per-record, cron callback, service-method
        // invoker) always reach the engine via a duplicate; this IT calls the engine directly
        // from a JUnit thread so it must establish the duplicate itself before the substrate
        // write guard would otherwise reject the bind (FR-CTX-157b).
        io.vertx.core.internal.ContextInternal dup =
                ((io.vertx.core.internal.ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        dup.runOnContext(unused -> engine.start(cmd)
                .compose(id ->
                        // After start: all three branches are parked at their wait-signal nodes.
                        // The parent instance is RUNNING (waitType=JOIN) — it has not reached a
                        // terminal wait; it is alive but blocked on the join barrier.
                        countBranchTokens(id)
                                .compose(branchCount -> {
                                    assertEquals(3, branchCount, "three branch tokens must be created after start");
                                    return engine.query(id);
                                })
                                .compose(view -> {
                                    assertEquals(
                                            WorkflowStatus.RUNNING,
                                            view.instance().status(),
                                            "instance must be RUNNING (at fork join) while branches are pending");
                                    WorkflowInstanceId id2 = view.instance().id();
                                    // Deliver inventory branch signal; instance should still be RUNNING
                                    // because payment and fraud branches have not yet completed.
                                    return pool.withTransaction(tx -> txEngine.signal(
                                                    id2,
                                                    "inventory.reserved.doc",
                                                    new InventoryReservedDoc("res-" + UUID.randomUUID()),
                                                    "inv-dedup-" + UUID.randomUUID(),
                                                    "reserve-order",
                                                    "inventory",
                                                    tx))
                                            .compose(v -> engine.query(id2))
                                            .compose(afterFirst -> {
                                                assertEquals(
                                                        WorkflowStatus.RUNNING,
                                                        afterFirst.instance().status(),
                                                        "instance must still be RUNNING after first branch signal");
                                                return Future.succeededFuture(id2);
                                            });
                                })
                                // Deliver payment branch signal; instance should still be RUNNING
                                // because fraud branch has not yet completed.
                                .compose(id2 -> pool.withTransaction(tx -> txEngine.signal(
                                                id2,
                                                "payment.authorized.doc",
                                                new PaymentAuthorizedDoc("charge-" + UUID.randomUUID()),
                                                "pay-dedup-" + UUID.randomUUID(),
                                                "reserve-order",
                                                "payment",
                                                tx))
                                        .compose(v -> engine.query(id2))
                                        .compose(afterSecond -> {
                                            assertEquals(
                                                    WorkflowStatus.RUNNING,
                                                    afterSecond.instance().status(),
                                                    "instance must still be RUNNING after second branch signal");
                                            return Future.succeededFuture(id2);
                                        }))
                                // Deliver fraud branch signal; all branches done → join fires → ship → COMPLETED.
                                .compose(id2 -> pool.withTransaction(tx -> txEngine.signal(
                                                id2,
                                                "fraud.screened.doc",
                                                new FraudScreenedDoc(true),
                                                "fraud-dedup-" + UUID.randomUUID(),
                                                "reserve-order",
                                                "fraud",
                                                tx))
                                        .map(v -> id2))
                                .compose(id2 -> engine.query(id2)))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "fan-out saga must succeed end-to-end, got: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertNotNull(view, "view must not be null");
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            view.instance().status(),
                            "instance must reach COMPLETED after all branch signals and ship step");
                    assertEquals(
                            1L,
                            view.instance().definitionVersion(),
                            "DB definitionVersion must match YAML-declared version 1");

                    // Assert all three branch outputs are present in the final state.
                    OrderFanOutState finalState =
                            Json.decodeValue(view.instance().stateJson(), OrderFanOutState.class);
                    assertNotNull(
                            finalState.inventoryReservationId(),
                            "inventoryReservationId must be set after inventory branch signal");
                    assertNotNull(
                            finalState.paymentChargeId(), "paymentChargeId must be set after payment branch signal");
                    assertNotNull(finalState.fraudCleared(), "fraudCleared must be set after fraud branch signal");

                    ctx.completeNow();
                })));
    }

    /**
     * Verifies that the compiled {@code planHash} for the YAML-defined fan-out workflow is
     * queryable via {@link WorkflowDefinitionService#list()} after bootstrap.
     */
    @Test
    @DisplayName("planHash is accessible via WorkflowDefinitionService.list() after fan-out bootstrap")
    void fanOutDefinitionPlanHashIsAccessibleViaService(VertxTestContext ctx) {
        Collection<CompiledDefinitionRef> refs = definitionService.list();
        ctx.verify(() -> {
            assertEquals(1, refs.size(), "exactly one definition must be in the fan-out component's service");
            CompiledDefinitionRef ref = refs.iterator().next();
            assertEquals("order-fulfillment-fanout-doc", ref.definitionId());
            assertEquals(1L, ref.definitionVersion());
            assertEquals(ActivationStatus.ACTIVE, ref.status());
            assertNotNull(ref.planHash(), "planHash must not be null");
            assertTrue(!ref.planHash().isBlank(), "planHash must not be blank");
            ctx.completeNow();
        });
    }

    // --- Private helpers ---

    /**
     * Counts the number of branch token rows for the given workflow instance.
     *
     * @param id the workflow instance id
     * @return a {@code Future} resolving to the number of branch token rows
     */
    private Future<Long> countBranchTokens(WorkflowInstanceId id) {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_branch_tokens WHERE workflow_id = $1")
                .execute(Tuple.of(id.value()))
                .map(rs -> rs.iterator().next().getLong(0));
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
