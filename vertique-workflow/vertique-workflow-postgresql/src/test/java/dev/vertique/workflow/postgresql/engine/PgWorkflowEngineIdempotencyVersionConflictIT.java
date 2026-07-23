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
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
 * Integration tests verifying idempotency-version conflict detection at workflow start
 * (FR-WF-DEF-070, AC #14).
 *
 * <p>Registers both v1 and v2 of the same definition id, then asserts that:
 * <ul>
 *   <li>Same idempotency key + different requested version → {@link WorkflowIdempotencyConflictException}
 *       with {@code kind="start"} and correct fingerprints.</li>
 *   <li>Same idempotency key + same requested version → returns existing instance (idempotent retry).</li>
 *   <li>Same idempotency key + no requested version (after first start with explicit v1, active now v2)
 *       → returns existing instance regardless of stored fingerprint (lenient semantics).</li>
 *   <li>First start with no requested version pins to current active v1; retry with explicit
 *       {@code requestedDefinitionVersion=2} → conflict.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineIdempotencyVersionConflictIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_idempotency_version_conflict_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    // --- Fixture types ---

    /** Simple start payload. */
    record SimplePayload(String id) {}

    /** Contract for v1. */
    interface IdempConflictContractV1 {}

    /** Contract for v2 — must be distinct from V1. */
    interface IdempConflictContractV2 {}

    static final String DEF_ID = "idempotency-conflict-test";

    static final WorkflowDefinition<SimplePayload, IdempConflictContractV1> DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<IdempConflictContractV1> contract() {
            return IdempConflictContractV1.class;
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

    static final WorkflowDefinition<SimplePayload, IdempConflictContractV2> DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<IdempConflictContractV2> contract() {
            return IdempConflictContractV2.class;
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
     * Same idempotency key + different requested version → {@link WorkflowIdempotencyConflictException}
     * with {@code kind="start"} and correct existing/incoming fingerprints (AC #14).
     */
    @Test
    @DisplayName("same idempotency key + different requested version → WorkflowIdempotencyConflictException")
    void sameKeyDifferentVersionThrowsConflict(VertxTestContext ctx) {
        String ikey = "conflict-key-" + UUID.randomUUID();
        StartCommand firstCmd = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null, 1L);
        StartCommand conflictCmd = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null, 2L);

        engine.start(firstCmd)
                .compose(firstId -> {
                    assertNotNull(firstId, "first start must return an id");
                    return engine.start(conflictCmd);
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "second start with different version must fail");
                    assertInstanceOf(
                            WorkflowIdempotencyConflictException.class,
                            ar.cause(),
                            "cause must be WorkflowIdempotencyConflictException, got: " + ar.cause());

                    WorkflowIdempotencyConflictException ex = (WorkflowIdempotencyConflictException) ar.cause();
                    assertEquals("start", ex.kind(), "kind must be 'start'");
                    assertEquals(ikey, ex.idempotencyKey(), "idempotencyKey must match");
                    assertEquals(
                            "definitionVersion=1",
                            ex.existingFingerprint(),
                            "existingFingerprint must reflect first start version");
                    assertEquals(
                            "definitionVersion=2",
                            ex.incomingFingerprint(),
                            "incomingFingerprint must reflect second start version");
                    ctx.completeNow();
                }));
    }

    /**
     * Same idempotency key + same requested version → returns existing instance (no exception).
     */
    @Test
    @DisplayName("same idempotency key + same requested version → idempotent (returns existing instance)")
    void sameKeySameVersionIsIdempotent(VertxTestContext ctx) {
        String ikey = "idempotent-key-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null, 1L);

        engine.start(cmd)
                .compose(firstId -> engine.start(cmd).map(secondId -> new WorkflowInstanceId[] {firstId, secondId}))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "both starts must succeed: " + ar.cause());
                    WorkflowInstanceId[] ids = ar.result();
                    assertEquals(ids[0].value(), ids[1].value(), "both starts must return the same instance id");
                    ctx.completeNow();
                }));
    }

    /**
     * Lenient semantics: first start with explicit v1 records fingerprint "definitionVersion=1";
     * retry with no requested version (active is v2) → returns existing instance regardless
     * of stored fingerprint (no conflict thrown).
     */
    @Test
    @DisplayName("retry with no requested version after explicit v1 start → lenient, returns existing instance")
    void retryWithNoVersionAfterExplicitV1IsLenient(VertxTestContext ctx) {
        String ikey = "lenient-no-version-" + UUID.randomUUID();
        StartCommand explicitV1 = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null, 1L);
        // retry with no requested version (5-arg form)
        StartCommand noVersion = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null);

        engine.start(explicitV1)
                .compose(firstId -> engine.start(noVersion).map(retryId -> new WorkflowInstanceId[] {firstId, retryId}))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "lenient retry must succeed: " + ar.cause());
                    WorkflowInstanceId[] ids = ar.result();
                    assertEquals(ids[0].value(), ids[1].value(), "lenient retry must return the same instance id");
                    ctx.completeNow();
                }));
    }

    /**
     * First start with no requested version pins to current active (v2 since both registered).
     * Retry with explicit {@code requestedDefinitionVersion=1} → conflict because existing
     * fingerprint is "definitionVersion=2" and incoming is "definitionVersion=1".
     */
    @Test
    @DisplayName("first start with no version, retry with explicit v1 (active=v2) → conflict")
    void firstStartNoVersionRetryWithExplicitV1Conflicts(VertxTestContext ctx) {
        String ikey = "strict-after-no-version-" + UUID.randomUUID();
        // First start: no requested version; registry's current is v2 (highest registered).
        StartCommand noVersion = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null);
        // Retry with explicit v1.
        StartCommand explicitV1 = new StartCommand(DEF_ID, new SimplePayload("p"), ikey, null, null, 1L);

        engine.start(noVersion)
                .compose(firstId -> {
                    assertNotNull(firstId, "first start must succeed");
                    return engine.start(explicitV1);
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "retry with explicit v1 after v2 start must fail");
                    assertInstanceOf(
                            WorkflowIdempotencyConflictException.class,
                            ar.cause(),
                            "cause must be WorkflowIdempotencyConflictException, got: " + ar.cause());

                    WorkflowIdempotencyConflictException ex = (WorkflowIdempotencyConflictException) ar.cause();
                    assertEquals("start", ex.kind());
                    assertEquals(ikey, ex.idempotencyKey());
                    assertEquals(
                            "definitionVersion=2",
                            ex.existingFingerprint(),
                            "existing must be v2 (the current version at first start)");
                    assertEquals(
                            "definitionVersion=1",
                            ex.incomingFingerprint(),
                            "incoming must be v1 (the explicit request)");
                    ctx.completeNow();
                }));
    }
}
