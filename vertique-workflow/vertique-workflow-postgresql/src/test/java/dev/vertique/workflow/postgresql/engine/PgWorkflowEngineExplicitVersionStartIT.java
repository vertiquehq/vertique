// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * Integration tests verifying explicit version pinning at workflow start (FR-WF-DEF-069, AC #13).
 *
 * <p>Registers both v1 and v2 of the same definition id, then asserts that:
 * <ul>
 *   <li>Starting with {@code requestedDefinitionVersion=1} pins the instance to v1.</li>
 *   <li>Starting with {@code requestedDefinitionVersion=2} pins the instance to v2.</li>
 *   <li>Starting with no requested version pins to the current (highest) registered version.</li>
 *   <li>Starting with an unregistered version fails with
 *       {@link WorkflowVersionPinUnavailableException}.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineExplicitVersionStartIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_explicit_version_start_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    // --- Fixture types ---

    /** Simple start payload for both versions. */
    record SimplePayload(String id) {}

    /** Contract interface for v1 of the pin-test definition. */
    interface PinTestContract {}

    static final String DEF_ID = "explicit-version-pin-test";

    /** v1 definition: init + complete immediately. */
    static final WorkflowDefinition<SimplePayload, PinTestContract> DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<PinTestContract> contract() {
            return PinTestContract.class;
        }

        @Override
        public Class<SimplePayload> stateType() {
            return SimplePayload.class;
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
        public void define(WorkflowBuilder<SimplePayload> wf) {
            wf.init(SimplePayload.class, x -> x).initialStep("done").complete("done");
        }
    };

    /** v2 definition: init + complete immediately, distinct contract. */
    static final WorkflowDefinition<SimplePayload, PinTestContract> DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<PinTestContract> contract() {
            return PinTestContract.class;
        }

        @Override
        public Class<SimplePayload> stateType() {
            return SimplePayload.class;
        }

        @Override
        public String definitionId() {
            return DEF_ID;
        }

        @Override
        public long definitionVersion() {
            return 2L;
        }

        @Override
        public void define(WorkflowBuilder<SimplePayload> wf) {
            wf.init(SimplePayload.class, x -> x).initialStep("done").complete("done");
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

        // Register both v1 and v2 — highest version (v2) becomes the "current".
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(DEF_V1);
        registry.register(DEF_V2);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
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
     * Requesting {@code requestedDefinitionVersion=1} after both v1 and v2 are registered must
     * create an instance pinned to v1 (AC #13).
     */
    @Test
    @DisplayName("requestedDefinitionVersion=1 pins instance to v1 even when v2 is registered")
    void startWithRequestedVersionOnePinsToV1(VertxTestContext ctx) {
        String ikey = "pin-v1-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(
                DEF_ID, PinTestContract.class.getName(), new SimplePayload("p"), ikey, null, null, 1L, null, null);

        engine.start(cmd)
                .compose(id ->
                        engine.executionProbe().awaitIdle(id, IDLE_TIMEOUT).map(r -> id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "start+query must succeed: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(1L, view.instance().definitionVersion(), "instance must be pinned to v1");
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "instance must be COMPLETED");
                    ctx.completeNow();
                }));
    }

    /**
     * Requesting {@code requestedDefinitionVersion=2} must create an instance pinned to v2.
     */
    @Test
    @DisplayName("requestedDefinitionVersion=2 pins instance to v2")
    void startWithRequestedVersionTwoPinsToV2(VertxTestContext ctx) {
        String ikey = "pin-v2-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(
                DEF_ID, PinTestContract.class.getName(), new SimplePayload("p"), ikey, null, null, 2L, null, null);

        engine.start(cmd)
                .compose(id ->
                        engine.executionProbe().awaitIdle(id, IDLE_TIMEOUT).map(r -> id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "start+query must succeed: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(2L, view.instance().definitionVersion(), "instance must be pinned to v2");
                    ctx.completeNow();
                }));
    }

    /**
     * Starting with no requested version must pin to the current (highest) registered version v2.
     */
    @Test
    @DisplayName("no requestedDefinitionVersion defaults to current (highest) version")
    void startWithNoRequestedVersionUsesCurrentVersion(VertxTestContext ctx) {
        String ikey = "pin-current-" + UUID.randomUUID();
        // 5-arg backward-compat constructor: no requested version.
        StartCommand cmd = new StartCommand(
                DEF_ID, PinTestContract.class.getName(), new SimplePayload("p"), ikey, null, null, null, null, null);

        engine.start(cmd)
                .compose(id ->
                        engine.executionProbe().awaitIdle(id, IDLE_TIMEOUT).map(r -> id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "start+query must succeed: " + ar.cause());
                    WorkflowView view = ar.result();
                    assertEquals(2L, view.instance().definitionVersion(), "no requested version → current (v2)");
                    ctx.completeNow();
                }));
    }

    /**
     * Requesting an unregistered version must fail with
     * {@link WorkflowVersionPinUnavailableException}.
     */
    @Test
    @DisplayName("requestedDefinitionVersion=99 (unregistered) → WorkflowVersionPinUnavailableException")
    void startWithUnregisteredVersionFails(VertxTestContext ctx) {
        String ikey = "pin-missing-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(
                DEF_ID, PinTestContract.class.getName(), new SimplePayload("p"), ikey, null, null, 99L, null, null);

        engine.start(cmd)
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "start must fail for unregistered version");
                    assertInstanceOf(
                            WorkflowVersionPinUnavailableException.class,
                            ar.cause(),
                            "cause must be WorkflowVersionPinUnavailableException, got: " + ar.cause());
                    WorkflowVersionPinUnavailableException ex = (WorkflowVersionPinUnavailableException) ar.cause();
                    assertEquals(DEF_ID, ex.definitionId());
                    assertEquals(99L, ex.version());
                    ctx.completeNow();
                }));
    }
}
