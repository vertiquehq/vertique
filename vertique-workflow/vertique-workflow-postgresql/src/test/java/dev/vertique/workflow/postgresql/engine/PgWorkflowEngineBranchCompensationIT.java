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
import dev.vertique.workflow.ops.WorkflowView;
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
import dev.vertique.workflow.state.WorkflowEntryType;
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
 * End-to-end IT for PRD-WF-002 AC #2 — branch compensation under {@code ALL_REQUIRED}.
 *
 * <p>Workflow shape:
 * <pre>
 *   init → fork
 *          branch a:  dispatch-a-1 (comp cancel-a-1)
 *                  → dispatch-a-2 (comp cancel-a-2)
 *                  → completeBranch                       // branch a ends COMPLETED
 *          branch b:  dispatch-b-1 (comp cancel-b-1)
 *                  → fail-b                               // FailNode → branch b ends FAILED
 *          join ALL_REQUIRED
 *               success path → done                      // unreachable in this IT
 *               no failure route                         // → workflow FAILED with
 *                                                        //   error_type "join_failed_no_failure_route"
 * </pre>
 *
 * <p>Branch {@code b}'s failure decides the join FAILED. Before the instance advances to the
 * (missing) failure route, {@link BranchCompensationOrchestrator#compensateFailedFork} runs:
 * <ol>
 *   <li>In <strong>reverse declaration order across branches</strong>, then for each branch in
 *       <strong>LIFO order within branch</strong>.</li>
 *   <li>Branch {@code b} is FAILED but still gets compensated because it recorded a forward
 *       SIDE_EFFECT_RECORDED entry (FR-WF-PAR-063 explicitly allows this).</li>
 * </ol>
 *
 * <p>Captured compensation order: {@code cancel-b-1}, {@code cancel-a-2}, {@code cancel-a-1}.
 * Two {@code BRANCH_COMPENSATING_START}/{@code BRANCH_COMPENSATED} pairs (one per branch with
 * committed compensable dispatches), three {@code BRANCH_COMPENSATING_STEP} entries.
 *
 * <p>This IT closes the 0% gap on {@link BranchCompensationOrchestrator#emitOneCompensation}
 * and the 28% gap on {@code compensateBranch}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchCompensationIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_compensation_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static CapturingServiceRecorder capturingRecorder;

    record StartFanout(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "comp-" + orderId;
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
            return "branch-compensation-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartFanout.class, p -> new State(p.orderId()))
                    .fork("fork")
                    .branch("a", "dispatch-a-1")
                    .branch("b", "dispatch-b-1")
                    .join("join")
                    // branch a: two compensable dispatches → completeBranch
                    .dispatchWithCompensation(
                            "dispatch-a-1",
                            "service.a-1",
                            st -> Map.of("orderId", st.orderId(), "step", "a-1"),
                            "cancel-a-1",
                            "dispatch-a-2")
                    .dispatchWithCompensation(
                            "dispatch-a-2",
                            "service.a-2",
                            st -> Map.of("orderId", st.orderId(), "step", "a-2"),
                            "cancel-a-2",
                            "complete-a")
                    .complete("complete-a")
                    // branch b: one compensable dispatch → FailNode
                    .dispatchWithCompensation(
                            "dispatch-b-1",
                            "service.b-1",
                            st -> Map.of("orderId", st.orderId(), "step", "b-1"),
                            "cancel-b-1",
                            "fail-b")
                    .fail("fail-b", "BranchBFailed", st -> "branch b deliberately fails for compensation IT")
                    // ALL_REQUIRED join with NO failure route — instance terminates FAILED
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done")
                    // compensation step nodes (referenced by compensationStepId on each dispatch)
                    .compensate(
                            "cancel-a-1",
                            "dispatch-a-1",
                            "service.cancel-a-1",
                            st -> Map.of("orderId", st.orderId(), "rolling", "back-a-1"))
                    .compensate(
                            "cancel-a-2",
                            "dispatch-a-2",
                            "service.cancel-a-2",
                            st -> Map.of("orderId", st.orderId(), "rolling", "back-a-2"))
                    .compensate(
                            "cancel-b-1",
                            "dispatch-b-1",
                            "service.cancel-b-1",
                            st -> Map.of("orderId", st.orderId(), "rolling", "back-b-1"));
            wf.initialStep("fork");
        }
    };

    /** In-memory recorder that captures every SERVICE intent (forward + compensation). */
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
        if (pool != null) pool.close();
    }

    @Test
    @DisplayName("AC #2 — failed ALL_REQUIRED fork compensates committed branch dispatches in reverse order")
    void allRequiredFailedBranch_compensatesCommittedDispatches(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("o-comp");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        engine.start(cmd)
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), () -> "engine.start should succeed: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(WorkflowStatus.FAILED, view.instance().status());
                    assertEquals(
                            "join_failed_no_failure_route",
                            view.instance().errorType(),
                            "ALL_REQUIRED with no onFailure must terminate FAILED with the canonical no-route error");

                    // Captured intents: 3 forward (a-1, a-2, b-1) + 3 compensation (cancel-b-1, cancel-a-2,
                    // cancel-a-1).
                    List<WorkflowSideEffectIntent> intents = capturingRecorder.snapshot();
                    assertEquals(6, intents.size(), "expected 3 forward + 3 compensation SERVICE intents");

                    List<String> targets = intents.stream()
                            .map(WorkflowSideEffectIntent::targetId)
                            .toList();
                    // Forward intents emitted as branches drive inline; their interleaved order is the
                    // declaration order across branches: a-1, a-2 (branch a inline), then b-1 (branch b
                    // inline up to fail-b). Compensations follow strictly: branch b first (LIFO of one),
                    // then branch a (LIFO of two = a-2, a-1).
                    assertEquals(
                            List.of(
                                    "service.a-1",
                                    "service.a-2",
                                    "service.b-1",
                                    "service.cancel-b-1",
                                    "service.cancel-a-2",
                                    "service.cancel-a-1"),
                            targets,
                            "compensation order is reverse declaration order across branches × LIFO within branch");

                    // History entry counts.
                    long compensatingStart = view.recentHistory().stream()
                            .filter(h -> h.entryType() == WorkflowEntryType.BRANCH_COMPENSATING_START)
                            .count();
                    long compensatingStep = view.recentHistory().stream()
                            .filter(h -> h.entryType() == WorkflowEntryType.BRANCH_COMPENSATING_STEP)
                            .count();
                    long compensated = view.recentHistory().stream()
                            .filter(h -> h.entryType() == WorkflowEntryType.BRANCH_COMPENSATED)
                            .count();
                    assertEquals(
                            2L,
                            compensatingStart,
                            "one BRANCH_COMPENSATING_START per branch with committed compensable dispatches");
                    assertEquals(3L, compensatingStep, "one BRANCH_COMPENSATING_STEP per emitted compensation intent");
                    assertEquals(
                            2L, compensated, "one BRANCH_COMPENSATED per branch with committed compensable dispatches");

                    // Branch b's COMPENSATING entries come before branch a's (reverse declaration order).
                    long firstBStartSeq = view.recentHistory().stream()
                            .filter(h -> h.entryType() == WorkflowEntryType.BRANCH_COMPENSATING_START
                                    && h.payloadJson().contains("\"branchId\":\"b\""))
                            .mapToLong(h -> h.sequence())
                            .min()
                            .orElse(Long.MAX_VALUE);
                    long firstAStartSeq = view.recentHistory().stream()
                            .filter(h -> h.entryType() == WorkflowEntryType.BRANCH_COMPENSATING_START
                                    && h.payloadJson().contains("\"branchId\":\"a\""))
                            .mapToLong(h -> h.sequence())
                            .min()
                            .orElse(Long.MAX_VALUE);
                    assertTrue(
                            firstBStartSeq < firstAStartSeq,
                            "branch b must compensate before branch a (reverse declaration order)");
                    ctx.completeNow();
                }));
    }
}
