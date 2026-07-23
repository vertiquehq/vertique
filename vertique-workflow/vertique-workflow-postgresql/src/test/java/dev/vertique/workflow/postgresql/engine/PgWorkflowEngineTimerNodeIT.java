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
import dev.vertique.workflow.ops.TimerFiringResult;
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
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 *   <li>handleTimerNode emits a WORKFLOW_TIMER intent and transitions to WAITING with
 *       wait_type=TIMER.</li>
 *   <li>timerFired returns APPLIED for a slot match and advances the workflow to the next step.</li>
 *   <li>timerFired returns STALE_NOOP for a non-WAITING instance.</li>
 *   <li>timerFired returns STALE_NOOP for a slot mismatch (wrong timer_id).</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineTimerNodeIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_timer_node_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    /** Captures the last intent routed for kind=WORKFLOW_TIMER. */
    static final AtomicReference<WorkflowSideEffectIntent> capturedIntent = new AtomicReference<>();

    /** Counts how many times the recorder was called. */
    static final AtomicInteger recorderCallCount = new AtomicInteger(0);

    /** The timer id the fake recorder inserts and returns. */
    static final AtomicReference<UUID> insertedTimerId = new AtomicReference<>();

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
     * Timer workflow: start → timer(500ms) → complete.
     *
     * <p>Plan: dispatch("start") → timer("wait", Duration.ofMillis(500)) → complete("done").
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
                    .timer("wait", Duration.ofMillis(500))
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

        // Fake recorder for WORKFLOW_TIMER that captures the intent, inserts a timer row,
        // and returns RecorderResult.ofTimer(timerId).
        WorkflowSideEffectRecorder<SqlClient> fakeTimerRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                capturedIntent.set(intent);
                recorderCallCount.incrementAndGet();
                UUID timerId = UUID.randomUUID();
                insertedTimerId.set(timerId);
                Instant fireAt = intent.payload() instanceof Instant fa
                        ? fa
                        : Instant.now().plusSeconds(60);
                UUID fakeJobId = UUID.randomUUID();
                TimerRecord record = new TimerRecord(
                        timerId,
                        (dev.vertique.workflow.ops.WorkflowInstanceId)
                                intent.correlation().workflowId(),
                        intent.targetId(),
                        fireAt,
                        dev.vertique.workflow.timer.TimerStatus.SCHEDULED,
                        fakeJobId,
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        TimerPurpose.STANDALONE,
                        null,
                        null, // branchTokenId
                        null, // forkStepId
                        null,
                        DurableMetadata.empty()); // branchId
                return timerStore.insertScheduled(record, tx).map(v -> RecorderResult.ofTimer(timerId));
            }
        };

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(TIMER_DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(fakeTimerRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        capturedIntent.set(null);
        recorderCallCount.set(0);
        insertedTimerId.set(null);
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
     * - invoke the WORKFLOW_TIMER recorder exactly once
     * - leave the instance in WAITING status with wait_type=TIMER and wait_key=timerId
     * - append a TIMER_SCHEDULED history entry with kind=STANDALONE
     */
    @Test
    @DisplayName("handleTimerNode emits WORKFLOW_TIMER intent and transitions to WAITING/TIMER")
    void handleTimerNodeTransitionsToWaiting(VertxTestContext ctx) {
        String taskId = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-node-test",
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(id -> {
                    // Verify recorder was called exactly once
                    ctx.verify(() -> {
                        assertEquals(1, recorderCallCount.get(), "WORKFLOW_TIMER recorder should be called once");
                        assertNotNull(capturedIntent.get(), "intent must have been captured");
                        assertEquals(
                                IntentKind.WORKFLOW_TIMER,
                                capturedIntent.get().kind(),
                                "intent kind must be WORKFLOW_TIMER");
                        assertNotNull(insertedTimerId.get(), "timerId must have been set");
                    });
                    // Load and verify the instance
                    return pool.withTransaction(tx -> instanceRepo.findById(id, tx))
                            .compose(optInst -> {
                                ctx.verify(() -> {
                                    assertTrue(optInst.isPresent(), "instance must exist");
                                    var inst = optInst.get();
                                    assertEquals(WorkflowStatus.WAITING, inst.status(), "status must be WAITING");
                                    assertEquals(WaitType.TIMER, inst.waitType(), "wait_type must be TIMER");
                                    assertEquals(
                                            insertedTimerId.get().toString(),
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
                                                                    insertedTimerId
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
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(id -> {
                    UUID timerId = insertedTimerId.get();
                    assertNotNull(timerId, "timerId must be set after start");
                    return pool.withTransaction(tx -> engine.timerFired(id, timerId, tx))
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
                                                    .map(entries -> {
                                                        ctx.verify(() -> {
                                                            boolean hasTimerFired = entries.stream()
                                                                    .anyMatch(e -> e.entryType()
                                                                            == WorkflowEntryType.TIMER_FIRED);
                                                            assertTrue(
                                                                    hasTimerFired,
                                                                    "history must contain TIMER_FIRED entry");
                                                        });
                                                        return null;
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
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(id -> {
                    UUID timerId = insertedTimerId.get();
                    // Cancel the workflow first
                    return engine.cancel(id, "test cancel").compose(v -> {
                        return pool.withTransaction(tx -> engine.timerFired(id, timerId, tx))
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
                new TimerTask(taskId),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(id -> {
                    UUID wrongTimerId = UUID.randomUUID();
                    return pool.withTransaction(tx -> engine.timerFired(id, wrongTimerId, tx))
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
