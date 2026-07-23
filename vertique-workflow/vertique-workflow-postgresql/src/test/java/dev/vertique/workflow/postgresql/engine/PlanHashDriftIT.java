// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowPlanHashDriftException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Clock;
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
 * Integration test for plan-hash drift detection in {@link WorkflowEngineHandle}.
 *
 * <p>Verifies that when a workflow instance was started with plan version v1 and a SECOND engine is
 * constructed with a DIFFERENT plan registered under the same {@code definitionId + version}, any
 * mutating operation (signal, retry) via the second engine is rejected with
 * {@link WorkflowPlanHashDriftException} — rather than silently resuming on the new plan content.
 *
 * <p>Read-only operations (query) must still succeed because they do not need the plan.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PlanHashDriftIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("plan_hash_drift_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    /** Engine A — uses the ORIGINAL plan (plan A). Starts instances. */
    static WorkflowEngineHandle engineA;

    /**
     * Engine B — uses a DIFFERENT plan registered under the same {@code id="hash-drift", v=1}.
     * Attempts to signal instances started by engine A.
     */
    static WorkflowEngineHandle engineB;

    // --- Domain types ---

    /** Simple state type for the drift test. */
    record DriftState(String id) {}

    /** Start payload. */
    record DriftStart(String id) {}

    /** Signal payload for plan A (signal name "wait-a"). */
    record SigA(String data) {}

    /** Signal payload for plan B (different signal name "wait-b"). */
    record SigB(String data) {}

    /** Marker contract for the drift-test workflow. */
    interface DriftContract {}

    /**
     * Plan A: minimal workflow that waits for signal {@code "wait-a"} then completes.
     *
     * <p>Engine A registers this plan under {@code id="hash-drift", v=1}.
     */
    static final WorkflowDefinition<DriftState, DriftContract> PLAN_A_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<DriftContract> contract() {
            return DriftContract.class;
        }

        @Override
        public Class<DriftState> stateType() {
            return DriftState.class;
        }

        @Override
        public String definitionId() {
            return "hash-drift";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<DriftState> wf) {
            wf.init(DriftStart.class, s -> new DriftState(s.id()))
                    .initialStep("step-a")
                    .waitFor("step-a", "wait-a", SigA.class, (state, sig) -> state, "done")
                    .complete("done");
        }
    };

    /**
     * Plan B: the SAME {@code id="hash-drift", v=1} but with a DIFFERENT wait signal
     * ({@code "wait-b"} instead of {@code "wait-a"}).
     *
     * <p>This simulates a packaging bug where plan content changed without a version bump.
     * Engine B registers this plan.
     */
    static final WorkflowDefinition<DriftState, DriftContract> PLAN_B_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<DriftContract> contract() {
            return DriftContract.class;
        }

        @Override
        public Class<DriftState> stateType() {
            return DriftState.class;
        }

        @Override
        public String definitionId() {
            return "hash-drift";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<DriftState> wf) {
            wf.init(DriftStart.class, s -> new DriftState(s.id()))
                    .initialStep("step-a")
                    // Different signal name: "wait-b" instead of "wait-a" — causes hash mismatch
                    .waitFor("step-a", "wait-b", SigB.class, (state, sig) -> state, "done")
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

        // Engine A: plan A (signal "wait-a").
        DefaultWorkflowRegistry registryA = new DefaultWorkflowRegistry();
        registryA.register(PLAN_A_DEF);
        engineA = PgWorkflowEngineTestSupport.create(
                pool,
                registryA,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        // Engine B: plan B (signal "wait-b" — different hash, same id+version).
        DefaultWorkflowRegistry registryB = new DefaultWorkflowRegistry();
        registryB.register(PLAN_B_DEF);
        engineB = PgWorkflowEngineTestSupport.create(
                pool,
                registryB,
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
    void truncate(VertxTestContext ctx) {
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
     * Engine A starts an instance (pinned to plan A's hash). Engine B signals it. The signal must
     * fail with {@link WorkflowPlanHashDriftException} because the registered plan hash (plan B)
     * differs from the stored hash (plan A).
     *
     * <p>The exception must carry the correct {@code definitionId}, {@code version},
     * {@code instanceId}, and differing {@code storedHash} / {@code currentHash} values.
     */
    @Test
    @DisplayName("signal via engine with drifted plan hash fails with WorkflowPlanHashDriftException")
    void signalWithDriftedPlanHashFails(VertxTestContext ctx) {
        String idempotencyKey = "drift-signal-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand("hash-drift", new DriftStart("d1"), idempotencyKey, null, null);

        engineA.start(cmd)
                .compose(id -> {
                    String dedupKey = "dedup-" + UUID.randomUUID();
                    // Engine B attempts to signal with its drifted plan.
                    return engineB.signal(id, "wait-a", new SigA("x"), dedupKey).map(v -> id);
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "engineB.signal must fail due to plan hash drift");
                    assertInstanceOf(
                            WorkflowPlanHashDriftException.class,
                            ar.cause(),
                            "cause must be WorkflowPlanHashDriftException, got: " + ar.cause());

                    WorkflowPlanHashDriftException ex = (WorkflowPlanHashDriftException) ar.cause();
                    assertEquals("hash-drift", ex.definitionId(), "definitionId must be 'hash-drift'");
                    assertEquals(1L, ex.version(), "version must be 1");
                    assertNotNull(ex.instanceId(), "instanceId must not be null");
                    assertNotNull(ex.storedHash(), "storedHash must not be null");
                    assertNotNull(ex.currentHash(), "currentHash must not be null");
                    assertNotEquals(ex.storedHash(), ex.currentHash(), "storedHash and currentHash must differ");

                    ctx.completeNow();
                }));
    }

    /**
     * Engine A starts an instance (pinned to plan A's hash). Engine B queries it. The query must
     * succeed because {@code query} is read-only and does not need to resolve the plan.
     */
    @Test
    @DisplayName("query via engine with drifted plan hash succeeds (read-only, plan-independent)")
    void queryWithDriftedPlanHashSucceeds(VertxTestContext ctx) {
        String idempotencyKey = "drift-query-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand("hash-drift", new DriftStart("d2"), idempotencyKey, null, null);

        engineA.start(cmd)
                .compose(id -> engineB.query(id).map(view -> new Object[] {id, view}))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engineB.query must succeed (plan-independent): " + ar.cause());
                    WorkflowInstanceId startedId = (WorkflowInstanceId) ar.result()[0];
                    var view = ar.result()[1];
                    assertNotNull(view, "WorkflowView must not be null");
                    assertFalse(
                            ((dev.vertique.workflow.ops.WorkflowView) view)
                                    .recentHistory()
                                    .isEmpty(),
                            "history must have at least a START entry");
                    ctx.completeNow();
                }));
    }

    // --- Assertion helpers ---

    /**
     * Asserts that the condition is false, providing a message on failure.
     *
     * @param condition the condition to check
     * @param message failure message
     */
    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}
