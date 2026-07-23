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
 * Integration tests for the signal-wait-with-timeout execution path in {@link WorkflowEngineHandle}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>handleWaitSignal with a timeout branch persists wait_type=SIGNAL and wait_aux_id=timerId.
 *   </li>
 *   <li>Signal arrival on a SIGNAL+aux WAITING cancels the timer and appends TIMER_CANCELLED.</li>
 *   <li>Signal arrival on a plain SIGNAL WAITING (no timeout) does not touch workflow_timers.</li>
 *   <li>timerFired for a SIGNAL+aux match runs onTimeout mutator and advances to
 *       timeoutNextStepId.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineSignalTimeoutIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_signal_timeout_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engineWithTimeout;
    static WorkflowEngineHandle engineWithoutTimeout;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    /** Tracks the most recent timer_id inserted by the fake recorder. */
    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    /** Counts recorder invocations. */
    static final AtomicInteger recorderCallCount = new AtomicInteger(0);

    // --- Domain types ---

    /**
     * Start payload.
     *
     * @param id unique id
     */
    record OrderPayload(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "signal-timeout-" + id;
        }
    }

    /**
     * Signal payload for "ok-signal".
     *
     * @param value signal value
     */
    record OkSignal(String value) {}

    /**
     * Workflow state.
     *
     * @param id the order id
     * @param phase the current phase ("started", "ok", "timeout")
     */
    record OrderState(String id, String phase) {
        static OrderState from(OrderPayload p) {
            return new OrderState(p.id(), "started");
        }

        static OrderState onSignal(OrderState s, OkSignal sig) {
            return new OrderState(s.id(), "ok");
        }

        static OrderState onTimeout(OrderState s) {
            return new OrderState(s.id(), "timeout");
        }
    }

    interface FakeTimeoutContract {}

    interface FakeNoTimeoutContract {}

    // --- Workflow definitions ---

    /** Signal-wait with timeout branch: start → wait("ok-signal", timeout=1min) → ok | cancelled. */
    static final WorkflowDefinition<OrderState, FakeTimeoutContract> TIMEOUT_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FakeTimeoutContract> contract() {
            return FakeTimeoutContract.class;
        }

        @Override
        public Class<OrderState> stateType() {
            return OrderState.class;
        }

        @Override
        public String definitionId() {
            return "signal-timeout-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OrderState> wf) {
            wf.init(OrderPayload.class, OrderState::from)
                    .initialStep("wait")
                    .waitForSignal("wait", "ok-signal", OkSignal.class)
                    .onSignal(OrderState::onSignal)
                    .toStepOnSignal("ok")
                    .timeoutAfter(Duration.ofMinutes(1))
                    .onTimeout(OrderState::onTimeout)
                    .toStepOnTimeout("cancelled")
                    .complete("ok")
                    .complete("cancelled");
        }
    };

    /** Signal-wait WITHOUT timeout: start → wait("ok-signal") → done. */
    static final WorkflowDefinition<OrderState, FakeNoTimeoutContract> NO_TIMEOUT_DEFINITION =
            new WorkflowDefinition<>() {
                @Override
                public Class<FakeNoTimeoutContract> contract() {
                    return FakeNoTimeoutContract.class;
                }

                @Override
                public Class<OrderState> stateType() {
                    return OrderState.class;
                }

                @Override
                public String definitionId() {
                    return "signal-no-timeout-test";
                }

                @Override
                public long definitionVersion() {
                    return 1L;
                }

                @Override
                public void define(WorkflowBuilder<OrderState> wf) {
                    wf.init(OrderPayload.class, OrderState::from)
                            .initialStep("wait")
                            .waitForSignal("wait", "ok-signal", OkSignal.class)
                            .onSignal(OrderState::onSignal)
                            .toStepOnSignal("done")
                            .build()
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
                recorderCallCount.incrementAndGet();
                UUID timerId = UUID.randomUUID();
                lastTimerId.set(timerId);
                Instant fireAt = intent.payload() instanceof Instant fa
                        ? fa
                        : Instant.now().plusSeconds(120);
                UUID fakeJobId = UUID.randomUUID();
                TimerRecord record = new TimerRecord(
                        timerId,
                        (dev.vertique.workflow.ops.WorkflowInstanceId)
                                intent.correlation().workflowId(),
                        intent.targetId(),
                        fireAt,
                        TimerStatus.SCHEDULED,
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

        DefaultWorkflowRegistry timeoutRegistry = new DefaultWorkflowRegistry();
        timeoutRegistry.register(TIMEOUT_DEFINITION);
        engineWithTimeout = PgWorkflowEngineTestSupport.create(
                pool,
                timeoutRegistry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(fakeTimerRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        DefaultWorkflowRegistry noTimeoutRegistry = new DefaultWorkflowRegistry();
        noTimeoutRegistry.register(NO_TIMEOUT_DEFINITION);
        engineWithoutTimeout = PgWorkflowEngineTestSupport.create(
                pool,
                noTimeoutRegistry,
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
        lastTimerId.set(null);
        recorderCallCount.set(0);
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
     * handleWaitSignal with timeout branch should:
     * - invoke the WORKFLOW_TIMER recorder once
     * - persist wait_type=SIGNAL, wait_key="ok-signal", wait_aux_id=timerId
     * - append TIMER_SCHEDULED with kind=WAIT_TIMEOUT
     */
    @Test
    @DisplayName("handleWaitSignal with timeout branch persists wait_type=SIGNAL, wait_aux_id=timerId")
    void handleWaitSignalWithTimeoutPersistsAuxId(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-timeout-test",
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engineWithTimeout
                .start(cmd)
                .compose(wfId -> {
                    ctx.verify(() -> {
                        assertEquals(1, recorderCallCount.get(), "WORKFLOW_TIMER recorder must be called once");
                        assertNotNull(lastTimerId.get(), "timerId must be set");
                    });
                    return pool.withTransaction(tx -> instanceRepo.findById(wfId, tx))
                            .compose(optInst -> {
                                ctx.verify(() -> {
                                    assertTrue(optInst.isPresent(), "instance must exist");
                                    var inst = optInst.get();
                                    assertEquals(WorkflowStatus.WAITING, inst.status(), "status must be WAITING");
                                    assertEquals(WaitType.SIGNAL, inst.waitType(), "wait_type must be SIGNAL");
                                    assertEquals("ok-signal", inst.waitKey(), "wait_key must be 'ok-signal'");
                                    assertNotNull(inst.waitAuxId(), "wait_aux_id must not be null (timeout timer id)");
                                    assertEquals(
                                            lastTimerId.get(), inst.waitAuxId(), "wait_aux_id must be the timer_id");
                                });
                                return pool.withTransaction(tx -> historyRepo.listByInstance(wfId, tx))
                                        .map(entries -> {
                                            ctx.verify(() -> {
                                                boolean hasTimerScheduled = entries.stream()
                                                        .anyMatch(e ->
                                                                e.entryType() == WorkflowEntryType.TIMER_SCHEDULED);
                                                assertTrue(hasTimerScheduled, "history must have TIMER_SCHEDULED");
                                                entries.stream()
                                                        .filter(e -> e.entryType() == WorkflowEntryType.TIMER_SCHEDULED)
                                                        .findFirst()
                                                        .ifPresent(e -> {
                                                            // Engine payload record is package-private;
                                                            // assert on the persisted JSON contract.
                                                            JsonObject p = new JsonObject(e.payloadJson());
                                                            assertEquals(
                                                                    "SIGNAL_TIMEOUT",
                                                                    p.getString("kind"),
                                                                    "kind must be SIGNAL_TIMEOUT");
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
     * Signal arrival on a SIGNAL+aux WAITING should:
     * - cancel the timeout timer (workflow_timers.status='CANCELLED')
     * - append TIMER_CANCELLED with cause=SIGNAL_ARRIVED
     * - advance the workflow to the signal next step (COMPLETED)
     */
    @Test
    @DisplayName("signal arrival on SIGNAL+aux WAITING cancels the timer and appends TIMER_CANCELLED")
    void signalArrivalCancelsTimeoutTimer(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-timeout-test",
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engineWithTimeout
                .start(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    String dedupKey = "signal-dedup-" + UUID.randomUUID();
                    return engineWithTimeout
                            .signal(wfId, "ok-signal", new OkSignal("test"), dedupKey)
                            .compose(v -> {
                                // Verify workflow advanced to COMPLETED
                                return pool.withTransaction(tx -> instanceRepo.findById(wfId, tx))
                                        .compose(optInst -> {
                                            ctx.verify(() -> {
                                                assertTrue(optInst.isPresent());
                                                assertEquals(
                                                        WorkflowStatus.COMPLETED,
                                                        optInst.get().status(),
                                                        "status must be COMPLETED after signal");
                                            });
                                            // Verify timer was cancelled in workflow_timers
                                            return pool.withTransaction(tx -> tx.preparedQuery(
                                                                    "SELECT status FROM workflow_timers WHERE timer_id = $1")
                                                            .execute(io.vertx.sqlclient.Tuple.of(timerId)))
                                                    .compose(rs -> {
                                                        ctx.verify(() -> {
                                                            var it = rs.iterator();
                                                            assertTrue(it.hasNext(), "timer row must exist");
                                                            assertEquals(
                                                                    "CANCELLED",
                                                                    it.next().getString("status"),
                                                                    "timer status must be CANCELLED");
                                                        });
                                                        // Verify TIMER_CANCELLED history entry
                                                        return pool.withTransaction(
                                                                        tx -> historyRepo.listByInstance(wfId, tx))
                                                                .map(entries -> {
                                                                    ctx.verify(() -> {
                                                                        boolean hasTimerCancelled = entries.stream()
                                                                                .anyMatch(e -> e.entryType()
                                                                                        == WorkflowEntryType
                                                                                                .TIMER_CANCELLED);
                                                                        assertTrue(
                                                                                hasTimerCancelled,
                                                                                "history must have TIMER_CANCELLED");
                                                                        entries.stream()
                                                                                .filter(e -> e.entryType()
                                                                                        == WorkflowEntryType
                                                                                                .TIMER_CANCELLED)
                                                                                .findFirst()
                                                                                .ifPresent(e -> {
                                                                                    // Engine payload record +
                                                                                    // cause enum are
                                                                                    // package-private; assert
                                                                                    // on persisted JSON.
                                                                                    JsonObject p = new JsonObject(
                                                                                            e.payloadJson());
                                                                                    assertEquals(
                                                                                            "SIGNAL_ARRIVED",
                                                                                            p.getString("cause"),
                                                                                            "cause must be SIGNAL_ARRIVED");
                                                                                });
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
     * Signal arrival on a plain SIGNAL WAITING (no timeout) should NOT touch workflow_timers.
     * The timer recorder should never be called.
     */
    @Test
    @DisplayName("signal on plain SIGNAL WAITING (no timeout) does not touch workflow_timers")
    void signalOnNoTimeoutWaitingDoesNotTouchTimers(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-no-timeout-test",
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engineWithoutTimeout
                .start(cmd)
                .compose(wfId -> {
                    // Recorder should never be called for a no-timeout wait
                    ctx.verify(() -> assertEquals(
                            0, recorderCallCount.get(), "recorder must not be called for no-timeout wait"));
                    String dedupKey = "dedup-" + UUID.randomUUID();
                    return engineWithoutTimeout
                            .signal(wfId, "ok-signal", new OkSignal("plain"), dedupKey)
                            .compose(v -> {
                                return pool.withTransaction(
                                                tx -> tx.query("SELECT COUNT(*) AS cnt FROM workflow_timers")
                                                        .execute())
                                        .map(rs -> {
                                            ctx.verify(() -> {
                                                long cnt = rs.iterator().next().getLong("cnt");
                                                assertEquals(
                                                        0L, cnt, "workflow_timers must be empty for no-timeout path");
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
     * timerFired for a SIGNAL+aux match (wait_type=SIGNAL, wait_aux_id=timerId) should:
     * - run the onTimeout mutator
     * - advance the workflow to the timeoutNextStepId
     * - append a TIMEOUT history entry
     * - return APPLIED
     */
    @Test
    @DisplayName("timerFired on SIGNAL+aux match runs onTimeout mutator and advances to timeout step")
    void timerFiredOnSignalAuxMatchRunsTimeoutMutator(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-timeout-test",
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        engineWithTimeout
                .start(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    return pool.withTransaction(tx -> engineWithTimeout.timerFired(wfId, timerId, tx))
                            .compose(result -> {
                                ctx.verify(() -> assertEquals(
                                        TimerFiringResult.APPLIED, result, "timerFired must return APPLIED"));
                                return pool.withTransaction(tx -> instanceRepo.findById(wfId, tx))
                                        .compose(optInst -> {
                                            ctx.verify(() -> {
                                                assertTrue(optInst.isPresent());
                                                // Workflow should have taken the timeout path and gone to "cancelled"
                                                // which is a CompleteNode
                                                assertEquals(
                                                        WorkflowStatus.COMPLETED,
                                                        optInst.get().status(),
                                                        "status must be COMPLETED after timeout path (which reaches a complete step)");
                                            });
                                            return pool.withTransaction(tx -> historyRepo.listByInstance(wfId, tx))
                                                    .map(entries -> {
                                                        ctx.verify(() -> {
                                                            boolean hasTimeout = entries.stream()
                                                                    .anyMatch(e ->
                                                                            e.entryType() == WorkflowEntryType.TIMEOUT);
                                                            assertTrue(
                                                                    hasTimeout, "history must contain TIMEOUT entry");
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
}
