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
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Clock;
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
 * Integration test verifying that {@link IntentKind#WORKFLOW_EVENT} is treated as an optional kind
 * by the {@link RecorderRouter}: when no {@code WORKFLOW_EVENT} recorder is registered (i.e. the
 * {@code vertique-workflow-events} module is absent), the engine silently drops event intents and
 * the workflow still completes successfully with no outbox rows written.
 *
 * <p>This proves the design invariant stated in cycle 4: generic lifecycle events are optional for
 * basic saga execution. Only {@link dev.vertique.workflow.plan.ReminderSpec reminder-enabled} plans
 * require the events module (enforced by
 * {@link dev.vertique.workflow.engine.WorkflowReminderComposeValidator}).
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowEventOptionalKindIT {

    // --- Testcontainers setup (workflow schema only — no outbox table needed) ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_event_optional_kind_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static PgWorkflowInstanceRepository instanceRepo;

    // --- Domain types ---

    /**
     * Minimal start payload.
     *
     * @param id the test identifier
     */
    record State(String id) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "optional-kind-it-" + id;
        }
    }

    /** Contract for an immediate-complete workflow. */
    interface CompleteContract {}

    // --- Workflow definition ---

    /**
     * A workflow with no reminders that completes immediately.
     * No due-date timer is set, so no WORKFLOW_TIMER recorder is needed.
     */
    static final WorkflowDefinition<State, CompleteContract> COMPLETE_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<CompleteContract> contract() {
            return CompleteContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "optional-kind-complete";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s).initialStep("done").complete("done");
        }
    };

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
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
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, exMapper);
        PgWorkflowDedupRepository dedupRepo = new PgWorkflowDedupRepository(pool, exMapper);
        PgTimerStore timerStore = new PgTimerStore(exMapper);

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(COMPLETE_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                // WORKFLOW_EVENT listed as optional — router silently drops it when absent
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                new PgTaskStore(pool, exMapper),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history,"
                        + " workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
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
     * Running a complete-immediately workflow without a WORKFLOW_EVENT recorder succeeds: the
     * instance transitions to COMPLETED and no outbox rows are written (the outbox table doesn't
     * even exist in this test schema — verifying the engine never touches it).
     *
     * <p>The engine emits WORKFLOW_EVENT intents at WORKFLOW_STARTED and WORKFLOW_COMPLETED sites,
     * but the {@link RecorderRouter} silently drops them because
     * {@link IntentKind#WORKFLOW_EVENT} is listed in {@code optionalKinds} and no recorder is
     * registered for it.
     */
    @Test
    @DisplayName("No WORKFLOW_EVENT recorder: workflow completes successfully; no outbox writes attempted")
    void workflowCompletesWithoutEventRecorder(VertxTestContext ctx) {
        State state = new State("opt-" + UUID.randomUUID());
        StartCommand cmd = new StartCommand("optional-kind-complete", state, state.idempotencyKey(), null, null);

        engine.start(cmd)
                .compose(workflowId -> pool.withTransaction(tx -> instanceRepo.findById(workflowId, tx))
                        .map(optInst -> {
                            ctx.verify(() -> {
                                assertTrue(optInst.isPresent(), "instance must exist after start");
                                assertEquals(
                                        WorkflowStatus.COMPLETED,
                                        optInst.get().status(),
                                        "instance must be COMPLETED even without event recorder");
                            });
                            return null;
                        }))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }

    /**
     * Multiple workflows complete successfully without a WORKFLOW_EVENT recorder, proving the
     * optional-drop is idempotent and not a one-shot state change.
     */
    @Test
    @DisplayName("Multiple workflows complete without event recorder: each reaches COMPLETED independently")
    void multipleWorkflowsCompleteWithoutEventRecorder(VertxTestContext ctx) {
        State stateA = new State("opt-a-" + UUID.randomUUID());
        State stateB = new State("opt-b-" + UUID.randomUUID());

        StartCommand cmdA = new StartCommand("optional-kind-complete", stateA, stateA.idempotencyKey(), null, null);
        StartCommand cmdB = new StartCommand("optional-kind-complete", stateB, stateB.idempotencyKey(), null, null);

        engine.start(cmdA)
                .compose(wfA -> engine.start(cmdB)
                        .compose(wfB -> pool.withTransaction(tx -> instanceRepo
                                .findById(wfA, tx)
                                .compose(optA -> instanceRepo.findById(wfB, tx).map(optB -> {
                                    ctx.verify(() -> {
                                        assertTrue(optA.isPresent() && optB.isPresent());
                                        assertEquals(
                                                WorkflowStatus.COMPLETED,
                                                optA.get().status(),
                                                "wfA must be COMPLETED");
                                        assertEquals(
                                                WorkflowStatus.COMPLETED,
                                                optB.get().status(),
                                                "wfB must be COMPLETED");
                                    });
                                    return null;
                                })))))
                .onComplete(ar -> {
                    if (ar.failed()) ctx.failNow(ar.cause());
                    else ctx.completeNow();
                });
    }
}
