// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests verifying that the engine fails loudly — with a
 * {@link WorkflowDefinitionException} — when a required history payload is corrupted before
 * compensation matching runs.
 *
 * <p>The compensation matching algorithm ({@code collectCompletedCompensableStepIds}) reads
 * {@code SIGNAL_RECEIVED} and {@code SIDE_EFFECT_RECORDED} payloads as execution input. A
 * malformed payload indicates database corruption or a schema migration bug. Silently skipping it
 * would cause under-compensation (missing rollbacks); instead the engine must propagate a typed
 * error that the caller can detect and alert on.
 *
 * <p>Each test drives a minimal saga to a point where both required history entry types exist, then
 * corrupts one via a direct SQL UPDATE before triggering the FailNode path that initiates
 * compensation.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class MalformedHistoryFailLoudIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("malformed_history_fail_loud_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    /** Records all side-effect intents; always succeeds. */
    static final List<WorkflowSideEffectIntent> capturedIntents = Collections.synchronizedList(new ArrayList<>());

    // --- Domain types (shared by both test sagas) ---

    /** Signal 1 confirming the first forward dispatch step completed. */
    record SigOne(String data) {}

    /** Signal 2 confirming the second forward dispatch step completed. */
    record SigTwo(String data) {}

    // --- Saga definition used for SIGNAL_RECEIVED corruption ---
    // Two-step plan: dispatch-a → wait-sig1 → dispatch-b → wait-sig2 → fail.
    // After sig1 arrives, SIGNAL_RECEIVED for sig1 is written to history.
    // We corrupt that entry, then deliver sig2. During compensation matching, the engine
    // reads the corrupted SIGNAL_RECEIVED entry and must fail loudly.

    /** Start payload for the two-step (SIGNAL_RECEIVED corruption) saga. */
    record TwoStepStart(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "sig-corrupt-" + id;
        }
    }

    /** Workflow state for the two-step saga. */
    record TwoStepState(String id) {
        static TwoStepState init(TwoStepStart s) {
            return new TwoStepState(s.id());
        }

        TwoStepState withSig1(SigOne sig) {
            return this;
        }

        TwoStepState withSig2(SigTwo sig) {
            return this;
        }
    }

    /** Marker contract for the two-step saga. */
    interface TwoStepContract {}

    /**
     * Two-step compensable saga used for the SIGNAL_RECEIVED corruption test:
     *
     * <pre>
     *   start
     *     → dispatch-a (SERVICE "svc-a.run", compensable → comp-a)
     *     → wait-sig1 (signal "sig1")
     *     → dispatch-b (SERVICE "svc-b.run", compensable → comp-b)
     *     → wait-sig2 (signal "sig2")
     *     → fail-step (FailNode)
     * </pre>
     *
     * <p>After sig1 is delivered, a {@code SIGNAL_RECEIVED} entry for sig1 is in history. The test
     * corrupts that entry, then delivers sig2, which triggers FailNode → compensation.
     * Compensation reads the corrupted SIGNAL_RECEIVED entry and must throw
     * {@link WorkflowDefinitionException}.
     */
    static final WorkflowDefinition<TwoStepState, TwoStepContract> TWO_STEP_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<TwoStepContract> contract() {
            return TwoStepContract.class;
        }

        @Override
        public Class<TwoStepState> stateType() {
            return TwoStepState.class;
        }

        @Override
        public String definitionId() {
            return "malformed-sig-saga";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<TwoStepState> wf) {
            wf.init(TwoStepStart.class, TwoStepState::init)
                    .initialStep("dispatch-a")
                    .dispatchWithCompensation("dispatch-a", "svc-a.run", s -> "pa", "comp-a", "wait-sig1")
                    .waitFor("wait-sig1", "sig1", SigOne.class, TwoStepState::withSig1, "dispatch-b")
                    .dispatchWithCompensation("dispatch-b", "svc-b.run", s -> "pb", "comp-b", "wait-sig2")
                    .waitFor("wait-sig2", "sig2", SigTwo.class, TwoStepState::withSig2, "fail-step")
                    .fail("fail-step", "FORCED_FAILURE", s -> "forced failure — sig corruption test")
                    .compensate("comp-a", "dispatch-a", "svc-a.rollback", s -> "ra")
                    .compensate("comp-b", "dispatch-b", "svc-b.rollback", s -> "rb");
        }
    };

    // --- Saga definition used for SIDE_EFFECT_RECORDED corruption ---
    // One-step plan: dispatch-step → wait-done → fail.
    // After start, SIDE_EFFECT_RECORDED for dispatch-step is in history.
    // We corrupt that entry, then deliver the signal. During compensation matching the engine
    // reads the corrupted SIDE_EFFECT_RECORDED entry and must fail loudly.

    /** Start payload for the one-step (SIDE_EFFECT_RECORDED corruption) saga. */
    record OneStepStart(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "ser-corrupt-" + id;
        }
    }

    /** Workflow state for the one-step saga. */
    record OneStepState(String id) {
        static OneStepState init(OneStepStart s) {
            return new OneStepState(s.id());
        }

        OneStepState withDone(SigOne sig) {
            return this;
        }
    }

    /** Marker contract for the one-step saga. */
    interface OneStepContract {}

    /**
     * One-step compensable saga used for the SIDE_EFFECT_RECORDED corruption test:
     *
     * <pre>
     *   start
     *     → dispatch-step (SERVICE "svc.run", compensable → comp-step)
     *     → wait-done (signal "done")
     *     → fail-step (FailNode)
     * </pre>
     *
     * <p>After start, a {@code SIDE_EFFECT_RECORDED} entry for dispatch-step is in history. The
     * test corrupts that entry, then delivers the "done" signal, which triggers FailNode →
     * compensation. Compensation reads the corrupted SIDE_EFFECT_RECORDED entry and must throw
     * {@link WorkflowDefinitionException}.
     */
    static final WorkflowDefinition<OneStepState, OneStepContract> ONE_STEP_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<OneStepContract> contract() {
            return OneStepContract.class;
        }

        @Override
        public Class<OneStepState> stateType() {
            return OneStepState.class;
        }

        @Override
        public String definitionId() {
            return "malformed-ser-saga";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<OneStepState> wf) {
            wf.init(OneStepStart.class, OneStepState::init)
                    .initialStep("dispatch-step")
                    .dispatchWithCompensation("dispatch-step", "svc.run", s -> "p", "comp-step", "wait-done")
                    .waitFor("wait-done", "done", SigOne.class, OneStepState::withDone, "fail-step")
                    .fail("fail-step", "FORCED_FAILURE", s -> "forced failure — ser corruption test")
                    .compensate("comp-step", "dispatch-step", "svc.rollback", s -> "r");
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
        PgWorkflowInstanceRepository instanceRepo = new PgWorkflowInstanceRepository(pool, exMapper);
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);

        WorkflowSideEffectRecorder<SqlClient> stubRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.SERVICE;
            }

            @Override
            public Future<dev.vertique.workflow.sideeffect.RecorderResult> record(
                    WorkflowSideEffectIntent intent, SqlClient tx) {
                capturedIntents.add(intent);
                return Future.succeededFuture(dev.vertique.workflow.sideeffect.RecorderResult.empty());
            }
        };

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(TWO_STEP_DEF);
        registry.register(ONE_STEP_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(stubRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
        capturedIntents.clear();
        pool.query(
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
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
     * Verifies that compensation fails with {@link WorkflowDefinitionException} when a
     * {@code SIGNAL_RECEIVED} history entry has a payload that is missing the {@code signalName}
     * field required by the compensation-matching algorithm.
     *
     * <p>Test flow:
     * <ol>
     *   <li>Start the two-step saga → dispatch-a records → WAITING at wait-sig1.</li>
     *   <li>Deliver sig1 → SIGNAL_RECEIVED for sig1 written → dispatch-b records → WAITING at
     *       wait-sig2.</li>
     *   <li>Corrupt the SIGNAL_RECEIVED payload via SQL (drop {@code signalName}).</li>
     *   <li>Deliver sig2 → engine advances past wait-sig2 → fail-step → compensation reads
     *       corrupted SIGNAL_RECEIVED → must throw {@link WorkflowDefinitionException}.</li>
     * </ol>
     *
     * <p>Without fail-loud behavior the engine would silently skip the malformed entry and
     * produce zero compensations, leaving the saga under-compensated.
     */
    @Test
    @DisplayName("compensation fails loudly when SIGNAL_RECEIVED payload is malformed (missing signalName)")
    void compensationFailsLoudWhenSignalReceivedPayloadIsMalformed(VertxTestContext ctx) {
        TwoStepStart start = new TwoStepStart("run-1");
        StartCommand cmd = new StartCommand("malformed-sig-saga", start, start.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose((WorkflowInstanceId id) ->
                        // Deliver sig1: SIGNAL_RECEIVED written, dispatch-b fires, WAITING at wait-sig2.
                        engine.signal(id, "sig1", new SigOne("d"), "dedup-sig1-run1")
                                .map(v -> id))
                .compose((WorkflowInstanceId id) -> {
                    // Corrupt the SIGNAL_RECEIVED payload: replace with JSON missing "signalName".
                    String sql = "UPDATE workflow_history"
                            + " SET payload_json = '{\"not\":\"valid\",\"missing\":\"signalName\"}'::jsonb"
                            + " WHERE workflow_id = '" + id.value() + "'"
                            + " AND entry_type = 'SIGNAL_RECEIVED'";
                    return pool.query(sql).execute().map(v -> id);
                })
                .compose((WorkflowInstanceId id) ->
                        // Deliver sig2: triggers fail-step → compensation reads corrupted SIGNAL_RECEIVED.
                        engine.signal(id, "sig2", new SigTwo("d"), "dedup-sig2-run1")
                                .<Throwable>map(v -> null)
                                .recover(Future::succeededFuture))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "outer future must succeed (captures engine failure as value)");
                    Throwable cause = ar.result();
                    assertTrue(
                            cause != null,
                            "engine must have failed due to malformed SIGNAL_RECEIVED payload; "
                                    + "got success (under-compensation silently occurred)");
                    assertInstanceOf(
                            WorkflowDefinitionException.class,
                            cause,
                            "cause must be WorkflowDefinitionException, got: "
                                    + cause.getClass().getName() + ": " + cause.getMessage());
                    assertTrue(
                            cause.getMessage().contains("SIGNAL_RECEIVED"),
                            "error message must identify the entry type, got: " + cause.getMessage());
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that compensation fails with {@link WorkflowDefinitionException} when a
     * {@code SIDE_EFFECT_RECORDED} history entry has a payload whose {@code stepId} field has the
     * wrong JSON type (array instead of string), causing a Jackson decode failure.
     *
     * <p>Test flow:
     * <ol>
     *   <li>Start the one-step saga → dispatch-step records {@code SIDE_EFFECT_RECORDED} →
     *       WAITING at wait-done.</li>
     *   <li>Corrupt the SIDE_EFFECT_RECORDED payload via SQL ({@code stepId} → array value).</li>
     *   <li>Deliver the "done" signal → engine advances past wait-done → fail-step → compensation
     *       reads corrupted SIDE_EFFECT_RECORDED → must throw
     *       {@link WorkflowDefinitionException}.</li>
     * </ol>
     *
     * <p>Without fail-loud behavior the engine would silently skip the malformed entry and produce
     * zero compensations, leaving the saga under-compensated.
     */
    @Test
    @DisplayName("compensation fails loudly when SIDE_EFFECT_RECORDED payload is malformed (wrong stepId type)")
    void compensationFailsLoudWhenSideEffectRecordedPayloadIsMalformed(VertxTestContext ctx) {
        OneStepStart start = new OneStepStart("run-1");
        StartCommand cmd = new StartCommand("malformed-ser-saga", start, start.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose((WorkflowInstanceId id) -> {
                    // After start: dispatch-step fired, SIDE_EFFECT_RECORDED written, WAITING at wait-done.
                    // Corrupt the SIDE_EFFECT_RECORDED payload: stepId is an array (wrong type).
                    String sql = "UPDATE workflow_history"
                            + " SET payload_json = '{\"stepId\":[\"wrong\",\"type\"],\"targetId\":\"svc.run\"}'::jsonb"
                            + " WHERE workflow_id = '" + id.value() + "'"
                            + " AND entry_type = 'SIDE_EFFECT_RECORDED'";
                    return pool.query(sql).execute().map(v -> id);
                })
                .compose((WorkflowInstanceId id) ->
                        // Deliver "done" signal: triggers fail-step → compensation reads corrupted payload.
                        engine.signal(id, "done", new SigOne("d"), "dedup-done-run1")
                                .<Throwable>map(v -> null)
                                .recover(Future::succeededFuture))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "outer future must succeed (captures engine failure as value)");
                    Throwable cause = ar.result();
                    assertTrue(
                            cause != null,
                            "engine must have failed due to malformed SIDE_EFFECT_RECORDED payload; "
                                    + "got success (under-compensation silently occurred)");
                    assertInstanceOf(
                            WorkflowDefinitionException.class,
                            cause,
                            "cause must be WorkflowDefinitionException, got: "
                                    + cause.getClass().getName() + ": " + cause.getMessage());
                    assertTrue(
                            cause.getMessage().contains("SIDE_EFFECT_RECORDED"),
                            "error message must identify the entry type, got: " + cause.getMessage());
                    ctx.completeNow();
                }));
    }
}
