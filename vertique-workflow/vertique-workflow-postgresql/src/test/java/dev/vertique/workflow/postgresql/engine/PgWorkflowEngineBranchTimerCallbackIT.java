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
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.spi.TimerCommandSpec;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.PgWorkflowCommandRepository;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.JoinPolicyType;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.timer.TimerPurpose;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * Integration tests for the branch-aware routing of timer commands in {@link WorkflowEngineHandle}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Branch standalone timer fires only the owning branch; siblings untouched.</li>
 *   <li>Branch signal-timeout timer fires only the owning branch; siblings untouched.</li>
 *   <li>A timer command due on a SUPERSEDED branch records
 *       {@link WorkflowEntryType#BRANCH_LATE_CALLBACK_IGNORED} and settles stale.</li>
 * </ul>
 *
 * <p>Rows are inserted directly via the stores (bypassing the plan validator gate).
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchTimerCallbackIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_timer_callback_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgTimerStore timerStore;
    static PgBranchTokenRepository branchRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgJoinStateRepository joinStateRepo;
    static DefaultWorkflowRegistry registry;
    static PgWorkflowCommandRepository commandRepo;

    record StartTimer(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "branch-timer-cb-" + id;
        }
    }

    record State(String id) {}

    interface StandaloneTimerContract {
        @WorkflowSignal("other-signal")
        Future<Void> otherSignal(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    interface SignalTimeoutContract {
        @WorkflowSignal("my-signal")
        Future<Void> mySignal(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("other-signal")
        Future<Void> otherSignal(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
    }

    /**
     * Standalone-timer fork: branch-a waits on a TimerNode at "timer-step", branch-b waits on a
     * separate signal at "signal-step". Used by tests #1 and #3 to verify branch-A's timer fire does
     * not touch branch-B.
     */
    static final WorkflowDefinition<State, StandaloneTimerContract> STANDALONE_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<StandaloneTimerContract> contract() {
            return StandaloneTimerContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "branch-timer-standalone";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartTimer.class, p -> new State(p.id()))
                    .fork("fork-step")
                    .branch("branch-a", "timer-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "signal-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .timer("timer-step", java.time.Duration.ofHours(1))
                    .toStep("complete-a")
                    .complete("complete-a")
                    .waitForSignal("signal-step", "other-signal", Object.class)
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

    /**
     * Signal-with-timeout fork: branch-a waits on signal-with-timeout at "wait-signal-step",
     * branch-b waits on a separate signal at "signal-step". Used by test #2 to verify branch-A's
     * timeout fire advances only branch A.
     */
    static final WorkflowDefinition<State, SignalTimeoutContract> TIMEOUT_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<SignalTimeoutContract> contract() {
            return SignalTimeoutContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "branch-timer-signal-timeout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartTimer.class, p -> new State(p.id()))
                    .fork("fork-step")
                    .branch("branch-a", "wait-signal-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "signal-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .waitForSignal("wait-signal-step", "my-signal", Object.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-a")
                    .timeoutAfter(java.time.Duration.ofHours(1))
                    .onTimeout(s -> s)
                    .toStepOnTimeout("timeout-step")
                    .complete("complete-a")
                    .complete("timeout-step")
                    .waitForSignal("signal-step", "other-signal", Object.class)
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
        historyRepo = new PgWorkflowHistoryRepository(pool, ex);
        branchRepo = new PgBranchTokenRepository(pool, ex);
        joinStateRepo = new PgJoinStateRepository(pool, ex);
        timerStore = new PgTimerStore(ex);
        commandRepo = new PgWorkflowCommandRepository(pool, ex);

        // No-validator registry: branch-owned TimerNode/WaitSignalNode-with-timeout are accepted by
        // the production validator (gate lifted earlier in this PR), but skipping validation here
        // keeps the test setup focused.
        registry = new DefaultWorkflowRegistry();
        registry.register(STANDALONE_DEF);
        registry.register(TIMEOUT_DEF);

        // The executor runs every command of the database: the instances here are seeded, not started by the engine
        engine = PgWorkflowEngineTestSupport.executorEngine(pool, vertx, registry)
                .executorConfig(new WorkflowExecutorConfig(1, 8, 100, 60))
                .build();
        try {
            engine.executor().start().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The executor of the test engine did not start", e);
        }
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
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

    private WorkflowInstanceId seedInstance(WorkflowDefinition<?, ?> def) {
        UUID id = UUID.randomUUID();
        String planHash = registry.resolve(
                        new DefinitionKey(WorkflowScopes.DEFAULT, def.definitionId(), def.definitionVersion()))
                .plan()
                .planHash();
        // INSERT workflow_instances row whose plan_hash matches the registered definition's
        // computed hash — otherwise requirePlanHashMatches would fail-fast inside the engine.
        // Park the parent at current_step_id='join' with wait_key='fork-step', mirroring what
        // production handleForkNode does after the initial fork dispatch (PgWorkflowEngine.java
        // handleForkNode). The join-advancement fence at applyAllRequired requires
        // currentStepId == joinNode.stepId(), so seeding the wrong step would silently mask join
        // advancement behavior.
        pool.preparedQuery("INSERT INTO workflow_instances"
                        + " (id, definition_id, definition_version, plan_hash, version,"
                        + "  status, current_step_id, wait_type, wait_key, state_json)"
                        + " VALUES ($1, $2, $3, $4, 0, 'RUNNING', 'join',"
                        + "  'JOIN', 'fork-step', '{}'::jsonb)")
                .execute(Tuple.of(id, def.definitionId(), (long) def.definitionVersion(), planHash))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        WorkflowInstanceId workflowId = new WorkflowInstanceId(id);
        // Seed an OPEN join state so terminal-branch evaluations don't fail on a missing row.
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

    private BranchToken seedBranchWaitingOnTimer(WorkflowInstanceId workflowId, String branchId, UUID timerId) {
        Instant now = Instant.now();
        BranchToken token = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                branchId,
                "timer-step",
                BranchStatus.WAITING,
                WaitType.TIMER,
                timerId.toString(),
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

    private BranchToken seedBranchWaitingOnSignalWithTimeout(
            WorkflowInstanceId workflowId, String branchId, UUID timeoutTimerId) {
        Instant now = Instant.now();
        BranchToken token = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                branchId,
                "wait-signal-step",
                BranchStatus.WAITING,
                WaitType.SIGNAL,
                "my-signal",
                timeoutTimerId,
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

    private BranchToken seedSupersededBranch(WorkflowInstanceId workflowId, String branchId) {
        Instant now = Instant.now();
        BranchToken token = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                branchId,
                "timer-step",
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
                now,
                now,
                DurableMetadata.empty());
        pool.withTransaction(tx -> branchRepo.insert(token, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return token;
    }

    /**
     * Records a due timer of the branch, with its command, the way the engine does when a branch enters a timed wait.
     * The timer takes the given id, which the branch token already names as the wait it holds.
     */
    private void seedTimer(
            WorkflowInstanceId workflowId,
            String stepId,
            UUID timerId,
            UUID branchTokenId,
            String branchId,
            TimerPurpose purpose) {
        TimerCommandSpec spec = new TimerCommandSpec(
                workflowId,
                purpose,
                stepId,
                null,
                branchTokenId,
                "fork-step",
                branchId,
                new TimerCommandSpec.FireTime.After(Duration.ofHours(1)),
                List.of());
        pool.withTransaction(tx -> commandRepo.recordTimer(spec, tx).compose(recorded -> tx.preparedQuery(
                                "UPDATE workflow_commands SET timer_id = $2 WHERE id = $1")
                        .execute(Tuple.of(recorded.commandId(), timerId))
                        .compose(updated -> tx.preparedQuery(
                                        "UPDATE workflow_timers SET timer_id = $2 WHERE timer_id = $1")
                                .execute(Tuple.of(recorded.timerId(), timerId)))))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    /** Makes the timer due, waits until the executor ran its command, and answers the command's state. */
    private Future<String> fireTimer(WorkflowInstanceId workflowId, UUID timerId) {
        return TimerCommandRows.makeDue(pool, timerId)
                .compose(v -> engine.executionProbe().awaitIdle(workflowId, IDLE_TIMEOUT))
                .compose(idle -> TimerCommandRows.commandState(pool, timerId));
    }

    // Async helpers — return Future<...> instead of blocking with .join(). The tests compose
    // these AFTER the engine's main future and only run synchronous assertions inside
    // ctx.verify(). Calling .join() from inside an onComplete handler running on the Vert.x
    // event loop deadlocks the loop and surfaces as a bare 30s TimeoutException.
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

    private Future<Long> countHistoryAsync(WorkflowInstanceId workflowId, WorkflowEntryType entryType) {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_history WHERE workflow_id = $1 AND entry_type = $2")
                .execute(Tuple.of(workflowId.value(), entryType.name()))
                .map(rs -> rs.iterator().next().getLong(0));
    }

    // --- Tests ---

    @Test
    @DisplayName("branch standalone timer fires only the owning branch; sibling branch state untouched")
    void branchStandaloneTimerFiresOwningBranchOnly(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance(STANDALONE_DEF);

        UUID timerId = UUID.randomUUID();
        BranchToken branchA = seedBranchWaitingOnTimer(workflowId, "branch-a", timerId);
        BranchToken branchB = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                "branch-b",
                "signal-step",
                BranchStatus.WAITING,
                WaitType.SIGNAL,
                "other-signal",
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
        pool.withTransaction(tx -> branchRepo.insert(branchB, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        seedTimer(workflowId, "timer-step", timerId, branchA.id(), "branch-a", TimerPurpose.STANDALONE);

        // Compose the assertions onto the same Vert.x future chain — do NOT call .join() inside
        // ctx.verify(); blocking on a SQL future from the engine's completion callback deadlocks
        // the event loop and surfaces as a bare 30s TimeoutException.
        record Outcome(String command, BranchSnapshot branchA, String siblingStatus, long firedCount) {}

        fireTimer(workflowId, timerId)
                .compose(result -> branchSnapshotAsync(branchA.id()).compose(branchASnapshot -> branchStatusAsync(
                                branchB.id())
                        .compose(siblingStatus -> countHistoryAsync(workflowId, WorkflowEntryType.BRANCH_TIMER_FIRED)
                                .map(firedCount -> new Outcome(result, branchASnapshot, siblingStatus, firedCount)))))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals("COMPLETE", o.command(), "the standalone fire must complete its command");
                    assertEquals("COMPLETED", o.branchA().status(), "branch A must advance to COMPLETED");
                    assertEquals(
                            "complete-a",
                            o.branchA().currentStepId(),
                            "branch A must reach the CompleteNode step 'complete-a'");
                    assertEquals("WAITING", o.siblingStatus(), "sibling branch-b must stay WAITING");
                    assertTrue(o.firedCount() >= 1L, "BRANCH_TIMER_FIRED history must be appended");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("branch signal-timeout timer fires only the owning branch; siblings untouched")
    void branchSignalTimeoutFiresOwningBranchOnly(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance(TIMEOUT_DEF);

        UUID timeoutTimerId = UUID.randomUUID();
        BranchToken branchA = seedBranchWaitingOnSignalWithTimeout(workflowId, "branch-a", timeoutTimerId);
        BranchToken branchB = new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork-step",
                "branch-b",
                "signal-step",
                BranchStatus.WAITING,
                WaitType.SIGNAL,
                "other-signal",
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
        pool.withTransaction(tx -> branchRepo.insert(branchB, tx))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        seedTimer(
                workflowId, "wait-signal-step", timeoutTimerId, branchA.id(), "branch-a", TimerPurpose.SIGNAL_TIMEOUT);

        record Outcome(String command, BranchSnapshot branchA, String siblingStatus, long timeoutCount) {}

        fireTimer(workflowId, timeoutTimerId)
                .compose(result -> branchSnapshotAsync(branchA.id())
                        .compose(branchASnapshot -> branchStatusAsync(branchB.id())
                                .compose(siblingStatus -> countHistoryAsync(
                                                workflowId, WorkflowEntryType.BRANCH_TIMER_TIMEOUT_FIRED)
                                        .map(timeoutCount ->
                                                new Outcome(result, branchASnapshot, siblingStatus, timeoutCount)))))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals("COMPLETE", o.command(), "the signal-timeout fire must complete its command");
                    assertEquals("COMPLETED", o.branchA().status(), "branch A must advance to COMPLETED");
                    assertEquals(
                            "timeout-step",
                            o.branchA().currentStepId(),
                            "branch A must reach the timeout-route CompleteNode 'timeout-step'");
                    assertEquals("WAITING", o.siblingStatus(), "sibling branch-b must stay WAITING");
                    assertTrue(o.timeoutCount() >= 1L, "BRANCH_TIMER_TIMEOUT_FIRED history must be appended");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a timer command due on a SUPERSEDED branch records BRANCH_LATE_CALLBACK_IGNORED and settles stale")
    void lateTimerFireOnSupersededBranchIsNoOp(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = seedInstance(STANDALONE_DEF);

        UUID timerId = UUID.randomUUID();
        BranchToken superseded = seedSupersededBranch(workflowId, "branch-a");
        seedTimer(workflowId, "timer-step", timerId, superseded.id(), "branch-a", TimerPurpose.STANDALONE);

        record Outcome(String command, long ignoredCount) {}

        fireTimer(workflowId, timerId)
                .compose(result -> countHistoryAsync(workflowId, WorkflowEntryType.BRANCH_LATE_CALLBACK_IGNORED)
                        .map(ignoredCount -> new Outcome(result, ignoredCount)))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals("STALE", o.command(), "a late fire must settle its command stale");
                    assertTrue(o.ignoredCount() >= 1L, "BRANCH_LATE_CALLBACK_IGNORED must be recorded");
                    ctx.completeNow();
                })));
    }
}
