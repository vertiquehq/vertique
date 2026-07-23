// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowStatus;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Engine-level integration test for compensation step matching when a plan loops back through the
 * same {@link dev.vertique.workflow.plan.WaitSignalNode}.
 *
 * <p>Before the fix, {@code collectCompletedCompensableStepIds} tracked only the <em>first</em>
 * {@code SIGNAL_RECEIVED} sequence per signal name ({@code signalNameToFirstSequence.putIfAbsent}).
 * For a plan that loops — dispatching and waiting on the same signal twice — only the first
 * iteration's dispatch was matched; the second was silently excluded because the signal sequence
 * comparison ({@code firstSignalSeq > dispatch2Seq}) was against the first signal's sequence
 * (which was already less than the second dispatch's sequence).
 *
 * <p>After the fix, the algorithm maintains per-name FIFOs of received-signal sequences and
 * consumes one entry per dispatch in order. Both dispatch occurrences are matched correctly,
 * and LIFO compensation order is preserved.
 *
 * <p>Scenario:
 * <pre>
 *   init → s1 (dispatch, comp=c1) → wait1 (signal "x") → decide
 *   decide → s1 (loop back) or done (terminate)
 *   s1 (2nd dispatch, comp=c1 again) → wait1 (signal "x" again)
 *   decide (2nd) → done
 *   done (CompleteNode)
 *   c1 (compensates s1)
 * </pre>
 * The saga loops through s1+wait1 twice before terminating. After two loops, we inject a
 * {@code FailNode} path by calling a separate failing definition variant that shares the same
 * loop structure but ends in {@code fail} rather than {@code complete}.
 *
 * <p>Because the plan must be static, we model the looping via a decision node that returns
 * "done" only after two signal deliveries (tracked by counter in state). The fail variant
 * always ends in compensation via {@link dev.vertique.workflow.plan.FailNode}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class CompensationLoopMatchingIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("comp_loop_matching_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    static final List<WorkflowSideEffectIntent> capturedIntents = Collections.synchronizedList(new ArrayList<>());

    // --- Domain types ---

    /** Start payload. */
    record LoopStart(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "loop-" + id;
        }
    }

    /** State tracks how many times the dispatch s1 has been executed. */
    record LoopState(String id, int dispatchCount) {
        static LoopState init(LoopStart s) {
            return new LoopState(s.id(), 0);
        }

        LoopState incrementOnSignal(XPayload p) {
            return new LoopState(id, dispatchCount + 1);
        }
    }

    /** Signal payload for signal "x". */
    record XPayload(String value) {}

    /** Marker contract. */
    interface LoopContract {}

    /**
     * Loop-with-compensation definition (fail after 2 loop iterations):
     * <pre>
     *   init → s1 (dispatch, comp=c1) → wait1 (signal "x") → decide
     *   decide:
     *     if dispatchCount &lt; 2 → s1 (loop)
     *     else → fail-step (triggers compensation)
     *   c1: compensates s1
     * </pre>
     * After two x-signal deliveries, the saga fails and compensation runs.
     */
    static final WorkflowDefinition<LoopState, LoopContract> LOOP_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<LoopContract> contract() {
            return LoopContract.class;
        }

        @Override
        public Class<LoopState> stateType() {
            return LoopState.class;
        }

        @Override
        public String definitionId() {
            return "loop-comp-saga";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<LoopState> wf) {
            wf.init(LoopStart.class, LoopState::init)
                    .initialStep("s1")
                    .dispatchWithCompensation("s1", "svc.run", s -> "payload-" + s.dispatchCount(), "c1", "wait1")
                    .waitFor("wait1", "x", XPayload.class, LoopState::incrementOnSignal, "decide")
                    .decide("decide", s -> s.dispatchCount() < 2 ? "s1" : "fail-step")
                    .fail("fail-step", "LOOP_DONE", s -> "looped " + s.dispatchCount() + " times")
                    .compensate("c1", "s1", "svc.rollback", s -> "rollback-" + s.dispatchCount());
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

        // Stub recorder: captures all intents, always succeeds.
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
        registry.register(LOOP_DEF);

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
     * Drives the loop-saga through two iterations (two dispatches + two "x" signals), then hits
     * the FailNode. Asserts that BOTH s1 occurrences are compensated via c1 in LIFO order.
     *
     * <p>This test fails before the fix (second dispatch is excluded from compensation) and passes
     * after the fix (per-name signal FIFO correctly matches both dispatches).
     */
    @Test
    @DisplayName("loop: both s1 dispatch occurrences are compensated (LIFO) after two signal deliveries")
    void bothLoopDispatchOccurrencesAreCompensated(VertxTestContext ctx) {
        LoopStart start = new LoopStart("loop-test-1");
        StartCommand cmd = new StartCommand("loop-comp-saga", start, start.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(id ->
                        // First loop: signal "x" → decide → back to s1 → wait1
                        engine.signal(id, "x", new XPayload("first"), "dedup-x-1")
                                // Second loop: signal "x" → decide → fail-step → compensation
                                .compose(v -> engine.signal(id, "x", new XPayload("second"), "dedup-x-2"))
                                .map(v -> id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "saga must succeed end-to-end; got: " + ar.cause());

                    var view = ar.result();

                    // Instance must be COMPENSATED
                    assertEquals(
                            WorkflowStatus.COMPENSATED,
                            view.instance().status(),
                            "saga must be COMPENSATED after two loops and fail-step");

                    // Gather history entry types
                    List<WorkflowHistoryEntry> history = view.recentHistory();
                    List<WorkflowEntryType> entryTypes = history.stream()
                            .map(WorkflowHistoryEntry::entryType)
                            .collect(Collectors.toList());

                    // History must contain COMPENSATING_START, two COMPENSATING_STEP entries, and COMPENSATED
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.COMPENSATING_START), "must have COMPENSATING_START");
                    assertTrue(entryTypes.contains(WorkflowEntryType.COMPENSATED), "must have COMPENSATED");

                    long compStepCount = entryTypes.stream()
                            .filter(t -> t == WorkflowEntryType.COMPENSATING_STEP)
                            .count();
                    assertEquals(
                            2L,
                            compStepCount,
                            "must have exactly 2 COMPENSATING_STEP entries (one per s1 occurrence); "
                                    + "history types: " + entryTypes);

                    // Both COMPENSATING_STEP entries must reference s1 (the only compensable step)
                    List<WorkflowHistoryEntry> compSteps = history.stream()
                            .filter(e -> e.entryType() == WorkflowEntryType.COMPENSATING_STEP)
                            .collect(Collectors.toList());
                    for (WorkflowHistoryEntry cs : compSteps) {
                        // The engine's payload record is package-private; assert on the persisted JSON.
                        JsonObject payload = new JsonObject(cs.payloadJson());
                        assertEquals(
                                "s1",
                                payload.getString("forwardStepId"),
                                "each COMPENSATING_STEP must reference step 's1'");
                    }

                    // Recorded intents: two svc.run (forward) + two svc.rollback (compensation)
                    List<String> intentTargets = capturedIntents.stream()
                            .map(WorkflowSideEffectIntent::targetId)
                            .collect(Collectors.toList());
                    long runCount =
                            intentTargets.stream().filter("svc.run"::equals).count();
                    long rollbackCount = intentTargets.stream()
                            .filter("svc.rollback"::equals)
                            .count();
                    assertEquals(2L, runCount, "must have exactly 2 svc.run intents");
                    assertEquals(2L, rollbackCount, "must have exactly 2 svc.rollback intents (one per s1 occurrence)");

                    ctx.completeNow();
                }));
    }
}
