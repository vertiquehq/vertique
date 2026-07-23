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
import dev.vertique.workflow.engine.WorkflowEngineHandle;
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
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the branch-owned-wait cancellation cascade driven by the public
 * {@code cancel(WorkflowInstanceId, String, SqlClient)} operation.
 *
 * <p>When an instance parked at {@code WAITING/JOIN} is cancelled, the public {@code cancel} closes
 * every active fan-out branch's owned waits (the former package-private {@code cancelBranchOwnedWaits}
 * helper) before transitioning the branch tokens and the instance to {@code CANCELLED}. These tests
 * drive that public path and verify the same observable cascade:
 * <ul>
 *   <li>All {@code SCHEDULED} timers owned by an active branch are marked {@code CANCELLED}.</li>
 *   <li>All {@code OPEN} tasks owned by an active branch are marked {@code CANCELLED}.</li>
 *   <li>Timers/tasks owned by a <em>different</em> branch of the same instance are left untouched.</li>
 *   <li>The cancelled branch's token row is transitioned to {@code CANCELLED} by the cancel.</li>
 * </ul>
 *
 * <p>Rows are inserted via store methods directly (bypassing the engine's plan-validator gate) and
 * the parent instance is seeded {@code WAITING} at the join so the public {@code cancel} reaches the
 * branch cascade without first taking an instance-level (timer/task/signal) wait path.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineCancelBranchOwnedWaitsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_cancel_branch_waits_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgTimerStore timerStore;
    static PgTaskStore taskStore;
    static PgBranchTokenRepository branchRepo;

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

    /** Seeds a minimal workflow_instances row and returns its id. */
    private WorkflowInstanceId seedInstance() {
        UUID id = UUID.randomUUID();
        pool.preparedQuery("INSERT INTO workflow_instances"
                        + " (id, definition_id, definition_version, plan_hash, version,"
                        + "  status, current_step_id, state_json)"
                        + " VALUES ($1, 'test-def', 1, 'hash', 0, 'WAITING', 'join', '{}'::jsonb)")
                .execute(Tuple.of(id))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return new WorkflowInstanceId(id);
    }

    /** Seeds a branch token in WAITING status (wait_type=TASK). */
    private BranchToken seedBranchToken(WorkflowInstanceId workflowId) {
        Instant now = Instant.now();
        BranchToken token = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork",
                "branch-a",
                "task-step",
                BranchStatus.WAITING,
                WaitType.TASK,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                now,
                now,
                DurableMetadata.empty());
        pool.withTransaction(tx -> branchRepo.insert(token, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return token;
    }

    /** Seeds an open task owned by the given branch token. */
    private TaskRecord seedTask(WorkflowInstanceId workflowId, UUID branchTokenId) {
        UUID taskId = UUID.randomUUID();
        TaskRecord record = new TaskRecord(
                taskId,
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
                "branch-a");
        pool.withTransaction(tx -> taskStore.insertOpen(record, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return record;
    }

    /** Seeds a scheduled timer owned by the given branch token. */
    private TimerRecord seedTimer(WorkflowInstanceId workflowId, UUID branchTokenId) {
        UUID timerId = UUID.randomUUID();
        TimerRecord record = new TimerRecord(
                timerId,
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
                "branch-a",
                DurableMetadata.empty());
        pool.withTransaction(tx -> timerStore.insertScheduled(record, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return record;
    }

    // --- Tests ---

    @Test
    @DisplayName("cancel() cancels the branch-owned timer and task, and transitions the branch token to CANCELLED")
    void cancelsBranchOwnedTimerAndTask(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance();
        BranchToken branch = seedBranchToken(workflowId);
        TaskRecord task = seedTask(workflowId, branch.id());
        TimerRecord timer = seedTimer(workflowId, branch.id());

        pool.withTransaction(tx -> engine.cancel(workflowId, "operator", tx))
                .compose(v -> pool.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                        .execute(Tuple.of(timer.timerId())))
                .compose(rs -> {
                    String timerStatus = rs.iterator().next().getString("status");
                    assertEquals("CANCELLED", timerStatus, "timer row must be CANCELLED");
                    return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                            .execute(Tuple.of(task.taskId()));
                })
                .compose(rs -> {
                    String taskStatus = rs.iterator().next().getString("status");
                    assertEquals("CANCELLED", taskStatus, "task row must be CANCELLED");
                    // The public cancel closes the active branch as part of the cascade.
                    return pool.preparedQuery("SELECT status FROM workflow_branch_tokens WHERE id = $1")
                            .execute(Tuple.of(branch.id()));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "cascade must succeed: " + ar.cause());
                    String branchStatus = ar.result().iterator().next().getString("status");
                    assertEquals("CANCELLED", branchStatus, "active branch token must be CANCELLED by cancel()");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("cancel() cancels multiple timers owned by the branch (standalone + due-date reminder)")
    void cancelsMultipleTimersOwnedByBranch(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance();
        BranchToken branch = seedBranchToken(workflowId);
        TaskRecord task = seedTask(workflowId, branch.id());
        // Seed two timers: a due-date timer and a reminder timer, both owned by the same branch.
        TimerRecord dueDateTimer = seedTimer(workflowId, branch.id());
        TimerRecord reminderTimer = new TimerRecord(
                UUID.randomUUID(),
                workflowId,
                "timer-step-reminder",
                Instant.now().plusSeconds(1800),
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                null,
                null,
                TimerPurpose.TASK_REMINDER,
                task.taskId(),
                branch.id(),
                "fork",
                "branch-a",
                DurableMetadata.empty());
        pool.withTransaction(tx -> timerStore.insertScheduled(reminderTimer, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        pool.withTransaction(tx -> engine.cancel(workflowId, "operator", tx))
                .compose(v -> pool.preparedQuery("SELECT status FROM workflow_timers WHERE branch_token_id = $1")
                        .execute(Tuple.of(branch.id())))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "cascade must succeed: " + ar.cause());
                    int cancelled = 0;
                    for (var row : ar.result()) {
                        assertEquals("CANCELLED", row.getString("status"), "each timer must be CANCELLED");
                        cancelled++;
                    }
                    assertEquals(2, cancelled, "both timers must be cancelled");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("cancel() succeeds when the active branch has no open tasks or scheduled timers")
    void noOpWhenNothingToCancel(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance();
        seedBranchToken(workflowId);
        // No tasks or timers seeded.

        pool.withTransaction(tx -> engine.cancel(workflowId, "operator", tx))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "no-op branch cascade must still succeed: " + ar.cause());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("cancel() does not cancel timers or tasks owned by a branch of a different instance")
    void doesNotCancelOtherInstanceBranchRows(VertxTestContext ctx) {
        // Instance 1 — the one we cancel.
        WorkflowInstanceId workflowId1 = seedInstance();
        seedBranchToken(workflowId1);

        // Instance 2 — its branch + owned waits must be left untouched by cancelling instance 1.
        WorkflowInstanceId workflowId2 = seedInstance();
        BranchToken branch2 = seedBranchToken(workflowId2);
        TaskRecord task2 = seedTask(workflowId2, branch2.id());
        TimerRecord timer2 = seedTimer(workflowId2, branch2.id());

        // Cancel instance 1; the cascade is scoped to instance 1's active branches only.
        pool.withTransaction(tx -> engine.cancel(workflowId1, "operator", tx))
                .compose(v -> pool.preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                        .execute(Tuple.of(task2.taskId())))
                .compose(rs -> {
                    assertEquals(
                            "OPEN", rs.iterator().next().getString("status"), "instance-2 branch task must stay OPEN");
                    return pool.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                            .execute(Tuple.of(timer2.timerId()));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "must succeed: " + ar.cause());
                    assertEquals(
                            "SCHEDULED",
                            ar.result().iterator().next().getString("status"),
                            "instance-2 branch timer must stay SCHEDULED");
                    ctx.completeNow();
                }));
    }
}
