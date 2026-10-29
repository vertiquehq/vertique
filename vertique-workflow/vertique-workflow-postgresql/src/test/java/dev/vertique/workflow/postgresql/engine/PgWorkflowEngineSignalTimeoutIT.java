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
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
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
 * Integration tests for the signal-wait-with-timeout execution path in {@link WorkflowEngineHandle}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>handleWaitSignal with a timeout branch persists wait_type=SIGNAL and wait_aux_id=timerId.
 *   </li>
 *   <li>Signal arrival on a SIGNAL+aux WAITING cancels the timer and appends TIMER_CANCELLED.</li>
 *   <li>Signal arrival on a plain SIGNAL WAITING (no timeout) does not touch workflow_timers.</li>
 *   <li>A due signal-timeout command runs the onTimeout mutator and advances to
 *       timeoutNextStepId.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineSignalTimeoutIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

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

    /** The timer that the command of the instance last started with a timeout recorded. */
    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    /** Starts an instance of the timeout workflow, waits until it is idle and reads the timeout it recorded. */
    private static Future<WorkflowInstanceId> startTimeoutWorkflow(StartCommand cmd) {
        return engineWithTimeout
                .start(cmd)
                .compose(started -> engineWithTimeout
                        .executionProbe()
                        .awaitIdle(started, IDLE_TIMEOUT)
                        .map(idle -> started))
                .compose(wfId -> TimerCommandRows.onlyTimerId(pool, wfId, TimerPurpose.SIGNAL_TIMEOUT)
                        .map(timerId -> {
                            lastTimerId.set(timerId);
                            return wfId;
                        }));
    }

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

    interface FakeTimeoutContract {
        @WorkflowSignal("ok-signal")
        Future<Void> okSignal(WorkflowInstanceId id, OkSignal payload, @SignalDedupKey String dedupKey);
    }

    interface FakeNoTimeoutContract {
        @WorkflowSignal("ok-signal")
        Future<Void> okSignal(WorkflowInstanceId id, OkSignal payload, @SignalDedupKey String dedupKey);
    }

    // --- Workflow definitions ---

    /** Signal-wait with timeout branch: start → wait("ok-signal", timeout=1h) → ok | cancelled. */
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
                    .timeoutAfter(Duration.ofHours(1))
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

        DefaultWorkflowRegistry timeoutRegistry = new DefaultWorkflowRegistry();
        timeoutRegistry.register(TIMEOUT_DEFINITION);
        engineWithTimeout = PgWorkflowEngineTestSupport.create(
                pool,
                timeoutRegistry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
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
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup,"
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
     * - record one timeout timer, linked to a pending TIMEOUT command
     * - persist wait_type=SIGNAL, wait_key="ok-signal", wait_aux_id=timerId
     * - append TIMER_SCHEDULED with kind=WAIT_TIMEOUT
     */
    @Test
    @DisplayName("handleWaitSignal with timeout branch persists wait_type=SIGNAL, wait_aux_id=timerId")
    void handleWaitSignalWithTimeoutPersistsAuxId(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-timeout-test",
                FakeTimeoutContract.class.getName(),
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startTimeoutWorkflow(cmd)
                .compose(wfId -> {
                    ctx.verify(() -> assertNotNull(lastTimerId.get(), "the start must record one timeout timer"));
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
                FakeTimeoutContract.class.getName(),
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startTimeoutWorkflow(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    String dedupKey = "signal-dedup-" + UUID.randomUUID();
                    return engineWithTimeout
                            .signal(wfId, "ok-signal", new OkSignal("test"), dedupKey)
                            .compose(v -> engineWithTimeout.executionProbe().awaitIdle(wfId, IDLE_TIMEOUT))
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
     * No timer is recorded for the wait.
     */
    @Test
    @DisplayName("signal on plain SIGNAL WAITING (no timeout) does not touch workflow_timers")
    void signalOnNoTimeoutWaitingDoesNotTouchTimers(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-no-timeout-test",
                FakeNoTimeoutContract.class.getName(),
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        engineWithoutTimeout
                .start(cmd)
                .compose(started -> engineWithoutTimeout
                        .executionProbe()
                        .awaitIdle(started, IDLE_TIMEOUT)
                        .map(idle -> started))
                .compose(wfId -> {
                    String dedupKey = "dedup-" + UUID.randomUUID();
                    return engineWithoutTimeout
                            .signal(wfId, "ok-signal", new OkSignal("plain"), dedupKey)
                            .compose(v -> engineWithoutTimeout.executionProbe().awaitIdle(wfId, IDLE_TIMEOUT))
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
     * A signal-timeout timer whose command comes due while the instance waits (wait_type=SIGNAL,
     * wait_aux_id=timerId) should:
     * - run the onTimeout mutator
     * - advance the workflow to the timeoutNextStepId
     * - append a TIMEOUT history entry
     * - complete the timer's command
     */
    @Test
    @DisplayName("a due signal-timeout command runs onTimeout mutator and advances to timeout step")
    void dueSignalTimeoutRunsTimeoutMutator(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "signal-timeout-test",
                FakeTimeoutContract.class.getName(),
                new OrderPayload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null);

        startTimeoutWorkflow(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    return TimerCommandRows.makeDue(pool, timerId)
                            .compose(due -> engineWithTimeout.executionProbe().awaitIdle(wfId, IDLE_TIMEOUT))
                            .compose(idle -> TimerCommandRows.commandState(pool, timerId))
                            .compose(commandState -> {
                                ctx.verify(() ->
                                        assertEquals("COMPLETE", commandState, "the timeout command must complete"));
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
