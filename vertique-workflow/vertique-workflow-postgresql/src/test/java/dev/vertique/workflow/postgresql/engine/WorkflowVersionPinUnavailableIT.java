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
 * Integration tests verifying behavior when a workflow engine is constructed without the definition
 * version that an in-flight instance was pinned to at start time.
 *
 * <p>Engine A knows {@code version-test} v1 and is used to create an in-flight instance. Engine B
 * has an empty registry (does NOT know v1) and is used to exercise signal and query paths.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowVersionPinUnavailableIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_version_pin_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    /** Engine A knows {@code version-test} v1. */
    static WorkflowEngineHandle engineA;

    /** Engine B has an empty registry — does NOT know {@code version-test} v1. */
    static WorkflowEngineHandle engineB;

    // --- Sample types ---

    /**
     * Simple start payload used by the version-test workflow.
     *
     * @param value an arbitrary string value
     */
    record SimpleStartType(String value) {}

    /** Marker contract for the version-test workflow. */
    interface VersionTestContract {}

    // --- Workflow definition (v1) ---

    /**
     * A minimal workflow definition: init, wait for signal {@code "go"}, then complete.
     *
     * <p>The instance will be in {@code WAITING} status after start, making it a valid target for
     * engine B's signal attempt.
     */
    static final WorkflowDefinition<SimpleStartType, VersionTestContract> VERSION_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<VersionTestContract> contract() {
            return VersionTestContract.class;
        }

        @Override
        public Class<SimpleStartType> stateType() {
            return SimpleStartType.class;
        }

        @Override
        public String definitionId() {
            return "version-test";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<SimpleStartType> wf) {
            wf.init(SimpleStartType.class, x -> x)
                    .initialStep("a")
                    .waitFor("a", "go", String.class, (s, p) -> s, "b")
                    .complete("b");
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

        // Registry A: knows version-test v1.
        DefaultWorkflowRegistry registryA = new DefaultWorkflowRegistry();
        registryA.register(VERSION_DEF);
        engineA = PgWorkflowEngineTestSupport.create(
                pool,
                registryA,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        // Registry B: empty — does NOT know version-test v1.
        DefaultWorkflowRegistry registryB = new DefaultWorkflowRegistry();
        engineB = PgWorkflowEngineTestSupport.create(
                pool,
                registryB,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(new PgDbExceptionMapper()),
                new PgTaskStore(pool, new PgDbExceptionMapper()),
                Clock.systemUTC());

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
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
     * Sending a signal via engine B (which lacks the v1 definition) must fail with
     * {@link WorkflowVersionPinUnavailableException} carrying the correct fields.
     *
     * <p>Engine A starts the instance (which enters WAITING state). Engine B attempts to signal it.
     * Since engine B does not have the definition registered, it must fail with a typed exception
     * that includes the definitionId, version, and the actual instance id.
     */
    @Test
    @DisplayName("signal via engine without pinned version fails with WorkflowVersionPinUnavailableException")
    void signalViaEngineWithoutPinnedVersionFails(VertxTestContext ctx) {
        SimpleStartType payload = new SimpleStartType("test-value");
        String idempotencyKey = "version-pin-test-" + UUID.randomUUID();
        StartCommand startCmd = new StartCommand("version-test", payload, idempotencyKey, null, null);

        // Engine A starts the instance; it will be WAITING for signal "go".
        engineA.start(startCmd)
                .compose(id -> {
                    String dedupKey = "dedup-" + UUID.randomUUID();
                    // Engine B attempts to signal — must fail since it doesn't know v1.
                    return engineB.signal(id, "go", "payload", dedupKey).map(v -> id);
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "engineB.signal must fail when v1 is not registered");
                    assertInstanceOf(
                            WorkflowVersionPinUnavailableException.class,
                            ar.cause(),
                            "cause must be WorkflowVersionPinUnavailableException, got: " + ar.cause());

                    WorkflowVersionPinUnavailableException ex = (WorkflowVersionPinUnavailableException) ar.cause();
                    assertEquals("version-test", ex.definitionId(), "definitionId must be 'version-test'");
                    assertEquals(1L, ex.version(), "version must be 1");
                    assertNotNull(ex.instanceId(), "instanceId must not be null (engine must enrich the exception)");
                    ctx.completeNow();
                }));
    }

    /**
     * Querying an instance via engine B must succeed (query is read-only and definition-independent).
     *
     * <p>Engine A starts the instance. Engine B queries it without needing the definition
     * registered. The returned {@link WorkflowView} must reflect the persisted instance state.
     */
    @Test
    @DisplayName("query via engine without pinned version returns WorkflowView from snapshot (definition-independent)")
    void queryViaEngineWithoutPinnedVersionReturnsViewFromSnapshot(VertxTestContext ctx) {
        SimpleStartType payload = new SimpleStartType("query-value");
        String idempotencyKey = "version-pin-query-" + UUID.randomUUID();
        StartCommand startCmd = new StartCommand("version-test", payload, idempotencyKey, null, null);

        // Engine A starts the instance.
        engineA.start(startCmd)
                .compose(id -> {
                    // Engine B queries it — must succeed without the definition registered.
                    return engineB.query(id).map(view -> new Object[] {id, view});
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(
                            ar.succeeded(),
                            "engineB.query must succeed (read-only, no definition needed): " + ar.cause());
                    Object[] result = ar.result();
                    WorkflowInstanceId startedId = (WorkflowInstanceId) result[0];
                    WorkflowView view = (WorkflowView) result[1];

                    assertNotNull(view, "WorkflowView must not be null");
                    assertEquals(
                            startedId.value(),
                            view.instance().id().value(),
                            "returned instance id must match the started instance");
                    assertEquals("version-test", view.instance().definitionId(), "definitionId must be 'version-test'");
                    assertEquals(1L, view.instance().definitionVersion(), "definitionVersion must be 1");
                    assertNotNull(view.recentHistory(), "history must not be null");
                    assertFalse(view.recentHistory().isEmpty(), "history must have at least a START entry");
                    ctx.completeNow();
                }));
    }

    // --- Assertion helpers ---

    /**
     * Asserts that the condition is false, providing a message on failure.
     *
     * @param condition the condition to check
     * @param message failure message
     */
    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}
