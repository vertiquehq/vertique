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
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Duration;
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
 * Exercises the {@link BranchTransitionEngine} creation handlers end-to-end via
 * {@link WorkflowEngineHandle#start} on a fork that uses all three branch-owned wait shapes:
 *
 * <ul>
 *   <li>branch-a — {@code HumanTaskNode} with {@code dueIn(...)} + recurring reminders →
 *       exercises {@code branchHandleHumanTaskNodeWithDueDate}, {@code scheduleBranchReminder
 *       Timers}, and the {@code TASK_DUE} / {@code TASK_REMINDER} side-effect recorder paths.</li>
 *   <li>branch-b — {@code TimerNode} → exercises {@code branchHandleTimerNode} and the
 *       {@code STANDALONE} timer side-effect recorder path.</li>
 *   <li>branch-c — {@code WaitSignalNode} with {@code timeoutAfter(...)} → exercises
 *       {@code branchHandleWaitSignalWithTimeout} and the {@code SIGNAL_TIMEOUT} timer
 *       side-effect recorder path.</li>
 * </ul>
 *
 * Also fires the branch-A reminder timer to exercise the
 * {@link WorkflowEngineHandle#taskReminderFired} → {@code doBranchTaskReminderFired} →
 * {@code scheduleBranchReminderTimer} re-schedule loop.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineBranchFanoutCreationIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_fanout_create_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    record StartFanout(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "branch-fanout-creation-" + id;
        }
    }

    record State(String id) {}

    interface FanoutCreationContract {}

    /**
     * Fork plan:
     * <pre>
     *   fork
     *   ├─ branch-a: task("review", due=1h, reminders every 30m) → complete-a / due-step
     *   ├─ branch-b: timer("wait-timer", after 2h)               → complete-b
     *   └─ branch-c: waitForSignal("wait-go", timeout 3h)        → complete-c / timeout-step
     *   join (all-required) → done
     * </pre>
     */
    static final WorkflowDefinition<State, FanoutCreationContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FanoutCreationContract> contract() {
            return FanoutCreationContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "branch-fanout-creation";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartFanout.class, p -> new State(p.id()))
                    .fork("fork-step")
                    .branch("branch-a", "review", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-b", "wait-timer", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("branch-c", "wait-go", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .task("review")
                    .assignToRole("reviewers")
                    .dueIn(Duration.ofHours(1))
                    .onDue(s -> s)
                    .toStepOnDue("due-step")
                    .reminderEvery(Duration.ofMinutes(30), 3)
                    .decision("approve", Void.class)
                    .onDecision((s, p) -> s)
                    .toStep("complete-a")
                    .build()
                    .complete("complete-a")
                    .complete("due-step")
                    .timer("wait-timer", Duration.ofHours(2))
                    .toStep("complete-b")
                    .complete("complete-b")
                    .waitForSignal("wait-go", "go-signal", Object.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-c")
                    .timeoutAfter(Duration.ofHours(3))
                    .onTimeout(s -> s)
                    .toStepOnTimeout("timeout-step")
                    .complete("complete-c")
                    .complete("timeout-step")
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
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        PgBranchTokenRepository branchRepo = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);
        PgTimerStore timerStore = new PgTimerStore(ex);
        PgTaskStore taskStore = new PgTaskStore(pool, ex);

        // No-op WORKFLOW_EVENT recorder. The branch-a human task declares reminders, so the engine's
        // WorkflowReminderComposeValidator (built by WorkflowEngineFactory against the real registry)
        // requires a WORKFLOW_EVENT recorder to be registered. This IT exercises the reminder
        // *re-schedule* loop (a WORKFLOW_TIMER) and branch fan-out, not event delivery, so the event
        // recorder is a no-op that satisfies the validator and lets taskReminderFired emit.
        dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder<io.vertx.sqlclient.SqlClient> eventRecorder =
                new dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder<>() {
                    @Override
                    public IntentKind kind() {
                        return IntentKind.WORKFLOW_EVENT;
                    }

                    @Override
                    public io.vertx.core.Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                            dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent intent,
                            io.vertx.sqlclient.SqlClient tx) {
                        return io.vertx.core.Future.succeededFuture(
                                dev.vertique.workflow.sideeffect.RecorderResult.empty());
                    }
                };

        // Real WORKFLOW_TIMER recorder — branchHandleTimerNode,
        // branchHandleHumanTaskNodeWithDueDate, branchHandleWaitSignalWithTimeout, and the
        // reminder scheduling all emit WORKFLOW_TIMER intents that must produce a persisted
        // workflow_timers row plus a RecorderResult.Timer.
        dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder<io.vertx.sqlclient.SqlClient> timerRecorder =
                new dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder<>() {
                    @Override
                    public IntentKind kind() {
                        return IntentKind.WORKFLOW_TIMER;
                    }

                    @Override
                    public io.vertx.core.Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                            dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent intent,
                            io.vertx.sqlclient.SqlClient tx) {
                        dev.vertique.workflow.timer.TimerIntentPayload payload =
                                (dev.vertique.workflow.timer.TimerIntentPayload) intent.payload();
                        UUID timerId = UUID.randomUUID();
                        dev.vertique.workflow.timer.TimerRecord row = new dev.vertique.workflow.timer.TimerRecord(
                                timerId,
                                intent.correlation().workflowId(),
                                intent.correlation().stepId(),
                                payload.fireAt(),
                                dev.vertique.workflow.timer.TimerStatus.SCHEDULED,
                                UUID.randomUUID(),
                                java.time.Instant.now(),
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
                        return timerStore
                                .insertScheduled(row, tx)
                                .map(v -> new dev.vertique.workflow.sideeffect.RecorderResult.Timer(timerId));
                    }
                };

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                historyRepo,
                dedup,
                Set.of(timerRecorder, eventRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                taskStore,
                Clock.systemUTC(),
                branchRepo,
                joins);
        ctx.completeNow();
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
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

    // --- Async assertion helpers (compose-only; no .join() inside ctx.verify) ---

    private io.vertx.core.Future<String> branchWaitStateAsync(WorkflowInstanceId workflowId, String branchId) {
        return pool.preparedQuery("SELECT wait_type, wait_key, current_step_id FROM workflow_branch_tokens"
                        + " WHERE workflow_id = $1 AND branch_id = $2")
                .execute(Tuple.of(workflowId.value(), branchId))
                .map(rs -> {
                    var it = rs.iterator();
                    if (!it.hasNext()) return null;
                    var row = it.next();
                    return row.getString("wait_type") + "/" + row.getString("wait_key") + "@"
                            + row.getString("current_step_id");
                });
    }

    private io.vertx.core.Future<Long> countTimersAsync(
            WorkflowInstanceId workflowId, dev.vertique.workflow.timer.TimerPurpose purpose, String branchId) {
        return pool.preparedQuery("SELECT COUNT(*) FROM workflow_timers WHERE workflow_id = $1 AND purpose = $2"
                        + " AND branch_id = $3 AND status = 'SCHEDULED'")
                .execute(Tuple.of(workflowId.value(), purpose.name(), branchId))
                .map(rs -> rs.iterator().next().getLong(0));
    }

    private io.vertx.core.Future<UUID> firstTimerIdAsync(
            WorkflowInstanceId workflowId, dev.vertique.workflow.timer.TimerPurpose purpose, String branchId) {
        return pool.preparedQuery("SELECT timer_id FROM workflow_timers WHERE workflow_id = $1 AND purpose = $2"
                        + " AND branch_id = $3 ORDER BY scheduled_at LIMIT 1")
                .execute(Tuple.of(workflowId.value(), purpose.name(), branchId))
                .map(rs -> {
                    var it = rs.iterator();
                    return it.hasNext() ? it.next().getUUID("timer_id") : null;
                });
    }

    // --- Tests ---

    @Test
    @DisplayName("engine.start drives the fork through three branch creation handlers and parks each branch correctly")
    void forkDispatchExercisesAllThreeBranchCreationHandlers(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("create-1");
        dev.vertique.workflow.ops.StartCommand cmd = new dev.vertique.workflow.ops.StartCommand(
                DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        record Outcome(
                String branchA,
                String branchB,
                String branchC,
                long dueTimers,
                long reminderTimers,
                long standaloneTimers,
                long timeoutTimers) {}

        engine.start(cmd)
                .compose(workflowId -> branchWaitStateAsync(workflowId, "branch-a")
                        .compose(a -> branchWaitStateAsync(workflowId, "branch-b")
                                .compose(b -> branchWaitStateAsync(workflowId, "branch-c")
                                        .compose(c -> countTimersAsync(
                                                        workflowId,
                                                        dev.vertique.workflow.timer.TimerPurpose.TASK_DUE,
                                                        "branch-a")
                                                .compose(due -> countTimersAsync(
                                                                workflowId,
                                                                dev.vertique.workflow.timer.TimerPurpose.TASK_REMINDER,
                                                                "branch-a")
                                                        .compose(rem -> countTimersAsync(
                                                                        workflowId,
                                                                        dev.vertique.workflow.timer.TimerPurpose
                                                                                .STANDALONE,
                                                                        "branch-b")
                                                                .compose(stand -> countTimersAsync(
                                                                                workflowId,
                                                                                dev.vertique.workflow.timer.TimerPurpose
                                                                                        .SIGNAL_TIMEOUT,
                                                                                "branch-c")
                                                                        .map(tmo -> new Outcome(
                                                                                a, b, c, due, rem, stand, tmo)))))))))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    // branch-a parked at HumanTaskNode wait (WAITING/TASK at step 'review').
                    assertNotNull(o.branchA(), "branch-a token must exist");
                    assertTrue(
                            o.branchA().startsWith("TASK/"), "branch-a must be WAITING on TASK, got: " + o.branchA());
                    assertTrue(o.branchA().endsWith("@review"), "branch-a must be at step 'review'");
                    // branch-b parked on TimerNode (WAITING/TIMER at step 'wait-timer').
                    assertNotNull(o.branchB(), "branch-b token must exist");
                    assertTrue(
                            o.branchB().startsWith("TIMER/"), "branch-b must be WAITING on TIMER, got: " + o.branchB());
                    assertTrue(o.branchB().endsWith("@wait-timer"), "branch-b must be at step 'wait-timer'");
                    // branch-c parked on WaitSignal-with-timeout (WAITING/SIGNAL at step 'wait-go').
                    assertNotNull(o.branchC(), "branch-c token must exist");
                    assertTrue(
                            o.branchC().startsWith("SIGNAL/go-signal"),
                            "branch-c must be WAITING on SIGNAL go-signal, got: " + o.branchC());
                    assertTrue(o.branchC().endsWith("@wait-go"), "branch-c must be at step 'wait-go'");

                    // Per-branch timer rows persisted by the recorder via each handler.
                    assertEquals(1L, o.dueTimers(), "branch-a must have a TASK_DUE timer");
                    assertTrue(
                            o.reminderTimers() >= 1L,
                            "branch-a must have at least the first TASK_REMINDER timer scheduled");
                    assertEquals(1L, o.standaloneTimers(), "branch-b must have a STANDALONE timer");
                    assertEquals(1L, o.timeoutTimers(), "branch-c must have a SIGNAL_TIMEOUT timer");
                    ctx.completeNow();
                })))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("branch reminder fire records BRANCH_TASK_REMINDER_FIRED + schedules next reminder")
    void branchReminderFireReschedulesNext(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("rem-1");
        dev.vertique.workflow.ops.StartCommand cmd = new dev.vertique.workflow.ops.StartCommand(
                DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        record Outcome(long firedCount, long reminderTimers) {}

        engine.start(cmd)
                .compose(workflowId -> firstTimerIdAsync(
                                workflowId, dev.vertique.workflow.timer.TimerPurpose.TASK_REMINDER, "branch-a")
                        .compose(reminderId -> {
                            assertNotNull(reminderId, "branch-a must have a reminder timer to fire");
                            // Look up the task id + scheduled fire time so we can call the
                            // executor-equivalent entry point. TASK_REMINDER timers route through
                            // taskReminderFired in WorkflowTimerFireExecutor, not the generic
                            // timerFired path.
                            return pool.preparedQuery(
                                            "SELECT task_id, fire_at FROM workflow_timers WHERE timer_id = $1")
                                    .execute(Tuple.of(reminderId))
                                    .compose(rs -> {
                                        var row = rs.iterator().next();
                                        UUID taskId = row.getUUID("task_id");
                                        java.time.Instant fireAt =
                                                row.getOffsetDateTime("fire_at").toInstant();
                                        return pool.withTransaction(tx -> engine.taskReminderFired(
                                                        workflowId, taskId, reminderId, fireAt, tx))
                                                .compose(v -> pool.preparedQuery("SELECT COUNT(*) FROM workflow_history"
                                                                + " WHERE workflow_id = $1 AND entry_type = $2")
                                                        .execute(Tuple.of(
                                                                workflowId.value(),
                                                                dev.vertique.workflow.state.WorkflowEntryType
                                                                        .BRANCH_TASK_REMINDER_FIRED
                                                                        .name()))
                                                        .map(r -> r.iterator()
                                                                .next()
                                                                .getLong(0)))
                                                .compose(firedCount -> countTimersAsync(
                                                                workflowId,
                                                                dev.vertique.workflow.timer.TimerPurpose.TASK_REMINDER,
                                                                "branch-a")
                                                        .map(remCount -> new Outcome(firedCount, remCount)));
                                    });
                        }))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(o.firedCount() >= 1L, "BRANCH_TASK_REMINDER_FIRED history must be appended");
                    // After firing the first reminder, the next recurring reminder is scheduled
                    // (RecurringInterval with maxFires=3 → after first fire, one new SCHEDULED row
                    // appears; the fired one is in FIRED state, not SCHEDULED).
                    assertTrue(
                            o.reminderTimers() >= 1L, "next recurring reminder must be scheduled after the first fire");
                    ctx.completeNow();
                })))
                .onFailure(ctx::failNow);
    }
}
