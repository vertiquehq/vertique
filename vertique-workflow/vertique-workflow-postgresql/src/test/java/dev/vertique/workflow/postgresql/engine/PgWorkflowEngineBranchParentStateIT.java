// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.plan.RaceSafetyTargetContributor;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a branch's payload factory sees the parent workflow instance's state when the
 * fork is dispatched (PRD-WF-002 §3 §10, FR-WF-PAR-031).
 *
 * <p>Before the fix, {@code BranchTransitionEngine} decoded the branch's own
 * {@link dev.vertique.workflow.state.BranchToken#resultJson()} (which is null for a freshly
 * created branch) and fell back to {@code "{}"} — payload factories saw a default-initialised
 * state object, regardless of what the parent had stored. This IT pins the regression: the
 * captured outbox-bound payload must echo the order id we set during {@code init}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchParentStateIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_parent_state_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static CapturingServiceRecorder capturingRecorder;

    record StartFanout(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "parent-state-" + orderId;
        }
    }

    record State(String orderId) {}

    interface FanoutContract {}

    static final WorkflowDefinition<State, FanoutContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FanoutContract> contract() {
            return FanoutContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "parent-state-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartFanout.class, p -> new State(p.orderId()))
                    .fork("fork")
                    .branch("a", "dispatch-a")
                    .branch("b", "dispatch-b")
                    .join("join")
                    .dispatch("dispatch-a", "service.a", st -> Map.of("orderId", st.orderId()), "complete-a")
                    .complete("complete-a")
                    .dispatch("dispatch-b", "service.b", st -> Map.of("orderId", st.orderId()), "complete-b")
                    .complete("complete-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        }
    };

    /** In-memory recorder that captures every SERVICE intent so the test can inspect payloads. */
    static final class CapturingServiceRecorder implements WorkflowSideEffectRecorder<SqlClient> {
        private final List<WorkflowSideEffectIntent> captured = new CopyOnWriteArrayList<>();

        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            captured.add(intent);
            return Future.succeededFuture(RecorderResult.empty());
        }

        List<WorkflowSideEffectIntent> snapshot() {
            return List.copyOf(captured);
        }

        void clear() {
            captured.clear();
        }
    }

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

        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, ex);
        PgWorkflowHistoryRepository history = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        PgBranchTokenRepository branches = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);
        capturingRecorder = new CapturingServiceRecorder();
        Set<WorkflowSideEffectRecorder<SqlClient>> recorders = Set.of(capturingRecorder);

        WorkflowPlanValidator validator = new WorkflowPlanValidator(
                new dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry(Set.<RaceSafetyTargetContributor>of()));
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(validator);
        registry.register(DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                history,
                dedup,
                recorders,
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC(),
                branches,
                joins);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        capturingRecorder.clear();
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

    @Test
    @DisplayName("Branch payload factories see the parent instance state, not an empty default")
    void branchDispatchSeesParentState(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("order-42");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        engine.start(cmd)
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.start should succeed: " + ar.cause());
                    assertEquals(
                            WorkflowStatus.COMPLETED, ar.result().instance().status());

                    List<WorkflowSideEffectIntent> intents = capturingRecorder.snapshot();
                    assertEquals(2, intents.size(), "expected one dispatch per branch");
                    for (WorkflowSideEffectIntent intent : intents) {
                        assertNotNull(intent.payload(), "branch payload must not be null");
                        assertTrue(intent.payload() instanceof Map, "branch payload should be a Map");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) intent.payload();
                        assertEquals(
                                "order-42",
                                map.get("orderId"),
                                "branch payload factory must see parent state's orderId, not the default null");
                        assertNotEquals(null, map.get("orderId"), "orderId must come from parent state");
                    }
                    ctx.completeNow();
                }));
    }
}
