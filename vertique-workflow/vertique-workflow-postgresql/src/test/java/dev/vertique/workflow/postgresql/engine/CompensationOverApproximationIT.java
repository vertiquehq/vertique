// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test verifying that the per-step compensation matching algorithm correctly
 * identifies only COMPLETED compensable steps — not over-approximating to include steps whose
 * signal was never received.
 *
 * <p>Scenario: a two-step saga where step A is dispatched and its signal is received (completed),
 * but step B is dispatched and its signal is NOT received (step B's signal never arrives). A
 * {@code FailNode} reached after step A's signal drives compensation. Only step A must be
 * compensated; step B must NOT appear in the compensation list.
 *
 * <p>Plan:
 * <pre>
 *   init
 *     → dispatch-a (SERVICE "svc-a.run", compensable → comp-a)
 *     → wait-a (signal "sig-a")
 *     → dispatch-b (SERVICE "svc-b.run", compensable → comp-b)
 *     → wait-b (signal "sig-b")  [NEVER receives signal in this test]
 *     → fail-step (FailNode)
 *
 *   compensation nodes:
 *     comp-a: compensates dispatch-a via "svc-a.rollback"
 *     comp-b: compensates dispatch-b via "svc-b.rollback"
 * </pre>
 *
 * <p>With only sig-a delivered, the instance reaches WAITING at wait-b. To trigger the FailNode,
 * we use a separate two-step plan that directly reaches the FailNode after sig-a, without
 * requiring sig-b.
 *
 * <p>The assertion is: {@code svc-a.rollback} is in the compensation intents and
 * {@code svc-b.rollback} is NOT.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class CompensationOverApproximationIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("compensation_over_approx_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    /** Records all intents received in order. */
    static final List<WorkflowSideEffectIntent> capturedIntents = Collections.synchronizedList(new ArrayList<>());

    // --- Domain types ---

    /** Start payload. */
    record TwoStepStart(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "over-approx-" + id;
        }
    }

    /** Workflow state. */
    record TwoStepState(String id, boolean aCompleted) {
        static TwoStepState init(TwoStepStart s) {
            return new TwoStepState(s.id(), false);
        }

        TwoStepState withA(SigA sig) {
            return new TwoStepState(id, true);
        }
    }

    record SigA(String data) {}

    /** Marker contract. */
    interface TwoStepContract {}

    /**
     * Plan: dispatch-a → wait-a (signal "sig-a") → dispatch-b → fail.
     *
     * <p>Step A is compensable and its signal IS received. Step B is compensable but its next step
     * goes directly to the FailNode — there is no wait for step B's signal. This means step B's
     * dispatch occurs but no signal follows it before the fail, so step B must NOT be compensated.
     *
     * <p>Specifically: after sig-a, dispatch-b records a SERVICE intent, then fail-step fires
     * immediately (no wait-b node). The history has SIDE_EFFECT_RECORDED for both dispatch-a and
     * dispatch-b, but SIGNAL_RECEIVED only for sig-a (before dispatch-b). The per-step algorithm
     * must see that dispatch-b's next step is the FailNode (not a WaitSignalNode), so dispatch-b
     * cannot be "completed" via a signal, and must be excluded from compensation.
     */
    static final WorkflowDefinition<TwoStepState, TwoStepContract> TWO_STEP_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<TwoStepContract> contract() {
            return TwoStepContract.class;
        }

        @Override
        public Class<TwoStepState> stateType() {
            return TwoStepState.class;
        }

        @Override
        public String definitionId() {
            return "over-approx-saga";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TwoStepState> wf) {
            wf.init(TwoStepStart.class, TwoStepState::init)
                    .initialStep("dispatch-a")
                    // Step A: compensable, next step is wait-a.
                    .dispatchWithCompensation("dispatch-a", "svc-a.run", s -> "payload-a", "comp-a", "wait-a")
                    .waitFor("wait-a", "sig-a", SigA.class, TwoStepState::withA, "dispatch-b")
                    // Step B: compensable, but next step is fail (not a wait) — no signal follows.
                    .dispatchWithCompensation("dispatch-b", "svc-b.run", s -> "payload-b", "comp-b", "fail-step")
                    .fail("fail-step", "FORCED_FAILURE", s -> "forced failure to trigger compensation")
                    .compensate("comp-a", "dispatch-a", "svc-a.rollback", s -> "rollback-a")
                    .compensate("comp-b", "dispatch-b", "svc-b.rollback", s -> "rollback-b");
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

        WorkflowSideEffectRecorder<SqlClient> stubRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.SERVICE;
            }

            @Override
            public Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                    WorkflowSideEffectIntent intent, SqlClient tx) {
                capturedIntents.add(intent);
                return Future.succeededFuture(dev.vertique.workflow.sideeffect.RecorderResult.empty());
            }
        };

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(TWO_STEP_DEF);
        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(stubRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
        capturedIntents.clear();
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
     * Drives the saga: start → sig-a → (dispatch-b records then fails immediately).
     *
     * <p>Asserts that after failure and compensation:
     * <ul>
     *   <li>Instance status is {@code COMPENSATED}.</li>
     *   <li>{@code svc-a.rollback} IS in the captured compensation intents.</li>
     *   <li>{@code svc-b.rollback} is NOT in the captured compensation intents (step B never
     *       completed — its next step was not a wait-signal, so no signal confirmed it).</li>
     * </ul>
     */
    @Test
    @DisplayName("only step-A (with completed signal) is compensated; step-B (no signal) is excluded")
    void onlyCompletedStepIsCompensated(VertxTestContext ctx) {
        TwoStepStart start = new TwoStepStart("oa-test-1");
        StartCommand cmd = new StartCommand("over-approx-saga", start, start.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(id ->
                        // Deliver sig-a: engine advances past wait-a → dispatch-b → fail → compensation.
                        engine.signal(id, "sig-a", new SigA("data"), "dedup-sig-a")
                                .map(v -> id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "saga must complete end-to-end, got: " + ar.cause());

                    var view = ar.result();
                    assertEquals(
                            WorkflowStatus.COMPENSATED,
                            view.instance().status(),
                            "instance must be COMPENSATED after fail + compensation");

                    List<String> intentTargets = capturedIntents.stream()
                            .map(WorkflowSideEffectIntent::targetId)
                            .collect(Collectors.toList());

                    // Forward intents must be present.
                    assertTrue(intentTargets.contains("svc-a.run"), "must have svc-a.run forward intent");
                    assertTrue(intentTargets.contains("svc-b.run"), "must have svc-b.run forward intent");

                    // Only step A (completed via signal) must be compensated.
                    assertTrue(
                            intentTargets.contains("svc-a.rollback"),
                            "svc-a.rollback must be compensated (step A completed with signal)");

                    // Step B must NOT be compensated (its next step was a FailNode, no signal confirmed it).
                    assertTrue(
                            !intentTargets.contains("svc-b.rollback"),
                            "svc-b.rollback must NOT be compensated (step B had no confirming signal);" + " intents: "
                                    + intentTargets);

                    ctx.completeNow();
                }));
    }
}
