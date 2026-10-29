// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
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
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
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
 * Integration tests verifying that attempting to resume a workflow instance whose pinned
 * definition version is no longer registered in the engine produces a typed
 * {@link WorkflowVersionPinUnavailableException} with the {@code instanceId} populated, and that
 * the transaction is rolled back leaving the instance state unchanged (FR-WF-DEF-062).
 *
 * <p>Engine A knows v1 and is used to create a WAITING instance. Engine B has an empty registry
 * (does NOT know v1) and is used to send a signal. Recording a signal needs no plan, so the call succeeds and the
 * signal waits as a recorded command; engine B applies nothing, and engine A runs it. When no engine on the node
 * knows the version, the executor defers the recorded signal and the instance stays as it was.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineMissingPinResumeIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    // --- Testcontainers ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_missing_pin_resume_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    /** Engine A knows the definition — used to start instances. */
    static WorkflowEngineHandle engineA;

    /** Engine B has an empty registry — used to attempt resume after unregistration. */
    static WorkflowEngineHandle engineB;

    // --- Types ---

    /**
     * Start payload.
     *
     * @param value arbitrary value
     */
    record MissingPinPayload(String value) {}

    /** Marker contract. */
    interface MissingPinContract {
        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    static final String DEF_ID = "missing-pin-test";

    static final WorkflowDefinition<MissingPinPayload, MissingPinContract> DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<MissingPinContract> contract() {
            return MissingPinContract.class;
        }

        @Override
        public Class<MissingPinPayload> stateType() {
            return MissingPinPayload.class;
        }

        @Override
        public String definitionId() {
            return DEF_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<MissingPinPayload> wf) {
            wf.init(MissingPinPayload.class, p -> p)
                    .initialStep("wait")
                    .waitForSignal("wait", "go", String.class)
                    .onSignal((s, p) -> s)
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

        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, ex);
        PgWorkflowHistoryRepository history = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);

        // Engine A: knows v1.
        DefaultWorkflowRegistry registryA = new DefaultWorkflowRegistry();
        registryA.register(DEF_V1);
        engineA = PgWorkflowEngineTestSupport.create(
                pool,
                registryA,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());

        // Engine B: empty registry — simulates a deployment where v1 was unregistered.
        DefaultWorkflowRegistry registryB = new DefaultWorkflowRegistry();
        engineB = PgWorkflowEngineTestSupport.create(
                pool,
                registryB,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
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
     * FR-WF-DEF-062: A signal sent through engine B, which lacks the pinned version, is recorded: recording needs no
     * plan. Engine B applies nothing and the instance row stays as it was; engine A, which has the version, runs the
     * recorded command and the instance completes.
     */
    @Test
    @DisplayName(
            "FR-WF-DEF-062 — signal via engine without pinned version is recorded, changes nothing, and the engine with the version applies it")
    void signalViaEngineWithoutPinnedVersionIsRecordedAndAppliedByTheEngineWithIt(VertxTestContext ctx) {
        String key = "missing-pin-signal-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(
                DEF_ID,
                MissingPinContract.class.getName(),
                new MissingPinPayload("test"),
                key,
                null,
                null,
                null,
                null,
                null);

        // Engine A starts the instance — ends up WAITING for "go".
        engineA.start(cmd)
                .compose(id ->
                        engineA.executionProbe().awaitIdle(id, IDLE_TIMEOUT).map(idle -> id))
                .compose(id -> engineA.query(id).map(view -> {
                    assertEquals(WorkflowStatus.WAITING, view.instance().status(), "must be WAITING before signal");
                    return id;
                }))
                .compose(id -> engineA.query(id).compose(view -> {
                    long capturedVersion = view.instance().version();
                    // Engine B records the signal even though it holds no plan for the pinned version.
                    return engineB.signal(id, "go", "payload", "dedup-" + UUID.randomUUID())
                            .compose(recorded -> pool.preparedQuery("SELECT count(*) AS c FROM workflow_commands"
                                            + " WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                                    .execute(Tuple.of(id.value()))
                                    .map(rows -> rows.iterator().next().getLong("c")))
                            .compose(commands -> {
                                assertEquals(1L, commands, "the signal must be recorded as one command");
                                // Engine A, which has the version, runs it.
                                return engineA.executionProbe().awaitIdle(id, IDLE_TIMEOUT);
                            })
                            .compose(idle -> engineA.query(id))
                            .map(afterView -> {
                                assertEquals(
                                        WorkflowStatus.COMPLETED,
                                        afterView.instance().status(),
                                        "the engine with the pinned version must apply the recorded signal");
                                assertTrue(
                                        afterView.instance().version() > capturedVersion,
                                        "the application must advance the instance version");
                                return afterView;
                            });
                }))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "test chain must complete: " + ar.cause());
                    ctx.completeNow();
                }));
    }

    /**
     * FR-WF-DEF-062: Verifying the scenario where the instance's {@code definition_version} column
     * references a version not in any live registry by directly updating the DB row to a fake
     * version that was never registered, then attempting a signal via engine A (which only knows v1).
     *
     * <p>This simulates the case where an instance was "poisoned" with a stale version pin that
     * no running engine knows about — a corner case that can occur if instances from a deprecated
     * deployment are discovered after a rollback.
     */
    @Test
    @DisplayName(
            "FR-WF-DEF-062 — signal for an instance whose DB row references an unknown version is recorded and deferred")
    void resumeWhenDbRowReferencesUnknownVersionIsRecordedAndDeferred(VertxTestContext ctx) {
        String key = "missing-pin-db-version-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(
                DEF_ID,
                MissingPinContract.class.getName(),
                new MissingPinPayload("db-test"),
                key,
                null,
                null,
                null,
                null,
                null);

        engineA.start(cmd)
                .compose(id ->
                        engineA.executionProbe().awaitIdle(id, IDLE_TIMEOUT).map(idle -> id))
                .compose(id -> {
                    // Hand-poke the DB row to reference a non-existent version (99).
                    return pool.preparedQuery("UPDATE workflow_instances SET definition_version = 99 WHERE id = $1")
                            .execute(Tuple.of(id.value()))
                            .map(v -> id);
                })
                .compose(id -> {
                    // Engine A does not know v99: the signal is recorded, and the executor can run no command of the
                    // instance, so it defers the signal and applies nothing.
                    return engineA.signal(id, "go", "payload", "dedup-" + UUID.randomUUID())
                            .compose(recorded -> engineA.executionProbe().awaitIdle(id, IDLE_TIMEOUT))
                            .compose(idle -> {
                                assertEquals(1, idle.deferred().size(), "the signal must be reported as deferred");
                                assertEquals(
                                        "SIGNAL", idle.deferred().get(0).kind(), "the deferred command is the signal");
                                return pool.preparedQuery("SELECT status FROM workflow_instances WHERE id = $1")
                                        .execute(Tuple.of(id.value()))
                                        .map(rows -> rows.iterator().next().getString("status"));
                            })
                            .map(status -> {
                                assertEquals("WAITING", status, "the instance must be left as it was");
                                return id;
                            });
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "test chain must complete: " + ar.cause());
                    ctx.completeNow();
                }));
    }
}
