// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.vertique.workflow.plan.RaceSafety;
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
 * Verifies the {@code FIRST_FAILURE} race-join policy (PRD-WF-002 AC #13, FR-WF-PAR-053).
 *
 * <p>Setup: a fork with two branches under {@code FIRST_FAILURE}. Branch {@code a} is a
 * {@link dev.vertique.workflow.plan.FailNode} (terminates as FAILED); branch {@code b} is a
 * {@link dev.vertique.workflow.plan.CompleteNode} (terminates as COMPLETED). Branches are driven
 * in declaration order during fork dispatch, so the failing branch terminates first and becomes
 * the winner. The completed sibling is recorded as a late loser; the workflow advances through
 * the {@code onFailure} route.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineFirstFailureIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_first_failure_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    record StartRace(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "first-failure-" + id;
        }
    }

    record State(String id) {}

    interface RaceContract {}

    static final WorkflowDefinition<State, RaceContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<RaceContract> contract() {
            return RaceContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "first-failure-race";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartRace.class, p -> new State(p.id()))
                    .fork("fork")
                    .branch("a", "fail-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "complete-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .fail("fail-a", "BranchAFailedFirst", st -> "deliberate failure for FIRST_FAILURE test")
                    .complete("complete-b")
                    .join("join")
                    .firstFailure((s, results) -> s)
                    .toStep("done")
                    .onFailure("failed")
                    .endJoin()
                    .complete("done")
                    .complete("failed");
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
    @DisplayName("AC #13 — FIRST_FAILURE takes the failure route on the first branch to terminate FAILED")
    void firstFailureAdvancesOnFirstFailingBranch(VertxTestContext ctx) {
        StartRace payload = new StartRace("rf1");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        engine.start(cmd)
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.start should succeed under FIRST_FAILURE: " + ar.cause());
                    assertEquals(
                            WorkflowStatus.COMPLETED, ar.result().instance().status());
                    // The workflow takes the onFailure route ("failed") and runs to completion at that
                    // CompleteNode — instance ends COMPLETED. The join state captures the failure and
                    // the winning branch.
                    pool.preparedQuery(
                                    "SELECT status, winning_branch_id FROM workflow_join_states WHERE workflow_id = $1")
                            .execute(io.vertx.sqlclient.Tuple.of(
                                    ar.result().instance().id().value()))
                            .onComplete(jr -> ctx.verify(() -> {
                                int joinRows = 0;
                                for (var row : jr.result()) {
                                    joinRows++;
                                    assertEquals(
                                            "FAILED", row.getString("status"), "FIRST_FAILURE join must end FAILED");
                                    assertNotNull(row.getString("winning_branch_id"), "winning_branch_id must be set");
                                    assertEquals(
                                            "a",
                                            row.getString("winning_branch_id"),
                                            "branch 'a' fails first in declaration order, so it wins FIRST_FAILURE");
                                }
                                assertEquals(1, joinRows);
                                ctx.completeNow();
                            }));
                }));
    }
}
