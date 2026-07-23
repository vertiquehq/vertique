// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Verifies that an explicit {@code cancel()} call cascades through every active fan-out branch,
 * closing branch-owned timers and tasks before marking each branch CANCELLED, and finally marking
 * the workflow instance CANCELLED (PRD-WF-002 lock order: timers → tasks → branch tokens →
 * instance).
 *
 * <p>Workflow shape:
 * <pre>
 *   init → fork (ALL_REQUIRED)
 *          branch a: waitForSignal("go-a") → complete-a    ← parks WAITING
 *          branch b: waitForSignal("go-b") → complete-b    ← parks WAITING
 *   join
 * </pre>
 *
 * <p>After both branches park, the test inserts an open task and a scheduled timer tied to each
 * branch token id directly via the stores (bypassing the validator gate). Then it calls
 * {@code engine.cancel(workflowId, "operator")}, which must:
 * <ol>
 *   <li>Cancel branch-a's timer.</li>
 *   <li>Cancel branch-a's task.</li>
 *   <li>Mark branch-a CANCELLED.</li>
 *   <li>Cancel branch-b's timer.</li>
 *   <li>Cancel branch-b's task.</li>
 *   <li>Mark branch-b CANCELLED.</li>
 *   <li>Mark the workflow instance CANCELLED.</li>
 * </ol>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineWorkflowCancelCascadesIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_cancel_cascades_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgTimerStore timerStore;
    static PgTaskStore taskStore;
    static PgBranchTokenRepository branchRepo;

    record StartFanout(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "cancel-cascades-" + id;
        }
    }

    record State(String id) {}

    record GoSignal(String v) {}

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
            return "cancel-cascades-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartFanout.class, p -> new State(p.id()))
                    .fork("fork")
                    .branch("a", "wait-a")
                    .branch("b", "wait-b")
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

    private Future<Void> insertTaskForBranch(WorkflowInstanceId workflowId, UUID branchTokenId, String branchId) {
        TaskRecord record = new TaskRecord(
                UUID.randomUUID(),
                workflowId,
                "task-step-" + branchId,
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
                branchId);
        return pool.withTransaction(tx -> taskStore.insertOpen(record, tx));
    }

    private Future<Void> insertTimerForBranch(WorkflowInstanceId workflowId, UUID branchTokenId, String branchId) {
        TimerRecord record = new TimerRecord(
                UUID.randomUUID(),
                workflowId,
                "timer-step-" + branchId,
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
                branchId,
                DurableMetadata.empty());
        return pool.withTransaction(tx -> timerStore.insertScheduled(record, tx));
    }

    // --- Tests ---

    @Test
    @DisplayName(
            "cancel() closes every active branch's owned timer and task before marking branches and instance CANCELLED")
    void cancelCascadesThroughBranchOwnedWaits(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("c1");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        AtomicReference<WorkflowInstanceId> idRef = new AtomicReference<>();
        AtomicReference<UUID> branchATokenId = new AtomicReference<>();
        AtomicReference<UUID> branchBTokenId = new AtomicReference<>();

        // Phase 1: start the workflow — both branches park at WAITING.
        engine.start(cmd)
                .compose(workflowId -> {
                    idRef.set(workflowId);
                    return pool.preparedQuery("SELECT id, branch_id FROM workflow_branch_tokens"
                                    + " WHERE workflow_id = $1 ORDER BY branch_id")
                            .execute(Tuple.of(workflowId.value()));
                })
                .compose(rs -> {
                    for (var row : rs) {
                        String branchId = row.getString("branch_id");
                        UUID tokenId = row.getUUID("id");
                        if ("a".equals(branchId)) branchATokenId.set(tokenId);
                        else if ("b".equals(branchId)) branchBTokenId.set(tokenId);
                    }
                    assertNotNull(branchATokenId.get(), "branch-a token id must be found");
                    assertNotNull(branchBTokenId.get(), "branch-b token id must be found");

                    // Phase 2: inject timer + task for each branch, chained as async Futures —
                    // calling .join() here would block the event loop and deadlock.
                    WorkflowInstanceId workflowId = idRef.get();
                    return insertTimerForBranch(workflowId, branchATokenId.get(), "a")
                            .compose(v -> insertTaskForBranch(workflowId, branchATokenId.get(), "a"))
                            .compose(v -> insertTimerForBranch(workflowId, branchBTokenId.get(), "b"))
                            .compose(v -> insertTaskForBranch(workflowId, branchBTokenId.get(), "b"))
                            // Phase 3: cancel the workflow.
                            .compose(v -> engine.cancel(workflowId, "operator"));
                })
                // Phase 4: assert all branch-a rows are CANCELLED.
                .compose(v -> pool.preparedQuery("SELECT status FROM workflow_timers WHERE branch_token_id = $1")
                        .execute(Tuple.of(branchATokenId.get())))
                .compose(rs -> {
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-a timer must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE branch_token_id = $1")
                            .execute(Tuple.of(branchATokenId.get()));
                })
                .compose(rs -> {
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-a task must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_branch_tokens WHERE id = $1")
                            .execute(Tuple.of(branchATokenId.get()));
                })
                .compose(rs -> {
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-a token must be CANCELLED");
                    // Phase 5: assert all branch-b rows are CANCELLED.
                    return pool.preparedQuery("SELECT status FROM workflow_timers WHERE branch_token_id = $1")
                            .execute(Tuple.of(branchBTokenId.get()));
                })
                .compose(rs -> {
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-b timer must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE branch_token_id = $1")
                            .execute(Tuple.of(branchBTokenId.get()));
                })
                .compose(rs -> {
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-b task must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_branch_tokens WHERE id = $1")
                            .execute(Tuple.of(branchBTokenId.get()));
                })
                .compose(rs -> {
                    assertEquals(
                            "CANCELLED", rs.iterator().next().getString("status"), "branch-b token must be CANCELLED");
                    // Phase 6: assert the workflow instance is CANCELLED.
                    return engine.query(idRef.get());
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "full cancel cascade must succeed: " + ar.cause());
                    assertEquals(
                            WorkflowStatus.CANCELLED, ar.result().instance().status(), "instance must be CANCELLED");
                    ctx.completeNow();
                }));
    }
}
