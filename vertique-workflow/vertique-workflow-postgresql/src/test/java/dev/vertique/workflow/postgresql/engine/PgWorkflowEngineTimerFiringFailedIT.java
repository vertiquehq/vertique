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
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
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
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Duration;
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
 * Integration tests for the {@code timerFiringFailed} path in {@link WorkflowEngineHandle}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Slot match: marks timer FAILED, appends TIMER_FAILED, transitions workflow to FAILED,
 *       returns APPLIED.</li>
 *   <li>Missing instance: STALE_NOOP returned.</li>
 *   <li>Slot mismatch: marks other timer FAILED with TIMER_INCONSISTENCY, does NOT mutate the
 *       workflow instance, returns STALE_NOOP.</li>
 *   <li>Already-terminal timer: STALE_NOOP, no mutations.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineTimerFiringFailedIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_timer_failed_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();
    static final AtomicReference<WorkflowInstanceId> lastWorkflowId = new AtomicReference<>();

    // --- Domain types ---

    /**
     * Workflow start payload.
     *
     * @param id unique id
     */
    record StartPayload(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "timer-failed-" + id;
        }
    }

    /**
     * Workflow state.
     *
     * @param id identifier
     */
    record State(String id) {
        static State from(StartPayload p) {
            return new State(p.id());
        }
    }

    interface FakeContract {}

    static final WorkflowDefinition<State, FakeContract> TIMER_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<FakeContract> contract() {
            return FakeContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "timer-firing-failed-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartPayload.class, State::from)
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

        WorkflowSideEffectRecorder<SqlClient> fakeTimerRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_TIMER;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                UUID timerId = UUID.randomUUID();
                lastTimerId.set(timerId);
                WorkflowInstanceId wfId =
                        (WorkflowInstanceId) intent.correlation().workflowId();
                lastWorkflowId.set(wfId);
                Instant fireAt = Instant.now().plusSeconds(3600);
                TimerRecord record = new TimerRecord(
                        timerId,
                        wfId,
                        intent.targetId(),
                        fireAt,
                        TimerStatus.SCHEDULED,
                        UUID.randomUUID(),
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
        registry.register(TIMER_DEF);

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
        lastTimerId.set(null);
        lastWorkflowId.set(null);
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
     * timerFiringFailed for a slot match: should mark the timer FAILED, append TIMER_FAILED history,
     * transition the workflow to FAILED, and return APPLIED.
     */
    @Test
    @DisplayName(
            "timerFiringFailed slot match: marks timer FAILED, appends TIMER_FAILED, transitions to FAILED, returns APPLIED")
    void timerFiringFailedSlotMatch(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-firing-failed-test",
                new StartPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    return pool.withTransaction(tx -> engine.timerFiringFailed(
                                    wfId, timerId, "EXECUTOR_ERROR", "Job failed after max retries", tx))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(
                                        TimerFiringResult.APPLIED, result, "timerFiringFailed must return APPLIED"));
                                // Timer must be FAILED
                                return pool.withTransaction(tx -> tx.preparedQuery(
                                                        "SELECT status FROM workflow_timers WHERE timer_id = $1")
                                                .execute(Tuple.of(timerId)))
                                        .compose(rs -> {
                                            ctx.verify(() -> {
                                                var it = rs.iterator();
                                                assertTrue(it.hasNext(), "timer row must exist");
                                                assertEquals(
                                                        "FAILED",
                                                        it.next().getString("status"),
                                                        "timer must be FAILED");
                                            });
                                            // Workflow must be FAILED
                                            return pool.withTransaction(tx -> instanceRepo.findById(wfId, tx))
                                                    .compose(optInst -> {
                                                        ctx.verify(() -> {
                                                            assertTrue(optInst.isPresent());
                                                            assertEquals(
                                                                    WorkflowStatus.FAILED,
                                                                    optInst.get()
                                                                            .status(),
                                                                    "workflow must be FAILED");
                                                        });
                                                        // History must have TIMER_FAILED
                                                        return pool.withTransaction(
                                                                        tx -> historyRepo.listByInstance(wfId, tx))
                                                                .map(entries -> {
                                                                    ctx.verify(() -> {
                                                                        boolean hasTimerFailed = entries.stream()
                                                                                .anyMatch(e -> e.entryType()
                                                                                        == WorkflowEntryType
                                                                                                .TIMER_FAILED);
                                                                        assertTrue(
                                                                                hasTimerFailed,
                                                                                "history must contain TIMER_FAILED");
                                                                    });
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
     * timerFiringFailed when neither timer row nor workflow instance exists: returns STALE_NOOP.
     *
     * <p>Per the implementation, lockForFiring on a missing timer_id returns Optional.empty()
     * and the method short-circuits to STALE_NOOP without ever attempting to load the instance.
     * This is the canonical STALE-NOOP path the recovery verticle relies on.
     */
    @Test
    @DisplayName("timerFiringFailed for missing timer + missing instance returns STALE_NOOP")
    void timerFiringFailedMissingInstance(VertxTestContext ctx) {
        UUID timerId = UUID.randomUUID();
        WorkflowInstanceId fakeWfId = new WorkflowInstanceId(UUID.randomUUID());

        pool.withTransaction(tx -> engine.timerFiringFailed(fakeWfId, timerId, "EXEC_ERROR", "failed", tx))
                .map(result -> {
                    ctx.verify(() -> assertEquals(
                            TimerFiringResult.STALE_NOOP,
                            result,
                            "timerFiringFailed must return STALE_NOOP when the timer row is missing"));
                    return null;
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
     * timerFiringFailed for a slot mismatch (WAITING on different timer): should mark the
     * other timer FAILED with TIMER_INCONSISTENCY reason, NOT mutate the workflow instance,
     * and return STALE_NOOP.
     */
    @Test
    @DisplayName(
            "timerFiringFailed slot mismatch: marks other timer FAILED, does NOT mutate workflow, returns STALE_NOOP")
    void timerFiringFailedSlotMismatch(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-firing-failed-test",
                new StartPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(wfId -> {
                    // The instance is WAITING on lastTimerId. Insert a DIFFERENT timer.
                    UUID otherTimerId = UUID.randomUUID();
                    TimerRecord otherRecord = new TimerRecord(
                            otherTimerId,
                            wfId,
                            "fake-step",
                            Instant.now().plusSeconds(1800),
                            TimerStatus.SCHEDULED,
                            UUID.randomUUID(),
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
                    return pool.withTransaction(tx -> timerStore.insertScheduled(otherRecord, tx))
                            .compose(v -> pool.withTransaction(
                                    tx -> engine.timerFiringFailed(wfId, otherTimerId, "EXEC_ERROR", "failed", tx)))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(
                                        TimerFiringResult.STALE_NOOP,
                                        result,
                                        "timerFiringFailed must return STALE_NOOP for slot mismatch"));
                                // The other timer row must be FAILED
                                return pool.withTransaction(tx -> tx.preparedQuery(
                                                        "SELECT status, failure_reason FROM workflow_timers WHERE timer_id = $1")
                                                .execute(Tuple.of(otherTimerId)))
                                        .compose(rs -> {
                                            ctx.verify(() -> {
                                                var it = rs.iterator();
                                                assertTrue(it.hasNext(), "other timer row must exist");
                                                var row = it.next();
                                                assertEquals(
                                                        "FAILED",
                                                        row.getString("status"),
                                                        "other timer must be FAILED");
                                                assertNotNull(
                                                        row.getString("failure_reason"),
                                                        "failure_reason must not be null");
                                                assertTrue(
                                                        row.getString("failure_reason")
                                                                .contains("TIMER_INCONSISTENCY"),
                                                        "failure_reason must contain TIMER_INCONSISTENCY");
                                            });
                                            // Workflow instance must still be WAITING
                                            return pool.withTransaction(tx -> instanceRepo.findById(wfId, tx))
                                                    .map(optInst -> {
                                                        ctx.verify(() -> {
                                                            assertTrue(optInst.isPresent());
                                                            assertEquals(
                                                                    WorkflowStatus.WAITING,
                                                                    optInst.get()
                                                                            .status(),
                                                                    "workflow must still be WAITING after slot mismatch");
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
     * timerFiringFailed for an already-terminal timer (CANCELLED) should return STALE_NOOP
     * without mutating either row.
     */
    @Test
    @DisplayName("timerFiringFailed for already-terminal timer returns STALE_NOOP")
    void timerFiringFailedAlreadyTerminalTimer(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "timer-firing-failed-test",
                new StartPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engine.start(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    // Cancel the timer manually to put it in a terminal state
                    return pool.withTransaction(tx -> timerStore.markCancelled(timerId, Instant.now(), tx))
                            .compose(v -> pool.withTransaction(
                                    tx -> engine.timerFiringFailed(wfId, timerId, "EXEC_ERROR", "failed", tx)))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(
                                        TimerFiringResult.STALE_NOOP,
                                        result,
                                        "timerFiringFailed for terminal timer must return STALE_NOOP"));
                                // Timer must still be CANCELLED (not changed to FAILED)
                                return pool.withTransaction(tx -> tx.preparedQuery(
                                                        "SELECT status FROM workflow_timers WHERE timer_id = $1")
                                                .execute(Tuple.of(timerId)))
                                        .map(rs -> {
                                            ctx.verify(() -> {
                                                var it = rs.iterator();
                                                assertTrue(it.hasNext(), "timer row must exist");
                                                assertEquals(
                                                        "CANCELLED",
                                                        it.next().getString("status"),
                                                        "timer must remain CANCELLED");
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
}
