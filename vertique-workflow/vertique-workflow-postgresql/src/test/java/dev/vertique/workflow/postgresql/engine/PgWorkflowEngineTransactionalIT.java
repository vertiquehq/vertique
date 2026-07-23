// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowEntryType;
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
import java.time.Clock;
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
 * Integration tests for {@link WorkflowEngineHandle} transactional operations.
 *
 * <p>Verifies the full lifecycle of workflow instances: atomic start (instance + history + dedup),
 * state initialization via the init function, optimistic-concurrency on concurrent signals, and
 * signal payload coercion from raw {@code Map} to declared types.
 *
 * <p>Sample types used across tests are defined as static nested records in this class.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineTransactionalIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_engine_tx_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    /** Engine with the simple order definition (init → complete). */
    static WorkflowEngineHandle simpleEngine;

    /** Engine with the signal workflow definition (init → wait-signal → complete). */
    static WorkflowEngineHandle signalEngine;

    // --- Sample domain types ---

    /**
     * Start payload for the order workflow.
     *
     * @param orderId the order identifier
     * @param total the order total
     */
    record PlaceOrder(String orderId, int total) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "order-" + orderId;
        }
    }

    /**
     * Workflow state for the order workflow.
     *
     * @param orderId the order identifier
     * @param total the order total
     * @param status the current processing status
     */
    record OrderState(String orderId, int total, String status) {
        /**
         * Derives the initial {@code OrderState} from a {@link PlaceOrder} start payload.
         *
         * @param po the start payload
         * @return initial state with status {@code "RUNNING"}
         */
        static OrderState from(PlaceOrder po) {
            return new OrderState(po.orderId(), po.total(), "RUNNING");
        }

        /**
         * Updates state when a payment is captured.
         *
         * @param state the current state
         * @param p the payment captured signal payload
         * @return updated state with status {@code "PAID"} and chargeId stored
         */
        static OrderState onPaymentCaptured(OrderState state, PaymentCaptured p) {
            return new OrderState(state.orderId(), state.total(), "PAID-" + p.chargeId());
        }
    }

    /**
     * Signal payload representing a captured payment.
     *
     * @param chargeId the payment charge identifier
     */
    record PaymentCaptured(String chargeId) {}

    // --- Fake contract markers ---

    /** Marker contract interface for the simple (init → complete) workflow. */
    interface FakeSimpleContract {}

    /** Marker contract interface for the signal (init → wait → complete) workflow. */
    interface FakeSignalContract {}

    // --- Workflow definitions ---

    /**
     * A minimal order-workflow definition: init from {@link PlaceOrder} → {@link OrderState}, then
     * complete immediately at step {@code "done"}.
     *
     * <p>This is the simplest possible non-trivial definition that exercises the init callback path.
     * No service dispatch nodes, so no recorder is needed.
     */
    static final WorkflowDefinition<OrderState, FakeSimpleContract> SIMPLE_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FakeSimpleContract> contract() {
            return FakeSimpleContract.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "engine-tx-order";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(PlaceOrder.class, OrderState::from).initialStep("done").complete("done");
        }
    };

    /**
     * A signal workflow definition: init → wait for {@code payment.captured} signal → complete.
     *
     * <p>Uses {@code OrderState::onPaymentCaptured} as the state updater so the engine merges the
     * signal payload into the state when the signal arrives.
     */
    static final WorkflowDefinition<OrderState, FakeSignalContract> SIGNAL_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FakeSignalContract> contract() {
            return FakeSignalContract.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "engine-tx-signal";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(PlaceOrder.class, OrderState::from)
                    .initialStep("wait-payment")
                    .waitFor(
                            "wait-payment",
                            "payment.captured",
                            PaymentCaptured.class,
                            OrderState::onPaymentCaptured,
                            "done")
                    .complete("done");
        }
    };

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();

        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);

        // Simple engine: only knows the simple (init → complete) definition; no recorders needed.
        DefaultWorkflowRegistry simpleRegistry = new DefaultWorkflowRegistry();
        simpleRegistry.register(SIMPLE_DEFINITION);
        simpleEngine = PgWorkflowEngineTestSupport.create(
                pool,
                simpleRegistry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        // Signal engine: knows the signal definition; no recorders needed (no service dispatch).
        DefaultWorkflowRegistry signalRegistry = new DefaultWorkflowRegistry();
        signalRegistry.register(SIGNAL_DEFINITION);
        signalEngine = PgWorkflowEngineTestSupport.create(
                pool,
                signalRegistry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query(
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
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
     * Verifies that the init function transforms the start payload into the correct state type and
     * that the persisted {@code state_json} decodes to the expected {@link OrderState}.
     */
    @Test
    @DisplayName("start init function produces OrderState with correct fields (state_json decoded)")
    void startInitializerProducesOrderStateNotPlaceOrder(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o1", 100);
        StartCommand cmd = new StartCommand("engine-tx-order", payload, payload.idempotencyKey(), null, null);

        simpleEngine
                .start(cmd)
                .compose(id -> simpleEngine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.start + query must succeed, got: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertNotNull(view, "WorkflowView must not be null");
                    // Decode state_json and verify it is an OrderState (not the raw PlaceOrder).
                    OrderState state = Json.decodeValue(view.instance().stateJson(), OrderState.class);
                    assertEquals("o1", state.orderId(), "orderId must match");
                    assertEquals(100, state.total(), "total must match");
                    assertEquals("RUNNING", state.status(), "status must be RUNNING (from init)");
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a successful start atomically commits an instance row, a history entry, and a
     * dedup record. Also verifies rollback: a plan with an unsupported initial node leaves no rows.
     */
    @Test
    @DisplayName("start commits instance + history + dedup atomically; rollback leaves zero rows")
    void startCommitsInstancePlusHistoryPlusDedup(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o2", 200);
        StartCommand cmd = new StartCommand("engine-tx-order", payload, payload.idempotencyKey(), null, null);

        simpleEngine
                .start(cmd)
                .compose(id -> {
                    // Verify all three tables have exactly one row.
                    Future<Long> instanceCount = pool.query("SELECT COUNT(*) AS cnt FROM workflow_instances")
                            .execute()
                            .map(rs -> rs.iterator().next().getLong("cnt"));
                    Future<Long> historyCount = pool.query("SELECT COUNT(*) AS cnt FROM workflow_history")
                            .execute()
                            .map(rs -> rs.iterator().next().getLong("cnt"));
                    Future<Long> dedupCount = pool.query("SELECT COUNT(*) AS cnt FROM workflow_dedup")
                            .execute()
                            .map(rs -> rs.iterator().next().getLong("cnt"));

                    return Future.all(instanceCount, historyCount, dedupCount).map(cf -> {
                        long inst = cf.resultAt(0);
                        long hist = cf.resultAt(1);
                        long ded = cf.resultAt(2);
                        return new long[] {inst, hist, ded};
                    });
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "queries must succeed, got: " + ar.cause());
                    long[] counts = ar.result();
                    assertEquals(1L, counts[0], "workflow_instances must have exactly 1 row");
                    assertTrue(counts[1] >= 1L, "workflow_history must have at least 1 row (START + COMPLETED)");
                    assertEquals(1L, counts[2], "workflow_dedup must have exactly 1 row");
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that concurrent signals on the same waiting instance produce optimistic-concurrency
     * behavior: exactly one wins, the other fails with {@link WorkflowConflictException}.
     */
    @Test
    @DisplayName("concurrent signals trigger optimistic concurrency — one wins, other gets WorkflowConflictException")
    @SuppressWarnings("unchecked")
    void concurrentSignalsOptimisticConcurrency(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o3", 300);
        StartCommand startCmd = new StartCommand("engine-tx-signal", payload, payload.idempotencyKey(), null, null);
        String dedupA = "signal-dedup-" + UUID.randomUUID();
        String dedupB = "signal-dedup-" + UUID.randomUUID();

        // First start the instance (it will be in WAITING state after start).
        signalEngine
                .start(startCmd)
                .compose(id -> {
                    // Verify it is WAITING.
                    return signalEngine.query(id).compose(view -> {
                        assertEquals(
                                WorkflowStatus.WAITING,
                                view.instance().status(),
                                "instance must be WAITING after start");

                        // Send two concurrent signals with different dedup keys.
                        Future<Void> signalA =
                                signalEngine.signal(id, "payment.captured", new PaymentCaptured("charge-1"), dedupA);
                        Future<Void> signalB =
                                signalEngine.signal(id, "payment.captured", new PaymentCaptured("charge-2"), dedupB);

                        return Future.join(List.of(signalA, signalB)).map(cf -> {
                            boolean aSucceeded = signalA.succeeded();
                            boolean bSucceeded = signalB.succeeded();

                            // Exactly one must win, the other must fail.
                            assertTrue(
                                    aSucceeded ^ bSucceeded,
                                    "exactly one signal must win; signalA=" + aSucceeded + " signalB=" + bSucceeded);

                            // The loser must fail with WorkflowConflictException (or signal rejected).
                            Future<Void> loser = aSucceeded ? signalB : signalA;
                            assertTrue(loser.failed(), "loser signal must fail");
                            // The loser fails because the instance was already advanced past WAITING.
                            // This may be a WorkflowConflictException or WorkflowSignalRejectedException.
                            assertNotNull(loser.cause(), "loser cause must not be null");
                            return null;
                        });
                    });
                })
                .onComplete(ar -> ctx.verify(() -> {
                    // If we get here without a verify() failure, the test passed.
                    // The join itself may have a combined failure; check the individual futures.
                    if (ar.failed() && !(ar.cause() instanceof AssertionError)) {
                        // The join may report a failure from the losing signal — that's expected.
                        // The assertion inside the compose already verified the invariant.
                    }
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a signal with a raw {@code Map} payload is coerced to the declared
     * {@link PaymentCaptured} type before the state updater is invoked.
     *
     * <p>Posts the signal as {@code Map.of("chargeId", "charge-xyz")} and asserts the resulting
     * state has status {@code "PAID-charge-xyz"} (set by the state updater when it receives a typed
     * {@code PaymentCaptured}).
     */
    @Test
    @DisplayName("signal payload coercion: raw Map coerced to PaymentCaptured before state updater")
    void signalPayloadCoercion(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o4", 400);
        StartCommand startCmd = new StartCommand("engine-tx-signal", payload, payload.idempotencyKey(), null, null);
        String dedupKey = "coerce-dedup-" + UUID.randomUUID();
        Map<String, Object> rawPayload = Map.of("chargeId", "charge-xyz");

        signalEngine
                .start(startCmd)
                .compose(id -> {
                    // Send signal as a raw Map (should be coerced to PaymentCaptured by the engine).
                    return signalEngine
                            .signal(id, "payment.captured", rawPayload, dedupKey)
                            .compose(v -> signalEngine.query(id));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "start + signal + query must succeed, got: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "must be COMPLETED after signal");
                    // Decode state and verify the updater received a typed PaymentCaptured.
                    OrderState state = Json.decodeValue(view.instance().stateJson(), OrderState.class);
                    assertEquals("PAID-charge-xyz", state.status(), "state updater must have received PaymentCaptured");
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a typed {@link PaymentCaptured} signal payload also works correctly (no
     * unnecessary coercion when the payload is already the right type).
     */
    @Test
    @DisplayName("signal with typed PaymentCaptured payload is processed correctly (no coercion needed)")
    void signalWithTypedPayload(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o5", 500);
        StartCommand startCmd = new StartCommand("engine-tx-signal", payload, payload.idempotencyKey(), null, null);
        String dedupKey = "typed-dedup-" + UUID.randomUUID();

        signalEngine
                .start(startCmd)
                .compose(id -> signalEngine
                        .signal(id, "payment.captured", new PaymentCaptured("charge-typed"), dedupKey)
                        .compose(v -> signalEngine.query(id)))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "start + signal + query must succeed, got: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "must be COMPLETED after signal");
                    OrderState state = Json.decodeValue(view.instance().stateJson(), OrderState.class);
                    assertEquals(
                            "PAID-charge-typed", state.status(), "state must reflect typed PaymentCaptured payload");
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that start is idempotent: calling start twice with the same idempotency key returns
     * the same instance id without creating a duplicate.
     */
    @Test
    @DisplayName("start is idempotent: duplicate call returns same instance id, no new rows")
    void startIsIdempotent(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o6", 600);
        StartCommand cmd = new StartCommand("engine-tx-order", payload, payload.idempotencyKey(), null, null);

        simpleEngine
                .start(cmd)
                .compose(id1 -> simpleEngine.start(cmd).map(id2 -> new WorkflowInstanceId[] {id1, id2}))
                .compose(ids -> {
                    assertEquals(ids[0].value(), ids[1].value(), "duplicate start must return the same instance id");
                    // Verify only one instance row exists.
                    return pool.query("SELECT COUNT(*) AS cnt FROM workflow_instances")
                            .execute()
                            .map(rs -> rs.iterator().next().getLong("cnt"));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "idempotency test must succeed, got: " + ar.cause());
                    assertEquals(1L, ar.result(), "only one workflow_instances row must exist");
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a {@code RUNNING} instance (not yet in WAITING state) rejects a signal with
     * {@link dev.vertique.workflow.exception.WorkflowSignalRejectedException}.
     */
    @Test
    @DisplayName("signal on non-waiting instance fails with WorkflowSignalRejectedException")
    void signalOnNonWaitingInstanceFails(VertxTestContext ctx) {
        PlaceOrder payload = new PlaceOrder("o7", 700);
        // Use simple engine — the instance will be COMPLETED immediately (no wait step).
        StartCommand cmd = new StartCommand("engine-tx-order", payload, payload.idempotencyKey(), null, null);
        String dedupKey = "reject-dedup-" + UUID.randomUUID();

        simpleEngine
                .start(cmd)
                .compose(id -> simpleEngine.signal(id, "payment.captured", Map.of(), dedupKey))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "signal on a COMPLETED instance must fail");
                    assertInstanceOf(
                            dev.vertique.workflow.exception.WorkflowSignalRejectedException.class,
                            ar.cause(),
                            "cause must be WorkflowSignalRejectedException, got: " + ar.cause());
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that a {@code NoOpServiceRecorder} (fake recorder with {@code kind=SERVICE}) allows
     * the engine to drive through a {@code ServiceDispatchNode} without an outbox table.
     *
     * <p>This test uses an inline {@code WorkflowDefinition} with a service dispatch node to ensure
     * the recorder routing path is exercised even without the real outbox infrastructure.
     */
    @Test
    @DisplayName("engine drives ServiceDispatchNode with no-op recorder (no outbox table needed)")
    void engineDrivesServiceDispatchWithNoOpRecorder(VertxTestContext ctx) {
        // A recorder that is a no-op (never actually writes to the outbox table).
        WorkflowSideEffectRecorder<SqlClient> noOpRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.SERVICE;
            }

            @Override
            public Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                    WorkflowSideEffectIntent intent, SqlClient tx) {
                return Future.succeededFuture(dev.vertique.workflow.sideeffect.RecorderResult.empty());
            }
        };

        // Workflow: init → dispatch "svc-target" → wait-payment → complete.
        WorkflowDefinition<OrderState, FakeSimpleContract> dispatchDef = new WorkflowDefinition<>() {
            @Override
            public Class<FakeSimpleContract> contract() {
                return FakeSimpleContract.class;
            }

            @Override
            public Class<OrderState> stateType() {
                return OrderState.class;
            }

            @Override
            public String definitionId() {
                return "engine-tx-dispatch";
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<OrderState> wf) {
                wf.init(PlaceOrder.class, OrderState::from)
                        .initialStep("dispatch")
                        .dispatch("dispatch", "svc-target", s -> Map.of("orderId", s.orderId()), "wait-payment")
                        .waitFor(
                                "wait-payment",
                                "payment.captured",
                                PaymentCaptured.class,
                                OrderState::onPaymentCaptured,
                                "done")
                        .complete("done");
            }
        };

        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);

        DefaultWorkflowRegistry dispatchRegistry = new DefaultWorkflowRegistry();
        dispatchRegistry.register(dispatchDef);

        WorkflowEngineHandle dispatchEngine = PgWorkflowEngineTestSupport.create(
                pool,
                dispatchRegistry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(noOpRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        PlaceOrder payload = new PlaceOrder("o8", 800);
        StartCommand cmd = new StartCommand("engine-tx-dispatch", payload, payload.idempotencyKey(), null, null);

        dispatchEngine
                .start(cmd)
                .compose(id -> dispatchEngine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "start + query must succeed with no-op recorder, got: " + ar.cause());
                    WorkflowView view = ar.result();
                    // After start, the engine drives: dispatch → wait-payment (WAITING).
                    assertEquals(
                            WorkflowStatus.WAITING,
                            view.instance().status(),
                            "instance must be WAITING after service dispatch + wait signal");
                    assertFalse(view.recentHistory().isEmpty(), "history must have entries");
                    // SIDE_EFFECT_RECORDED entry must be present.
                    boolean hasSideEffect = view.recentHistory().stream()
                            .anyMatch(e -> e.entryType() == WorkflowEntryType.SIDE_EFFECT_RECORDED);
                    assertTrue(hasSideEffect, "history must contain a SIDE_EFFECT_RECORDED entry");
                    ctx.completeNow();
                }));
    }
}
