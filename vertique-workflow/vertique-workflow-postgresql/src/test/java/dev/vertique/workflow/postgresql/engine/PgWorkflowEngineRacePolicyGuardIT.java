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
 * Verifies the engine's race-policy guard.
 *
 * <p>The DSL and {@link WorkflowPlanValidator} accept {@code FirstSuccessJoinPolicy} and
 * {@code FirstFailureJoinPolicy} (so plans validate cleanly), but PRD-WF-002 V1 only implements
 * {@code AllRequiredJoinPolicy} evaluation. The engine guards {@code evaluateJoin} against
 * non-{@code AllRequired} policies and fails the workflow with a
 * {@link WorkflowDefinitionException} that names the unimplemented policy. Without this guard, a
 * validated {@code FIRST_SUCCESS} workflow would silently execute with all-required semantics.
 *
 * <p>This test guards against the regression risk and is the canonical failure-mode contract
 * until race-join evaluation lands.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineRacePolicyGuardIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_race_guard_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    record StartRace(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "race-" + id;
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
            return "race-guard";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartRace.class, p -> new State(p.id()))
                    .fork("fork")
                    .branch("a", "step-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "step-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .complete("step-a")
                    .complete("step-b")
                    .join("join")
                    .firstSuccess((s, results) -> s)
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

        // Register every branch target as IGNORE_LATE_RESULT_SAFE so the validator accepts the
        // FIRST_SUCCESS plan. Branches are CompleteNode-only here, so no actual targets to register.
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
    @DisplayName("AC #11 — FIRST_SUCCESS advances on the first branch to reach COMPLETED; siblings become SUPERSEDED")
    void firstSuccessAdvancesOnFirstCompletion(VertxTestContext ctx) {
        StartRace payload = new StartRace("r1");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        engine.start(cmd)
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.start should succeed under FIRST_SUCCESS: " + ar.cause());
                    assertEquals(
                            dev.vertique.workflow.state.WorkflowStatus.COMPLETED,
                            ar.result().instance().status());
                    pool.preparedQuery(
                                    "SELECT status, winning_branch_id FROM workflow_join_states WHERE workflow_id = $1")
                            .execute(io.vertx.sqlclient.Tuple.of(
                                    ar.result().instance().id().value()))
                            .onComplete(jr -> ctx.verify(() -> {
                                int joinRows = 0;
                                for (var row : jr.result()) {
                                    joinRows++;
                                    assertEquals("COMPLETED", row.getString("status"));
                                    assertTrue(
                                            row.getString("winning_branch_id") != null,
                                            "winning_branch_id should be set");
                                }
                                assertEquals(1, joinRows);
                                ctx.completeNow();
                            }));
                }));
    }
}
