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
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Clock;
import java.time.Duration;
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
 * Integration tests for the standalone {@link dev.vertique.workflow.plan.TimerNode} execution path
 * in {@link WorkflowEngineHandle}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>handleTimerNode records a timer row and a {@code TIMER} command and transitions to WAITING with
 *       wait_type=TIMER.</li>
 *   <li>timerFired returns APPLIED for a slot match and advances the workflow to the next step; the timer's
 *       command then settles stale.</li>
 *   <li>timerFired returns STALE_NOOP for a non-WAITING instance.</li>
 *   <li>timerFired returns STALE_NOOP for a slot mismatch (wrong timer_id).</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineTimerNodeIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_timer_node_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    /** The timer that the command of the started instance recorded. */
    static final AtomicReference<UUID> recordedTimerId = new AtomicReference<>();

    /** Starts the instance, waits until it is idle and reads the timer its start recorded. */
    private static Future<WorkflowInstanceId> startAndAwaitRecorded(StartCommand cmd) {
        return engine.start(cmd)
                .compose(started ->
                        engine.executionProbe().awaitIdle(started, IDLE_TIMEOUT).map(idle -> started))
                .compose(id -> TimerCommandRows.onlyTimerId(pool, id, TimerPurpose.STANDALONE)
                        .map(timerId -> {
                            recordedTimerId.set(timerId);
                            return id;
                        }));
    }

    // --- Sample domain types ---

    /**
     * Start payload for the timer workflow.
     *
     * @param taskId the task identifier
     */
    record TimerTask(String taskId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "timer-task-" + taskId;
        }
    }

    /**
     * Workflow state for the timer workflow.
     *
     * @param taskId the task identifier
     * @param phase the current phase
     */
    record TimerState(String taskId, String phase) {
        static TimerState from(TimerTask t) {
            return new TimerState(t.taskId(), "started");
        }
    }

    /** Marker contract interface. */
    interface FakeTimerContract {}

    // --- Workflow definition ---

    /**
     * Timer workflow: start → timer(1h) → complete.
     *
     * <p>Plan: dispatch("start") → timer("wait", Duration.ofHours(1)) → complete("done"). The timer does not come due
     * during a test; tests make it due or fire it directly.
     * The initial step is the timer step itself.
     */
    static final WorkflowDefinition<TimerState, FakeTimerContract> TIMER_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FakeTimerContract> contract() {
            return FakeTimerContract.class;
        }

        @Override
        public Class<TimerState> stateType() {
            return TimerState.class;
        }

        @Override
        public String definitionId() {
            return "timer-node-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TimerState> wf) {
            wf.init(TimerTask.class, TimerState::from)
                    .initialStep("wait")
                    .timer("wait", Duration.ofHours(1))
                    .toStep("done")
                    .complete("done");
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

        PgDbExceptionMapper exMapper = new PgDbExceptionMapper();
        instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);
        historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);
        timerStore = new PgTimerStore(exMapper);

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(TIMER_DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup,"
                        + " workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    /**
     * Starting a workflow whose initial step is a TimerNode should:
     * - record one timer row, linked to a pending TIMER command
     * - leave the instance in WAITING status with wait_type=TIMER and wait_key=timerId
     * - append a TIMER_SCHEDULED history entry with kind=STANDALONE
     */
    @Test
    @DisplayName("handleTimerNode records a timer command and transitions to WAITING/TIMER")
    void handleTimerNodeTransitionsToWaiting(VertxTestContext ctx) {
        String taskId = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-node-test",
                FakeTimerContract.class.getName(),
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startAndAwaitRecorded(cmd)
                .compose(id -> {
                    // Verify the timer row is linked to a pending TIMER command
                    return TimerCommandRows.commandState(pool, recordedTimerId.get())
                            .compose(commandState -> {
                                ctx.verify(() -> assertEquals(
                                        "PENDING", commandState, "the timer's command must wait for its fire time"));
                                return pool.withTransaction(tx -> instanceRepo.findById(id, tx));
                            })
                            .compose(optInst -> {
                                ctx.verify(() -> {
                                    assertTrue(optInst.isPresent(), "instance must exist");
                                    var inst = optInst.get();
                                    assertEquals(WorkflowStatus.WAITING, inst.status(), "status must be WAITING");
                                    assertEquals(WaitType.TIMER, inst.waitType(), "wait_type must be TIMER");
                                    assertEquals(
                                            recordedTimerId.get().toString(),
                                            inst.waitKey(),
                                            "wait_key must be the timer_id as string");
                                    assertNull(inst.waitAuxId(), "wait_aux_id must be null for standalone timer");
                                });
                                // Verify TIMER_SCHEDULED history
                                return pool.withTransaction(tx -> historyRepo.listByInstance(id, tx))
                                        .map(entries -> {
                                            ctx.verify(() -> {
                                                boolean hasTimerScheduled = entries.stream()
                                                        .anyMatch(e ->
                                                                e.entryType() == WorkflowEntryType.TIMER_SCHEDULED);
                                                assertTrue(
                                                        hasTimerScheduled,
                                                        "history must contain TIMER_SCHEDULED entry");
                                                // Decode and verify kind=STANDALONE
                                                entries.stream()
                                                        .filter(e -> e.entryType() == WorkflowEntryType.TIMER_SCHEDULED)
                                                        .findFirst()
                                                        .ifPresent(e -> {
                                                            // Engine payload record is package-private;
                                                            // assert on the persisted JSON contract.
                                                            JsonObject p = new JsonObject(e.payloadJson());
                                                            assertEquals(
                                                                    "STANDALONE",
                                                                    p.getString("kind"),
                                                                    "timer kind must be STANDALONE");
                                                            assertEquals(
                                                                    recordedTimerId
                                                                            .get()
                                                                            .toString(),
                                                                    p.getString("timerId"),
                                                                    "timerId must match");
                                                            assertEquals(
                                                                    "wait",
                                                                    p.getString("stepId"),
                                                                    "stepId must be 'wait'");
                                                        });
                                            });
                                            return null;
                                        });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    /**
     * timerFired for a slot match (WAITING/TIMER and wait_key == timerId) should:
     * - return APPLIED
     * - advance the workflow to COMPLETED
     * - append a TIMER_FIRED history entry
     */
    @Test
    @DisplayName("timerFired returns APPLIED for slot match and advances workflow to COMPLETED")
    void timerFiredSlotMatchAdvancesWorkflow(VertxTestContext ctx) {
        String taskId = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-node-test",
                FakeTimerContract.class.getName(),
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startAndAwaitRecorded(cmd)
                .compose(id -> {
                    UUID timerId = recordedTimerId.get();
                    assertNotNull(timerId, "timerId must be set after start");
                    return pool.withTransaction(tx -> engine.timerFired(id, timerId, tx))
                            .compose(fired -> engine.executionProbe()
                                    .awaitIdle(id, IDLE_TIMEOUT)
                                    .map(idle -> fired))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(
                                        TimerFiringResult.APPLIED, result, "timerFired must return APPLIED"));
                                return pool.withTransaction(tx -> instanceRepo.findById(id, tx))
                                        .compose(optInst -> {
                                            ctx.verify(() -> {
                                                assertTrue(optInst.isPresent(), "instance must exist");
                                                var inst = optInst.get();
                                                assertEquals(
                                                        WorkflowStatus.COMPLETED,
                                                        inst.status(),
                                                        "status must be COMPLETED after timer fires");
                                            });
                                            return pool.withTransaction(tx -> historyRepo.listByInstance(id, tx))
                                                    .compose(entries -> {
                                                        ctx.verify(() -> {
                                                            boolean hasTimerFired = entries.stream()
                                                                    .anyMatch(e -> e.entryType()
                                                                            == WorkflowEntryType.TIMER_FIRED);
                                                            assertTrue(
                                                                    hasTimerFired,
                                                                    "history must contain TIMER_FIRED entry");
                                                        });
                                                        // The direct call left the timer's command behind: when it
                                                        // comes due, the wait is gone and the command settles stale.
                                                        return TimerCommandRows.makeDue(pool, timerId)
                                                                .compose(done -> engine.executionProbe()
                                                                        .awaitIdle(id, IDLE_TIMEOUT))
                                                                .compose(idle ->
                                                                        TimerCommandRows.commandState(pool, timerId))
                                                                .map(commandState -> {
                                                                    ctx.verify(() -> assertEquals(
                                                                            "STALE",
                                                                            commandState,
                                                                            "the command of a timer fired directly"
                                                                                    + " must settle stale"));
                                                                    return null;
                                                                });
                                                    });
                                        });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    /**
     * timerFired for a non-WAITING instance (e.g., CANCELLED) should return STALE_NOOP
     * without mutating state.
     */
    @Test
    @DisplayName("timerFired returns STALE_NOOP when instance status != WAITING")
    void timerFiredStaleSNOopForNonWaiting(VertxTestContext ctx) {
        String taskId = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-node-test",
                FakeTimerContract.class.getName(),
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startAndAwaitRecorded(cmd)
                .compose(id -> {
                    UUID timerId = recordedTimerId.get();
                    // Cancel the workflow first
                    return engine.cancel(id, "test cancel").compose(v -> {
                        return pool.withTransaction(tx -> engine.timerFired(id, timerId, tx))
                                .compose(fired -> engine.executionProbe()
                                        .awaitIdle(id, IDLE_TIMEOUT)
                                        .map(idle -> fired))
                                .map(result -> {
                                    ctx.verify(() -> assertEquals(
                                            TimerFiringResult.STALE_NOOP,
                                            result,
                                            "timerFired must return STALE_NOOP for cancelled instance"));
                                    return null;
                                });
                    });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    /**
     * timerFired with a different timerId (slot mismatch) should return STALE_NOOP
     * without mutating the workflow.
     */
    @Test
    @DisplayName("timerFired returns STALE_NOOP for slot mismatch (different timer_id)")
    void timerFiredStaleSNOopForSlotMismatch(VertxTestContext ctx) {
        String taskId = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-node-test",
                FakeTimerContract.class.getName(),
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startAndAwaitRecorded(cmd)
                .compose(id -> {
                    UUID wrongTimerId = UUID.randomUUID();
                    return pool.withTransaction(tx -> engine.timerFired(id, wrongTimerId, tx))
                            .compose(fired -> engine.executionProbe()
                                    .awaitIdle(id, IDLE_TIMEOUT)
                                    .map(idle -> fired))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(
                                        TimerFiringResult.STALE_NOOP,
                                        result,
                                        "timerFired must return STALE_NOOP for wrong timer_id"));
                                // Verify instance is still WAITING
                                return pool.withTransaction(tx -> instanceRepo.findById(id, tx))
                                        .map(optInst -> {
                                            ctx.verify(() -> {
                                                assertTrue(optInst.isPresent());
                                                assertEquals(
                                                        WorkflowStatus.WAITING,
                                                        optInst.get().status(),
                                                        "instance must still be WAITING");
                                            });
                                            return null;
                                        });
                            });
                })
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    // --- Private helper ---

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message + " (was: " + value + ")");
    }
}
