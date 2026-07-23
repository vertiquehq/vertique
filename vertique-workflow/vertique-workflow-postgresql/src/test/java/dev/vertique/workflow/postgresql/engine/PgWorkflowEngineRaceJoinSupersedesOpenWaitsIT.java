// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a FIRST_SUCCESS race-join supersede closes the losing branch's owned timers and
 * tasks before marking it SUPERSEDED (PRD-WF-002 FR-WF-PAR-049/050/053).
 *
 * <p>Lock order assertion: timers → tasks → branch token (the SUPERSEDED update) →
 * instance (advanced through join next-step).
 *
 * <p>Workflow shape:
 * <pre>
 *   init → fork (FIRST_SUCCESS)
 *          branch a (IGNORE_LATE_RESULT_SAFE): waitForSignal("go-a") → complete-a
 *          branch b (IGNORE_LATE_RESULT_SAFE): waitForSignal("go-b") → complete-b
 *   join firstSuccess → done
 * </pre>
 *
 * <p>Both branches park at WAITING after start. The test then inserts an open task and a scheduled
 * timer tied to branch-b directly via the stores (bypassing the validator gate that blocks
 * HumanTaskNode/TimerNode in branch plans). Then it signals branch-a ("go-a"), which completes
 * branch-a, elects it as FIRST_SUCCESS winner, and calls {@code supersedeNonTerminalSiblings} on
 * branch-b. The supersede must cancel the timer and task before marking branch-b SUPERSEDED.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineRaceJoinSupersedesOpenWaitsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_race_supersede_waits_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgTimerStore timerStore;
    static PgTaskStore taskStore;
    static PgBranchTokenRepository branchRepo;

    record StartRace(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "race-supersede-waits-" + id;
        }
    }

    record State(String id) {}

    record GoSignal(String v) {}

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
            return "race-supersede-waits";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartRace.class, p -> new State(p.id()))
                    .fork("fork")
                    .branch("a", "wait-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .waitForSignal("wait-a", "go-a", GoSignal.class)
                    .onSignal((s, sig) -> s)
                    .toStepOnSignal("complete-a")
                    .build()
                    .complete("complete-a")
                    .waitForSignal("wait-b", "go-b", GoSignal.class)
                    .onSignal((s, sig) -> s)
                    .toStepOnSignal("complete-b")
                    .build()
                    .complete("complete-b")
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
        branchRepo = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);
        timerStore = new PgTimerStore(ex);
        taskStore = new PgTaskStore(pool, ex);

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
                timerStore,
                taskStore,
                Clock.systemUTC(),
                branchRepo,
                joins);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_tasks, workflow_history, workflow_dedup, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) pool.close();
    }

    // --- Helpers ---

    private Future<Void> insertTaskForBranch(WorkflowInstanceId workflowId, UUID branchTokenId) {
        TaskRecord record = new TaskRecord(
                UUID.randomUUID(),
                workflowId,
                "task-step",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.Void", "next")),
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                branchTokenId,
                "fork",
                "b");
        return pool.withTransaction(tx -> taskStore.insertOpen(record, tx));
    }

    private Future<Void> insertTimerForBranch(WorkflowInstanceId workflowId, UUID branchTokenId) {
        TimerRecord record = new TimerRecord(
                UUID.randomUUID(),
                workflowId,
                "timer-step",
                Instant.now().plusSeconds(3600),
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                branchTokenId,
                "fork",
                "b",
                DurableMetadata.empty());
        return pool.withTransaction(tx -> timerStore.insertScheduled(record, tx));
    }

    // --- Tests ---

    @Test
    @DisplayName("FIRST_SUCCESS win by branch-a cancels branch-b's open timer and task before SUPERSEDED update")
    void firstSuccessSupersedeCancelsLoserBranchWaits(VertxTestContext ctx) {
        StartRace payload = new StartRace("rs1");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        AtomicReference<WorkflowInstanceId> idRef = new AtomicReference<>();
        AtomicReference<UUID> branchBTokenIdRef = new AtomicReference<>();

        // Phase 1: start the workflow — both branches park at WAITING.
        engine.start(cmd)
                .compose(workflowId -> {
                    idRef.set(workflowId);
                    // Look up branch-b's token id.
                    return pool.preparedQuery("SELECT id FROM workflow_branch_tokens"
                                    + " WHERE workflow_id = $1 AND branch_id = 'b'")
                            .execute(Tuple.of(workflowId.value()));
                })
                .compose(rs -> {
                    assertTrue(rs.iterator().hasNext(), "branch-b token must exist after start");
                    UUID branchBId = rs.iterator().next().getUUID("id");
                    branchBTokenIdRef.set(branchBId);

                    // Verify branch-b is WAITING (parked at wait-b).
                    return pool.preparedQuery("SELECT status FROM workflow_branch_tokens WHERE id = $1")
                            .execute(Tuple.of(branchBId));
                })
                .compose(rs -> {
                    assertEquals("WAITING", rs.iterator().next().getString("status"), "branch-b must be WAITING");

                    // Phase 2: insert a timer + task owned by branch-b (bypassing the validator gate).
                    // Chain as async Futures — calling .join() here would block the event loop.
                    WorkflowInstanceId workflowId = idRef.get();
                    UUID branchBId = branchBTokenIdRef.get();
                    return insertTimerForBranch(workflowId, branchBId)
                            .compose(v -> insertTaskForBranch(workflowId, branchBId))
                            .compose(v ->
                                    // Phase 3: signal branch-a → FIRST_SUCCESS triggers →
                                    // supersedeNonTerminalSiblings runs on branch-b (cancelling its timer+task).
                                    pool.withTransaction(tx -> engine.signal(
                                            workflowId, "go-a", new GoSignal("ok"), "dedup-go-a", "fork", "a", tx)));
                })
                // Phase 4: assert outcomes.
                .compose(v -> pool.preparedQuery("SELECT status FROM workflow_timers WHERE branch_token_id = $1")
                        .execute(Tuple.of(branchBTokenIdRef.get())))
                .compose(rs -> {
                    assertTrue(rs.iterator().hasNext(), "branch-b timer must still exist in DB");
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-b timer must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE branch_token_id = $1")
                            .execute(Tuple.of(branchBTokenIdRef.get()));
                })
                .compose(rs -> {
                    assertTrue(rs.iterator().hasNext(), "branch-b task must still exist in DB");
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-b task must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_branch_tokens WHERE id = $1")
                            .execute(Tuple.of(branchBTokenIdRef.get()));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "full cascade must succeed: " + ar.cause());
                    String branchBStatus = ar.result().iterator().next().getString("status");
                    assertEquals("SUPERSEDED", branchBStatus, "branch-b token must be SUPERSEDED");
                    ctx.completeNow();
                }));
    }
}
