// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
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
import io.vertx.sqlclient.Tuple;
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
 * Integration tests verifying that attempting to resume a workflow instance whose pinned
 * definition version is no longer registered in the engine produces a typed
 * {@link WorkflowVersionPinUnavailableException} with the {@code instanceId} populated, and that
 * the transaction is rolled back leaving the instance state unchanged (FR-WF-DEF-062).
 *
 * <p>Engine A knows v1 and is used to create a WAITING instance. Engine B has an empty registry
 * (does NOT know v1) and is used to attempt signal delivery. The attempt must fail with the typed
 * exception carrying the correct {@code definitionId}, {@code version}, and {@code instanceId}.
 * After the failure the instance row must still be WAITING with its original version counter
 * (proving the transaction rolled back).
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineMissingPinResumeIT {

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
    interface MissingPinContract {}

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
        pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
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
     * FR-WF-DEF-062: Attempting to resume (signal) via engine B throws
     * {@link WorkflowVersionPinUnavailableException} with {@code instanceId} populated. The
     * instance row must be unchanged after the failed attempt.
     */
    @Test
    @DisplayName(
            "FR-WF-DEF-062 — signal via engine without pinned version throws typed exception with instanceId; instance unchanged")
    void signalViaEngineWithoutPinnedVersionThrowsTypedExceptionAndRollsBack(VertxTestContext ctx) {
        String key = "missing-pin-signal-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(DEF_ID, new MissingPinPayload("test"), key, null, null);

        // Engine A starts the instance — ends up WAITING for "go".
        engineA.start(cmd)
                .compose(id -> engineA.query(id).map(view -> {
                    assertEquals(WorkflowStatus.WAITING, view.instance().status(), "must be WAITING before signal");
                    return id;
                }))
                .compose(id -> {
                    long versionBeforeAttempt = -1; // captured below via query.
                    return engineA.query(id).compose(view -> {
                        long capturedVersion = view.instance().version();
                        // Engine B attempts to signal — must fail.
                        return engineB.signal(id, "go", "payload", "dedup-" + UUID.randomUUID())
                                .compose(
                                        // Should not reach here.
                                        v -> io.vertx.core.Future.<WorkflowView>failedFuture(
                                                new AssertionError("signal must have failed")),
                                        e -> {
                                            // Validate the exception type and fields.
                                            assertInstanceOf(
                                                    WorkflowVersionPinUnavailableException.class,
                                                    e,
                                                    "must throw WorkflowVersionPinUnavailableException; got: " + e);
                                            WorkflowVersionPinUnavailableException ex =
                                                    (WorkflowVersionPinUnavailableException) e;
                                            assertEquals(DEF_ID, ex.definitionId(), "definitionId must match");
                                            assertEquals(1L, ex.version(), "version must be 1");
                                            assertNotNull(ex.instanceId(), "instanceId must be populated");
                                            assertEquals(
                                                    id.value(),
                                                    ex.instanceId().value(),
                                                    "instanceId must match the started instance");

                                            // Verify the instance is still WAITING and version is unchanged.
                                            return engineA.query(id).map(afterView -> {
                                                assertEquals(
                                                        WorkflowStatus.WAITING,
                                                        afterView.instance().status(),
                                                        "instance must still be WAITING after failed signal");
                                                assertEquals(
                                                        capturedVersion,
                                                        afterView.instance().version(),
                                                        "instance version must be unchanged (tx rolled back)");
                                                return afterView;
                                            });
                                        });
                    });
                })
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
    @DisplayName("FR-WF-DEF-062 — resume when DB row references unknown version throws typed exception with instanceId")
    void resumeWhenDbRowReferencesUnknownVersionThrowsTypedException(VertxTestContext ctx) {
        String key = "missing-pin-db-version-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(DEF_ID, new MissingPinPayload("db-test"), key, null, null);

        engineA.start(cmd)
                .compose(id -> {
                    // Hand-poke the DB row to reference a non-existent version (99).
                    return pool.preparedQuery("UPDATE workflow_instances SET definition_version = 99 WHERE id = $1")
                            .execute(Tuple.of(id.value()))
                            .map(v -> id);
                })
                .compose(id -> {
                    // Engine A does not know v99 — signal must fail.
                    return engineA.signal(id, "go", "payload", "dedup-" + UUID.randomUUID())
                            .compose(
                                    v -> io.vertx.core.Future.<WorkflowInstanceId>failedFuture(
                                            new AssertionError("signal must have failed")),
                                    e -> {
                                        assertInstanceOf(
                                                WorkflowVersionPinUnavailableException.class,
                                                e,
                                                "must throw WorkflowVersionPinUnavailableException; got: " + e);
                                        WorkflowVersionPinUnavailableException ex =
                                                (WorkflowVersionPinUnavailableException) e;
                                        assertEquals(DEF_ID, ex.definitionId(), "definitionId must match");
                                        assertEquals(99L, ex.version(), "version must be 99");
                                        assertNotNull(ex.instanceId(), "instanceId must be populated");
                                        assertEquals(
                                                id.value(),
                                                ex.instanceId().value(),
                                                "instanceId must match the instance");
                                        return io.vertx.core.Future.succeededFuture(id);
                                    });
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "test chain must complete: " + ar.cause());
                    ctx.completeNow();
                }));
    }
}
