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
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
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
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies branch-task {@code requireVersionStability} parity with single-path:
 * <ul>
 *   <li>Branch creation snapshots {@code subjectVersionAtCreation} from the parent instance's
 *       {@link WorkflowSubjectRef#version()}.</li>
 *   <li>Branch creation fail-fasts with {@link WorkflowSubjectVersionUnavailableException} when the
 *       plan declares {@code requireVersionStability=true} and the start command did not supply a
 *       versioned subject ref.</li>
 *   <li>Branch completion accepts a matching {@code reviewedSubjectVersion}.</li>
 *   <li>Branch completion rejects a mismatching or null {@code reviewedSubjectVersion} with
 *       {@link WorkflowStaleSubjectVersionException}.</li>
 * </ul>
 *
 * <p>Single-path parity is covered by {@code RequireVersionStabilityIT}; this IT exercises the
 * branch-aware fork in {@link WorkflowEngineHandle#taskCompleted} (i.e., {@code doBranchCompleteTask})
 * and the branch creation path in {@link BranchTransitionEngine}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchVersionStabilityIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_vsa_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    record StartReview(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "branch-vsa-" + id;
        }
    }

    record State(String id) {}

    interface BranchVsaContract {}

    /**
     * Fork plan with a {@code requireVersionStability=true} branch task:
     * <pre>
     *   fork
     *   ├─ branch-a: task("review", requireVersionStability=true) → complete-a
     *   └─ branch-b: waitSignal("my-signal") → complete-b
     *   join (all-required) → done
     * </pre>
     */
    static final WorkflowDefinition<State, BranchVsaContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<BranchVsaContract> contract() {
            return BranchVsaContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "branch-vsa";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartReview.class, p -> new State(p.id()))
                    .fork("fork")
                    .branch("branch-a", "review", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "signal-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .task("review")
                    .assignToRole("reviewers")
                    .requireVersionStability()
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .build()
                    .complete("complete-a")
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
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        PgBranchTokenRepository branchRepo = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);
        PgTimerStore timerStore = new PgTimerStore(ex);
        PgTaskStore taskStore = new PgTaskStore(pool, ex);

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
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

    // --- Helpers ---

    private WorkflowInstanceId startWithSubjectVersion(String id, String subjectVersion) {
        WorkflowSubjectRef ref = subjectVersion == null ? null : new WorkflowSubjectRef("Order", id, subjectVersion);
        StartCommand cmd =
                new StartCommand(DEFINITION.definitionId(), new StartReview(id), "idem-vsa-" + id, null, ref);
        return engine.start(cmd).toCompletionStage().toCompletableFuture().join();
    }

    private UUID findBranchATask(WorkflowInstanceId workflowId) {
        return pool.preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND branch_id = 'branch-a'")
                .execute(Tuple.of(workflowId.value()))
                .map(rs -> rs.iterator().next().getUUID("task_id"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    private String taskSubjectVersion(UUID taskId) {
        return pool.preparedQuery("SELECT subject_version_at_creation FROM workflow_tasks WHERE task_id = $1")
                .execute(Tuple.of(taskId))
                .map(rs -> rs.iterator().next().getString("subject_version_at_creation"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    // --- Tests ---

    @Test
    @DisplayName("branch task row snapshots subjectVersionAtCreation from the parent instance's subject ref")
    void branchTaskSnapshotsSubjectVersion(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWithSubjectVersion("snap-1", "v7");

        UUID taskId = findBranchATask(workflowId);
        assertNotNull(taskId, "branch-a task must exist");

        String snapshot = taskSubjectVersion(taskId);
        assertEquals("v7", snapshot, "branch task row must record the parent instance's subject version at creation");
        ctx.completeNow();
    }

    @Test
    @DisplayName("branch task creation fail-fasts when requireVersionStability=true and parent has no subject ref")
    void branchTaskCreationFailsFastWithoutSubjectRef(VertxTestContext ctx) {
        try {
            startWithSubjectVersion("no-ref", null);
            ctx.failNow("expected WorkflowSubjectVersionUnavailableException, got success");
            return;
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            assertTrue(
                    cause instanceof WorkflowSubjectVersionUnavailableException,
                    "expected WorkflowSubjectVersionUnavailableException, got: " + cause);
            ctx.completeNow();
        }
    }

    @Test
    @DisplayName("branch task completion succeeds when reviewedSubjectVersion matches the snapshot")
    void branchTaskCompletionMatchingVersionSucceeds(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWithSubjectVersion("match-1", "v3");
        UUID taskId = findBranchATask(workflowId);

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", null, "idem-match-1", new WorkflowActor.User("alice"), "v3");
        TaskMutationResult result = pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(TaskMutationResult.APPLIED, result, "matching reviewedSubjectVersion must complete the task");
        ctx.completeNow();
    }

    @Test
    @DisplayName(
            "branch task completion rejects a stale reviewedSubjectVersion with WorkflowStaleSubjectVersionException")
    void branchTaskCompletionStaleVersionFails(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWithSubjectVersion("stale-1", "v5");
        UUID taskId = findBranchATask(workflowId);

        TaskCompletionCommand cmd =
                new TaskCompletionCommand(taskId, "approve", null, "idem-stale-1", new WorkflowActor.User("bob"), "v4");
        try {
            pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            ctx.failNow("expected WorkflowStaleSubjectVersionException, got success");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            assertTrue(
                    cause instanceof WorkflowStaleSubjectVersionException,
                    "expected WorkflowStaleSubjectVersionException for stale version, got: " + cause);
            ctx.completeNow();
        }
    }

    @Test
    @DisplayName("branch task completion rejects null reviewedSubjectVersion with WorkflowStaleSubjectVersionException")
    void branchTaskCompletionNullVersionFails(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = startWithSubjectVersion("null-1", "v9");
        UUID taskId = findBranchATask(workflowId);

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", null, "idem-null-1", new WorkflowActor.User("carol"), null);
        try {
            pool.withTransaction(tx -> engine.taskCompleted(cmd, tx))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            ctx.failNow("expected WorkflowStaleSubjectVersionException, got success");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            assertTrue(
                    cause instanceof WorkflowStaleSubjectVersionException,
                    "expected WorkflowStaleSubjectVersionException for null version, got: " + cause);
            ctx.completeNow();
        }
    }
}
