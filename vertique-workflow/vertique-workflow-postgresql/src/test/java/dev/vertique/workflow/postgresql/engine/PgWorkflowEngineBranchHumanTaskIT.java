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
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Instant;
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
 * Integration tests for {@link BranchTransitionEngine#branchHandleHumanTaskNode} (Slice 4).
 *
 * <p>Uses a workflow plan with a fork where branch A enters a {@code HumanTaskNode} and branch B
 * waits on a signal. The {@link DefaultWorkflowRegistry} is constructed without a
 * {@link dev.vertique.workflow.plan.WorkflowPlanValidator} so the branch-owned
 * {@code HumanTaskNode} plan is accepted — the validator gate is slice 5's responsibility.
 *
 * <p>Covers:
 * <ul>
 *   <li>Branch A creates a task; branch B still waiting; parent stays at JOIN.</li>
 *   <li>Task row carries branch identity ({@code branch_token_id / fork_step_id / branch_id}).</li>
 *   <li>Branch A completion triggers join evaluation; branch B still waiting keeps parent at JOIN.</li>
 *   <li>Both branches completing results in workflow COMPLETED.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchHumanTaskIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_human_task_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgTaskStore taskStore;
    static PgBranchTokenRepository branchRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    /** Captures the UUID of the most recently created timer. */
    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    record StartBranchTask(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "branch-task-" + id;
        }
    }

    record State(String id) {}

    interface BranchTaskContract {}

    /**
     * Fork plan:
     * <pre>
     *   fork
     *   ├─ branch-a: task("task-step") → complete-a
     *   └─ branch-b: waitSignal("my-signal") → complete-b
     *   join (all-required) → done
     * </pre>
     */
    static final WorkflowDefinition<State, BranchTaskContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<BranchTaskContract> contract() {
            return BranchTaskContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "branch-human-task";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartBranchTask.class, p -> new State(p.id()))
                    .fork("fork")
                    .branch("branch-a", "task-step", dev.vertique.workflow.plan.RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "signal-step", dev.vertique.workflow.plan.RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    // Branch A: task then complete
                    .task("task-step")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .build()
                    .complete("complete-a")
                    // Branch B: wait-signal then complete
                    .waitForSignal("signal-step", "my-signal", Object.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-b")
                    .build()
                    .complete("complete-b")
                    // Join
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .onFailure("done")
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
        historyRepo = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        branchRepo = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);
        timerStore = new PgTimerStore(ex);
        taskStore = new PgTaskStore(pool, ex);

        // A recorder that captures the last timer id for assertions.
        WorkflowSideEffectRecorder<SqlClient> timerCapture = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                TimerIntentPayload payload = (TimerIntentPayload) intent.payload();
                UUID timerId = UUID.randomUUID();
                lastTimerId.set(timerId);
                TimerRecord row = new TimerRecord(
                        timerId,
                        intent.correlation().workflowId(),
                        intent.correlation().stepId(),
                        payload.fireAt(),
                        TimerStatus.SCHEDULED,
                        UUID.randomUUID(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        payload.purpose(),
                        payload.taskId(),
                        intent.correlation().branchTokenId(),
                        intent.correlation().forkStepId(),
                        intent.correlation().branchId(),
                        DurableMetadata.empty());
                return timerStore.insertScheduled(row, tx).map(v -> new RecorderResult.Timer(timerId));
            }
        };

        Set<WorkflowSideEffectRecorder<SqlClient>> recorders = Set.of(timerCapture);

        // No-validator registry so branch-owned HumanTaskNode is accepted.
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                historyRepo,
                dedup,
                recorders,
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
        lastTimerId.set(null);
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

    private WorkflowInstanceId startWorkflow(String id) {
        StartCommand cmd =
                new StartCommand(DEFINITION.definitionId(), new StartBranchTask(id), "idem-" + id, null, null);
        return engine.start(cmd).toCompletionStage().toCompletableFuture().join();
    }

    private String instanceStatus(WorkflowInstanceId id) {
        return pool.preparedQuery("SELECT status FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value()))
                .map(rs -> rs.iterator().next().getString("status"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private UUID findOpenTaskForWorkflow(WorkflowInstanceId workflowId) {
        return pool.preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN' LIMIT 1")
                .execute(Tuple.of(workflowId.value()))
                .map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? it.next().getUUID("task_id") : null;
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private JsonObject taskRow(UUID taskId) {
        return pool.preparedQuery(
                        "SELECT branch_token_id, fork_step_id, branch_id FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> {
                    var row = rs.iterator().next();
                    return new JsonObject()
                            .put(
                                    "branchTokenId",
                                    row.getUUID("branch_token_id") != null
                                            ? row.getUUID("branch_token_id").toString()
                                            : null)
                            .put("forkStepId", row.getString("fork_step_id"))
                            .put("branchId", row.getString("branch_id"));
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    // --- Tests ---

    @Test
    @DisplayName("branch A creates a task with branch identity; branch B still waiting; parent stays at JOIN")
    void branchACreatesTaskParentStaysAtJoin(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWorkflow("wf-1");

        // After fork dispatch, branch A should be WAITING on a task.
        UUID taskId = findOpenTaskForWorkflow(workflowId);
        assertNotNull(taskId, "branch A must have created an open task");

        // Verify branch identity is persisted on the task row.
        JsonObject task = taskRow(taskId);
        assertNotNull(task.getString("branchTokenId"), "task must carry branchTokenId");
        assertEquals("fork", task.getString("forkStepId"), "task must carry forkStepId='fork'");
        assertEquals("branch-a", task.getString("branchId"), "task must carry branchId='branch-a'");

        // Parent instance must still be parked at the JOIN (not COMPLETED/FAILED).
        String status = instanceStatus(workflowId);
        assertTrue(
                status.equals("RUNNING") || status.equals("WAITING"),
                "parent instance must still be alive (at JOIN), got: " + status);

        ctx.completeNow();
    }

    @Test
    @DisplayName("branch A task completion then branch B signal completion advances workflow to COMPLETED")
    void bothBranchesCompleteWorkflowReachesCompleted(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWorkflow("wf-2");

        UUID taskId = findOpenTaskForWorkflow(workflowId);
        assertNotNull(taskId, "branch A task must exist");

        // Complete branch A's task. This proves the load-bearing fix: the branch token must be
        // advanced from WAITING/TASK to RUNNING with currentStepId=decision.nextStepId, then
        // driven through complete-a so the join evaluates branch A as COMPLETED.
        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", null, "idem-task-1", new WorkflowActor.User("user-1"), null);
        TaskMutationResult taskResult = pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals(TaskMutationResult.APPLIED, taskResult, "task completion must be APPLIED");

        // Parent must still be at JOIN (branch B hasn't completed yet).
        String statusAfterA = instanceStatus(workflowId);
        assertTrue(
                statusAfterA.equals("RUNNING") || statusAfterA.equals("WAITING"),
                "parent must still be at JOIN after only branch A completes, got: " + statusAfterA);

        // Apply branch-aware signal for branch B (parent is parked at WAITING/JOIN, so the
        // single-path 4-arg signal would reject; use the 7-arg branch-aware overload).
        pool.withTransaction(tx ->
                        engine.signal(workflowId, "my-signal", new Object(), "idem-signal-1", "fork", "branch-b", tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Now both branches are done — workflow must be COMPLETED.
        assertEquals("COMPLETED", instanceStatus(workflowId), "workflow must be COMPLETED after both branches done");
        ctx.completeNow();
    }

    @Test
    @DisplayName("branch task row carries branch_token_id, fork_step_id, and branch_id after fork dispatch")
    void branchTaskRowCarriesBranchIdentity(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWorkflow("wf-3");

        UUID taskId = findOpenTaskForWorkflow(workflowId);
        assertNotNull(taskId, "task must exist after fork dispatch");

        JsonObject row = taskRow(taskId);
        assertNotNull(row.getString("branchTokenId"), "branch_token_id must be non-null on task row");
        assertNotNull(row.getString("forkStepId"), "fork_step_id must be non-null on task row");
        assertEquals("branch-a", row.getString("branchId"), "branch_id must be 'branch-a'");
        ctx.completeNow();
    }
}
