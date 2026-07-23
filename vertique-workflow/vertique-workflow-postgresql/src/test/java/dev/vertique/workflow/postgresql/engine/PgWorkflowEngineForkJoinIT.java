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
import dev.vertique.workflow.state.JoinStateStatus;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration smoke test for PRD-WF-002 ALL_REQUIRED fan-out / fan-in.
 *
 * <p>Builds a minimal definition: {@code init → fork → join → complete} where each branch is just
 * a {@code CompleteNode}. Asserts that:
 * <ul>
 *   <li>the workflow instance reaches {@link WorkflowStatus#COMPLETED};</li>
 *   <li>both branch tokens land at {@code COMPLETED};</li>
 *   <li>the join state is {@code COMPLETED};</li>
 *   <li>history contains {@code FORK_DISPATCHED}, {@code BRANCH_COMPLETED} (twice),
 *       {@code FAN_IN_EVALUATED}, and {@code FAN_IN_COMPLETED} entries.</li>
 * </ul>
 *
 * <p>This is the AC #1 happy path. Service-dispatch branches, branch waits, and race joins land
 * in subsequent slices and have their own ITs.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineForkJoinIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_fork_join_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    record StartFanout(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "fanout-" + orderId;
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
            return "fanout-allreq";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartFanout.class, p -> new State(p.orderId()))
                    .fork("fork")
                    .branch("a", "step-a")
                    .branch("b", "step-b")
                    .join("join")
                    .complete("step-a")
                    .complete("step-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
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

        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, ex);
        PgWorkflowHistoryRepository history = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        PgBranchTokenRepository branches = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);

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
                Set.of(),
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
    @DisplayName("AC #1 — ALL_REQUIRED fan-out completes when every branch reaches a terminal Complete")
    void allRequiredHappyPath(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("o1");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        engine.start(cmd)
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.start should succeed: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status());

                    // Branch tokens — both COMPLETED.
                    pool.preparedQuery(
                                    "SELECT status FROM workflow_branch_tokens WHERE workflow_id = $1 ORDER BY branch_id")
                            .execute(io.vertx.sqlclient.Tuple.of(
                                    view.instance().id().value()))
                            .onComplete(r2 -> ctx.verify(() -> {
                                int rows = 0;
                                for (var row : r2.result()) {
                                    rows++;
                                    assertEquals("COMPLETED", row.getString("status"));
                                }
                                assertEquals(2, rows);

                                // Join state COMPLETED.
                                pool.preparedQuery("SELECT status FROM workflow_join_states WHERE workflow_id = $1")
                                        .execute(io.vertx.sqlclient.Tuple.of(
                                                view.instance().id().value()))
                                        .onComplete(r3 -> ctx.verify(() -> {
                                            int joinRows = 0;
                                            for (var row : r3.result()) {
                                                joinRows++;
                                                assertEquals(JoinStateStatus.COMPLETED.name(), row.getString("status"));
                                            }
                                            assertEquals(1, joinRows);

                                            // History contains the expected fork/branch/fan-in entries.
                                            long forkDispatched = view.recentHistory().stream()
                                                    .filter(h -> h.entryType() == WorkflowEntryType.FORK_DISPATCHED)
                                                    .count();
                                            long branchCompleted = view.recentHistory().stream()
                                                    .filter(h -> h.entryType() == WorkflowEntryType.BRANCH_COMPLETED)
                                                    .count();
                                            long fanInEval = view.recentHistory().stream()
                                                    .filter(h -> h.entryType() == WorkflowEntryType.FAN_IN_EVALUATED)
                                                    .count();
                                            long fanInDone = view.recentHistory().stream()
                                                    .filter(h -> h.entryType() == WorkflowEntryType.FAN_IN_COMPLETED)
                                                    .count();
                                            assertEquals(1L, forkDispatched);
                                            assertEquals(2L, branchCompleted);
                                            assertEquals(1L, fanInEval);
                                            assertEquals(1L, fanInDone);
                                            ctx.completeNow();
                                        }));
                            }));
                }));
    }
}
