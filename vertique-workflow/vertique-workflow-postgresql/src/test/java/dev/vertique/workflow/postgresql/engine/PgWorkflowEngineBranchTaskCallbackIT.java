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
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
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
import dev.vertique.workflow.state.JoinPolicyType;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for branch-aware task-callback routing in {@link WorkflowEngineHandle}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Branch task completion advances only the owning branch; sibling state untouched.</li>
 *   <li>Late completion on a SUPERSEDED branch records
 *       {@link WorkflowEntryType#BRANCH_LATE_CALLBACK_IGNORED} and returns
 *       {@link TaskMutationResult#STALE_NOOP}.</li>
 *   <li>Branch task due-date fires: cancels reminder timers, marks task EXPIRED, advances the owning
 *       branch only.</li>
 * </ul>
 *
 * <p>Rows are inserted directly via the stores (bypassing the plan validator gate, which still
 * blocks branch-owned {@code HumanTaskNode} plans in slice 3). This mirrors the pattern established
 * by {@link PgWorkflowEngineCancelBranchOwnedWaitsIT}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchTaskCallbackIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_task_callback_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgTaskStore taskStore;
    static PgTimerStore timerStore;
    static PgBranchTokenRepository branchRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgJoinStateRepository joinStateRepo;
    static DefaultWorkflowRegistry registry;

    record StartTask(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "branch-task-cb-" + id;
        }
    }

    record State(String id) {}

    interface TaskCallbackContract {}

    /**
     * Workflow plan:
     * <pre>
     *   fork
     *   ├─ branch-a: task("task-step", decision approve→complete-step, due-date→due-step) → complete-step
     *   └─ branch-b: waitForSignal("signal-step", "my-signal") → complete-b
     *   join (all-required) → done
     * </pre>
     *
     * <p>The HumanTaskNode at {@code task-step} has both a decision ({@code approve} →
     * {@code complete-step}) and a due-date branch ({@code due-step}). Tests #1 and #2 use the
     * decision path; test #3 uses the due-date branch.
     */
    static final WorkflowDefinition<State, TaskCallbackContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<TaskCallbackContract> contract() {
            return TaskCallbackContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "branch-task-callback";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartTask.class, p -> new State(p.id()))
                    .fork("fork-step")
                    .branch("branch-a", "task-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "signal-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .task("task-step")
                    .assignToRole("reviewers")
                    .dueIn(java.time.Duration.ofHours(1))
                    .onDue(s -> s)
                    .toStepOnDue("due-step")
                    .decision("approve", Void.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-step")
                    .build()
                    .complete("complete-step")
                    .complete("due-step")
                    .waitForSignal("signal-step", "my-signal", Object.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-b")
                    .build()
                    .complete("complete-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .onFailure("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork-step");
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
        joinStateRepo = new PgJoinStateRepository(pool, ex);
        PgJoinStateRepository joins = joinStateRepo;
        timerStore = new PgTimerStore(ex);
        taskStore = new PgTaskStore(pool, ex);
        // No-validator registry: branch-owned HumanTaskNode is accepted by the production validator
        // (gate lifted earlier in this PR), but skipping validation here keeps the test setup focused.
        registry = new DefaultWorkflowRegistry();
        registry.register(DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                historyRepo,
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

    // --- Seed helpers ---

    private WorkflowInstanceId seedInstance() {
        UUID id = UUID.randomUUID();
        String planHash = registry.resolvePinned(DEFINITION.definitionId(), DEFINITION.definitionVersion())
                .plan()
                .planHash();
        // Park the parent at current_step_id='join' with wait_key='fork-step' — production
        // handleForkNode parks here after the fork dispatch. applyAllRequired's join-advancement
        // fence requires currentStepId == joinNode.stepId(), so a parent seeded at
        // current_step_id='fork-step' would silently mask join advancement.
        pool.preparedQuery("INSERT INTO workflow_instances"
                        + " (id, definition_id, definition_version, plan_hash, version,"
                        + "  status, current_step_id, wait_type, wait_key, state_json)"
                        + " VALUES ($1, $2, $3, $4, 0, 'RUNNING', 'join',"
                        + "  'JOIN', 'fork-step', '{}'::jsonb)")
                .execute(Tuple.of(id, DEFINITION.definitionId(), (long) DEFINITION.definitionVersion(), planHash))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        WorkflowInstanceId workflowId = new WorkflowInstanceId(id);
        Instant now = Instant.now();
        JoinState js = new JoinState(
                workflowId,
                "fork-step",
                "join",
                JoinPolicyType.ALL_REQUIRED,
                JoinStateStatus.OPEN,
                null,
                null,
                0L,
                now,
                now);
        pool.withTransaction(tx -> joinStateRepo.insert(js, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return workflowId;
    }

    private BranchToken seedBranchWaitingOnTask(WorkflowInstanceId workflowId, String branchId, UUID taskId) {
        Instant now = Instant.now();
        BranchToken token = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                branchId,
                "task-step",
                BranchStatus.WAITING,
                WaitType.TASK,
                taskId.toString(),
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

    private BranchToken seedBranchWaiting(WorkflowInstanceId workflowId, String branchId, BranchStatus status) {
        Instant now = Instant.now();
        BranchToken token = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                branchId,
                "signal-step",
                status,
                status == BranchStatus.WAITING ? WaitType.SIGNAL : null,
                status == BranchStatus.WAITING ? "my-signal" : null,
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

    private TaskRecord seedTask(WorkflowInstanceId workflowId, UUID branchTokenId, String branchId, UUID taskId) {
        TaskRecord record = new TaskRecord(
                taskId,
                workflowId,
                "task-step",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.Void", "complete-step")),
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
                "fork-step",
                branchId);
        pool.withTransaction(tx -> taskStore.insertOpen(record, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return record;
    }

    private TaskRecord seedTaskWithDueDate(
            WorkflowInstanceId workflowId, UUID branchTokenId, String branchId, UUID dueDateTimerId) {
        UUID taskId = UUID.randomUUID();
        TaskRecord record = new TaskRecord(
                taskId,
                workflowId,
                "task-step",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.Void", "complete-step")),
                Instant.now().plusSeconds(3600),
                dueDateTimerId,
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
                "fork-step",
                branchId);
        pool.withTransaction(tx -> taskStore.insertOpen(record, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return record;
    }

    private TimerRecord seedTimer(
            WorkflowInstanceId workflowId, UUID branchTokenId, String branchId, TimerPurpose purpose, UUID taskId) {
        TimerRecord record = new TimerRecord(
                UUID.randomUUID(),
                workflowId,
                "task-step",
                Instant.now().plusSeconds(3600),
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                null,
                null,
                purpose,
                taskId,
                branchTokenId,
                "fork-step",
                branchId,
                DurableMetadata.empty());
        pool.withTransaction(tx -> timerStore.insertScheduled(record, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return record;
    }

    // Async helpers — return Future<...> so tests can compose them onto the engine's main future
    // chain. Blocking on .join() inside ctx.verify() (which runs on the Vert.x event loop after
    // onComplete) deadlocks the loop and surfaces as a bare 30s TimeoutException.
    private Future<String> branchStatusAsync(UUID branchTokenId) {
        return pool.preparedQuery("SELECT status FROM workflow_branch_tokens WHERE id = $1")
                .execute(Tuple.of(branchTokenId))
                .map(rs -> rs.iterator().next().getString("status"));
    }

    /** Persisted branch-token state snapshot used for assertion ergonomics. */
    record BranchSnapshot(String status, String currentStepId) {}

    private Future<BranchSnapshot> branchSnapshotAsync(UUID branchTokenId) {
        return pool.preparedQuery("SELECT status, current_step_id FROM workflow_branch_tokens WHERE id = $1")
                .execute(Tuple.of(branchTokenId))
                .map(rs -> {
                    io.vertx.sqlclient.Row row = rs.iterator().next();
                    return new BranchSnapshot(row.getString("status"), row.getString("current_step_id"));
                });
    }

    private Future<String> taskStatusAsync(UUID taskId) {
        return pool.preparedQuery("SELECT status FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getString("status"));
    }

    private Future<String> timerStatusAsync(UUID timerId) {
        return pool.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                .execute(Tuple.of(timerId))
                .map(rs -> rs.iterator().next().getString("status"));
    }

    private Future<Long> countHistoryAsync(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_history WHERE workflow_id = $1 AND entry_type = $2")
                .execute(Tuple.of(workflowId.value(), entryType.name()))
                .map(rs -> rs.iterator().next().getLong(0));
    }

    // --- Tests ---

    @Test
    @DisplayName("branch task completion advances only the owning branch; sibling branch state untouched")
    void branchTaskCompletionAdvancesOwningBranchOnly(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance();
        // Branch A: waiting on a task. Use the same taskId for both branch waitKey and the task row.
        UUID taskId = UUID.randomUUID();
        BranchToken branchA = seedBranchWaitingOnTask(workflowId, "branch-a", taskId);
        TaskRecord task = seedTask(workflowId, branchA.id(), "branch-a", taskId);
        // Branch B: waiting on a signal (unrelated)
        BranchToken branchB = seedBranchWaiting(workflowId, "branch-b", BranchStatus.WAITING);

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                task.taskId(), "approve", null, "idem-key-1", new WorkflowActor.User("user-1"), null);

        record Outcome(
                TaskMutationResult result,
                String taskStatus,
                long completedEntries,
                BranchSnapshot branchA,
                String siblingStatus) {}

        pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                .compose(result -> taskStatusAsync(task.taskId()).compose(taskStatus -> countHistoryAsync(
                                workflowId, WorkflowEntryType.BRANCH_TASK_COMPLETED)
                        .compose(completedEntries -> branchSnapshotAsync(branchA.id())
                                .compose(branchASnapshot -> branchStatusAsync(branchB.id())
                                        .map(statusB -> new Outcome(
                                                result, taskStatus, completedEntries, branchASnapshot, statusB))))))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals(TaskMutationResult.APPLIED, o.result(), "completion must be APPLIED");
                    assertEquals("COMPLETED", o.taskStatus(), "task must be COMPLETED");
                    assertTrue(o.completedEntries() >= 1L, "BRANCH_TASK_COMPLETED history must be appended");
                    assertEquals("COMPLETED", o.branchA().status(), "branch A must advance to COMPLETED");
                    assertEquals(
                            "complete-step",
                            o.branchA().currentStepId(),
                            "branch A must reach the decision's nextStepId 'complete-step'");
                    assertEquals("WAITING", o.siblingStatus(), "sibling branch-b must stay WAITING");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("late completion on SUPERSEDED branch records BRANCH_LATE_CALLBACK_IGNORED and returns STALE_NOOP")
    void lateCompletionOnSupersededBranchIsNoOp(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance();
        UUID taskId = UUID.randomUUID();
        // Branch A: superseded (already lost race-join) but task is still OPEN
        BranchToken branchA = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                "branch-a",
                "task-step",
                BranchStatus.SUPERSEDED,
                null,
                null,
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
                Instant.now(),
                Instant.now(),
                DurableMetadata.empty());
        pool.withTransaction(tx -> branchRepo.insert(branchA, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        TaskRecord task = new TaskRecord(
                taskId,
                workflowId,
                "task-step",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.Void", "complete-step")),
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
                branchA.id(),
                "fork-step",
                "branch-a");
        pool.withTransaction(tx -> taskStore.insertOpen(task, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", null, "idem-key-2", new WorkflowActor.User("user-2"), null);

        record Outcome(TaskMutationResult result, long ignoredCount) {}

        pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                .compose(result -> countHistoryAsync(workflowId, WorkflowEntryType.BRANCH_LATE_CALLBACK_IGNORED)
                        .map(ignoredCount -> new Outcome(result, ignoredCount)))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals(TaskMutationResult.STALE_NOOP, o.result(), "late completion must be STALE_NOOP");
                    assertTrue(o.ignoredCount() >= 1L, "BRANCH_LATE_CALLBACK_IGNORED must be recorded");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("branch task due-date fires: cancels reminder timers and advances owning branch")
    void branchTaskDueDateFiresCancelsRemindersAndAdvancesBranch(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance();

        // Seed branch B as WAITING — the fork is ALL_REQUIRED with two branches; without branch B
        // the join would see "all seeded branches done" once branch A expires and decide the join,
        // silently masking the per-branch advance behavior under test.
        BranchToken branchB = seedBranchWaiting(workflowId, "branch-b", BranchStatus.WAITING);

        // Seed a reminder timer and a due-date timer
        UUID dueDateTimerId = UUID.randomUUID();
        // Branch A waiting on a task that has a due-date timer
        BranchToken branchA = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                "branch-a",
                "task-step",
                BranchStatus.WAITING,
                WaitType.TASK,
                null, // waitKey set below after we know taskId
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
                Instant.now(),
                Instant.now(),
                DurableMetadata.empty());
        pool.withTransaction(tx -> branchRepo.insert(branchA, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Decide the taskId up front so we can seed the due-date timer's task_id correctly. The
        // FK is workflow_tasks.due_date_timer_id → workflow_timers.timer_id, so the timer must
        // exist before the task references it. Production order is the same: recorder inserts the
        // timer (with null task_id), then the engine inserts the task pointing at that timer id.
        UUID taskId = UUID.randomUUID();

        // 1. Insert the due-date timer first (task_id null at this stage to satisfy task_id-purpose
        //    pairing — TASK_DUE requires non-null task_id, so we go the other way: insert the task
        //    first with a null due_date_timer_id, then the timer, then patch task.due_date_timer_id).
        TaskRecord task = new TaskRecord(
                taskId,
                workflowId,
                "task-step",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "java.lang.Void", "complete-step")),
                Instant.now().plusSeconds(3600),
                null, // dueDateTimerId — patched after the timer is inserted (FK)
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
                branchA.id(),
                "fork-step",
                "branch-a");
        pool.withTransaction(tx -> taskStore.insertOpen(task, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Update the branch token to point to the task
        pool.preparedQuery("UPDATE workflow_branch_tokens SET wait_key = $1, version = version + 1" + " WHERE id = $2")
                .execute(Tuple.of(task.taskId().toString(), branchA.id()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // 2. Insert the due-date timer (task_id non-null, matches the just-inserted task).
        TimerRecord dueDateTimer = new TimerRecord(
                dueDateTimerId,
                workflowId,
                "task-step",
                Instant.now().minusSeconds(1),
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                Instant.now().minusSeconds(100),
                null,
                null,
                null,
                null,
                TimerPurpose.TASK_DUE,
                task.taskId(),
                branchA.id(),
                "fork-step",
                "branch-a",
                DurableMetadata.empty());
        pool.withTransaction(tx -> timerStore.insertScheduled(dueDateTimer, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // 3. Patch the task to point at the now-existing timer (satisfies the FK forward-direction).
        pool.preparedQuery("UPDATE workflow_tasks SET due_date_timer_id = $1 WHERE task_id = $2")
                .execute(Tuple.of(dueDateTimerId, task.taskId()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Seed a reminder timer for the same task
        TimerRecord reminderTimer =
                seedTimer(workflowId, branchA.id(), "branch-a", TimerPurpose.TASK_REMINDER, task.taskId());

        record Outcome(
                TaskMutationResult result,
                String taskStatus,
                String reminderStatus,
                long expiredCount,
                BranchSnapshot branchA,
                String siblingStatus) {}

        // Call taskDueFired — the executor routes TASK_DUE timers through this entry point (see
        // WorkflowTimerFireExecutor.dispatchTaskDueFired), not the generic timerFired path.
        pool.withTransaction(tx -> engine.taskDueFired(workflowId, task.taskId(), tx))
                .compose(result -> taskStatusAsync(task.taskId())
                        .compose(taskStatus -> timerStatusAsync(reminderTimer.timerId())
                                .compose(reminderStatus -> countHistoryAsync(
                                                workflowId, WorkflowEntryType.BRANCH_TASK_DUE_EXPIRED)
                                        .compose(expiredCount -> branchSnapshotAsync(branchA.id())
                                                .compose(branchASnapshot -> branchStatusAsync(branchB.id())
                                                        .map(siblingStatus -> new Outcome(
                                                                result,
                                                                taskStatus,
                                                                reminderStatus,
                                                                expiredCount,
                                                                branchASnapshot,
                                                                siblingStatus)))))))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals(TaskMutationResult.APPLIED, o.result(), "due-date fire must be APPLIED");
                    assertEquals("EXPIRED", o.taskStatus(), "task must be EXPIRED");
                    assertEquals("CANCELLED", o.reminderStatus(), "reminder timer must be CANCELLED");
                    assertTrue(o.expiredCount() >= 1L, "BRANCH_TASK_DUE_EXPIRED history must be appended");
                    assertEquals("COMPLETED", o.branchA().status(), "branch A must advance to COMPLETED");
                    assertEquals(
                            "due-step",
                            o.branchA().currentStepId(),
                            "branch A must reach the due-date CompleteNode 'due-step'");
                    assertEquals("WAITING", o.siblingStatus(), "sibling branch-b must stay WAITING");
                    ctx.completeNow();
                })));
    }
}
