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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for timer cancellation during workflow cancellation.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>cancel() on a TIMER WAITING marks the timer CANCELLED with cause=WORKFLOW_CANCELLED.</li>
 *   <li>cancel() on a SIGNAL+aux WAITING marks the timeout timer CANCELLED with
 *       cause=WORKFLOW_CANCELLED.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineCancelTimerIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_cancel_timer_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle timerEngine;
    static WorkflowEngineHandle signalTimeoutEngine;
    static PgWorkflowInstanceRepository instanceRepo;
    static PgWorkflowHistoryRepository historyRepo;
    static PgTimerStore timerStore;

    static final AtomicReference<UUID> lastTimerId = new AtomicReference<>();

    // --- Domain types ---

    /**
     * Minimal workflow payload.
     *
     * @param id identifier
     */
    record Payload(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "cancel-timer-" + id;
        }
    }

    /**
     * Signal payload.
     *
     * @param v value
     */
    record Signal(String v) {}

    /**
     * Simple state.
     *
     * @param id identifier
     * @param phase current phase
     */
    record State(String id, String phase) {
        static State from(Payload p) {
            return new State(p.id(), "started");
        }

        static State onSignal(State s, Signal sig) {
            return new State(s.id(), "ok");
        }
    }

    interface StandaloneTimerContract {}

    interface SignalTimeoutContract {}

    static final WorkflowDefinition<State, StandaloneTimerContract> STANDALONE_TIMER_DEF = new WorkflowDefinition<>() {
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
            return "cancel-standalone-timer-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Payload.class, State::from)
                    .initialStep("wait")
                    .timer("wait", Duration.ofHours(1))
                    .toStep("done")
                    .complete("done");
        }
    };

    static final WorkflowDefinition<State, SignalTimeoutContract> SIGNAL_TIMEOUT_DEF = new WorkflowDefinition<>() {
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
            return "cancel-signal-timeout-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Payload.class, State::from)
                    .initialStep("wait")
                    .waitForSignal("wait", "ok", Signal.class)
                    .onSignal(State::onSignal)
                    .toStepOnSignal("done")
                    .timeoutAfter(Duration.ofMinutes(30))
                    .toStepOnTimeout("cancelled")
                    .complete("done")
                    .complete("cancelled");
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
                Instant fireAt = intent.payload() instanceof Instant fa
                        ? fa
                        : Instant.now().plusSeconds(3600);
                TimerRecord record = new TimerRecord(
                        timerId,
                        (dev.vertique.workflow.ops.WorkflowInstanceId)
                                intent.correlation().workflowId(),
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

        DefaultWorkflowRegistry timerRegistry = new DefaultWorkflowRegistry();
        timerRegistry.register(STANDALONE_TIMER_DEF);
        timerEngine = PgWorkflowEngineTestSupport.create(
                pool,
                timerRegistry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(fakeTimerRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        DefaultWorkflowRegistry signalRegistry = new DefaultWorkflowRegistry();
        signalRegistry.register(SIGNAL_TIMEOUT_DEF);
        signalTimeoutEngine = PgWorkflowEngineTestSupport.create(
                pool,
                signalRegistry,
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
     * cancel() on a TIMER WAITING instance should mark the timer CANCELLED with
     * cause=WORKFLOW_CANCELLED and append TIMER_CANCELLED history.
     */
    @Test
    @DisplayName("cancel() on TIMER WAITING marks timer CANCELLED with cause=WORKFLOW_CANCELLED")
    void cancelOnTimerWaitingMarksTimerCancelled(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "cancel-standalone-timer-test",
                new Payload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        timerEngine
                .start(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    return timerEngine.cancel(wfId, "test cancel").compose(v -> {
                        // Timer row must be CANCELLED
                        return pool.withTransaction(
                                        tx -> tx.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                                                .execute(io.vertx.sqlclient.Tuple.of(timerId)))
                                .compose(rs -> {
                                    ctx.verify(() -> {
                                        var it = rs.iterator();
                                        assertTrue(it.hasNext(), "timer row must exist");
                                        assertEquals(
                                                "CANCELLED", it.next().getString("status"), "timer must be CANCELLED");
                                    });
                                    // History must have TIMER_CANCELLED with cause=WORKFLOW_CANCELLED
                                    return pool.withTransaction(tx -> historyRepo.listByInstance(wfId, tx))
                                            .map(entries -> {
                                                ctx.verify(() -> {
                                                    boolean hasCancelled = entries.stream()
                                                            .anyMatch(e ->
                                                                    e.entryType() == WorkflowEntryType.TIMER_CANCELLED);
                                                    assertTrue(hasCancelled, "history must have TIMER_CANCELLED");
                                                    entries.stream()
                                                            .filter(e ->
                                                                    e.entryType() == WorkflowEntryType.TIMER_CANCELLED)
                                                            .findFirst()
                                                            .ifPresent(e -> {
                                                                // Engine payload record + cause enum are
                                                                // package-private; assert on persisted JSON.
                                                                JsonObject p = new JsonObject(e.payloadJson());
                                                                assertEquals(
                                                                        "WORKFLOW_CANCELLED",
                                                                        p.getString("cause"),
                                                                        "cause must be WORKFLOW_CANCELLED");
                                                            });
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
     * cancel() on a SIGNAL+aux WAITING instance should mark the timeout timer CANCELLED with
     * cause=WORKFLOW_CANCELLED.
     */
    @Test
    @DisplayName("cancel() on SIGNAL+aux WAITING marks the timeout timer CANCELLED")
    void cancelOnSignalWithTimeoutMarksTimerCancelled(VertxTestContext ctx) {
        String id = UUID.randomUUID().toString();
        StartCommand cmd = new StartCommand(
                "cancel-signal-timeout-test",
                new Payload(id),
                java.util.UUID.randomUUID().toString(),
                null,
                null);

        signalTimeoutEngine
                .start(cmd)
                .compose(wfId -> {
                    UUID timerId = lastTimerId.get();
                    assertNotNull(timerId, "timerId must be set");
                    return signalTimeoutEngine.cancel(wfId, "cancel with timer").compose(v -> {
                        return pool.withTransaction(
                                        tx -> tx.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                                                .execute(io.vertx.sqlclient.Tuple.of(timerId)))
                                .map(rs -> {
                                    ctx.verify(() -> {
                                        var it = rs.iterator();
                                        assertTrue(it.hasNext(), "timer row must exist");
                                        assertEquals(
                                                "CANCELLED",
                                                it.next().getString("status"),
                                                "timeout timer must be CANCELLED");
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
