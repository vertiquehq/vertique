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
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Clock;
import java.util.Optional;
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
 * Integration tests verifying the happy-path migration flow for
 * {@link WorkflowEngineHandle#migrate(WorkflowInstanceId, long)} (AC #6 / PRD-WF-003 §7.4).
 *
 * <p>Uses two engines that share the same repository layer but have different registries:
 * <ul>
 *   <li>{@code engineV1} — knows only v1; used to start and park instances at WAITING.</li>
 *   <li>{@code engineMigrate} — knows both v1 and v2 plus the migration registry; used to
 *       call {@code migrate(...)}.</li>
 * </ul>
 *
 * <p>After migration the following are asserted (AC #6):
 * <ul>
 *   <li>The instance is re-pinned to v2 with the correct plan hash.</li>
 *   <li>The instance has been driven forward in the v2 plan (AnchorAtInitial → init-v2 →
 *       CompleteNode → COMPLETED).</li>
 *   <li>A {@code WORKFLOW_MIGRATED} history entry was appended.</li>
 *   <li>The state JSON reflects the handler-produced target state.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineMigrateIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_migrate_happy_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    /** Engine that only knows v1 — starts instances so they land on v1. */
    static WorkflowEngineHandle engineV1;

    /** Engine that knows both v1 and v2, plus the migration registry. */
    static WorkflowEngineHandle engineMigrate;

    static String v1PlanHash;
    static String v2PlanHash;

    // --- Types ---

    /**
     * Start payload shared by both versions.
     *
     * @param name an arbitrary name value
     */
    record StartPayload(String name) {}

    /**
     * V1 workflow state.
     *
     * @param name the name from the start payload
     */
    record V1State(String name) {}

    /**
     * V2 workflow state — adds a {@code migrated} flag.
     *
     * @param name    the name carried over from V1
     * @param migrated flag indicating this state was produced by the migration handler
     */
    record V2State(String name, boolean migrated) {}

    /** Marker contract for v1 of the migration-test workflow. */
    interface MigrateTestContractV1 {}

    /** Marker contract for v2 of the migration-test workflow. */
    interface MigrateTestContractV2 {}

    static final String DEF_ID = "migrate-test";

    // --- V1 definition: init → waitForSignal("pause") → complete ---

    static final WorkflowDefinition<V1State, MigrateTestContractV1> DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<MigrateTestContractV1> contract() {
            return MigrateTestContractV1.class;
        }

        @Override
        public Class<V1State> stateType() {
            return V1State.class;
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
        public void define(WorkflowBuilder<V1State> wf) {
            wf.init(StartPayload.class, p -> new V1State(p.name()))
                    .initialStep("wait-v1")
                    .waitForSignal("wait-v1", "pause", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("done-v1")
                    .build()
                    .complete("done-v1");
        }
    };

    // --- V2 definition: init → complete (AnchorAtInitial drives instance here immediately) ---

    static final WorkflowDefinition<V2State, MigrateTestContractV2> DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<MigrateTestContractV2> contract() {
            return MigrateTestContractV2.class;
        }

        @Override
        public Class<V2State> stateType() {
            return V2State.class;
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
        public void define(WorkflowBuilder<V2State> wf) {
            wf.init(StartPayload.class, p -> new V2State(p.name(), false))
                    .initialStep("init-v2")
                    .complete("init-v2");
        }
    };

    // --- Migration handler: V1State → V2State, AnchorAtInitial ---

    static final WorkflowMigrationHandler<V1State, V2State> HANDLER = new WorkflowMigrationHandler<>() {
        @Override
        public String definitionId() {
            return DEF_ID;
        }

        @Override
        public long fromDefinitionVersion() {
            return 1L;
        }

        @Override
        public long targetDefinitionVersion() {
            return 2L;
        }

        @Override
        public Class<V1State> sourceStateType() {
            return V1State.class;
        }

        @Override
        public Class<V2State> targetStateType() {
            return V2State.class;
        }

        @Override
        public MigrationResult<V2State> migrate(V1State sourceState, MigrationContext ctx) {
            return MigrationResult.anchorAtInitial(new V2State(sourceState.name(), true));
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
        PgBranchTokenRepository branches = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);

        // Registry for engineV1: only v1 registered so resolveCurrent returns v1.
        DefaultWorkflowRegistry registryV1 = new DefaultWorkflowRegistry();
        registryV1.register(DEF_V1);

        // Registry for engineMigrate: both versions registered; needed for migration validation.
        DefaultWorkflowRegistry registryBoth = new DefaultWorkflowRegistry();
        registryBoth.register(DEF_V1);
        registryBoth.register(DEF_V2);

        v1PlanHash = registryV1.resolvePinned(DEF_ID, 1L).plan().planHash();
        v2PlanHash = registryBoth.resolvePinned(DEF_ID, 2L).plan().planHash();

        DefaultWorkflowMigrationRegistry migrationRegistry =
                new DefaultWorkflowMigrationRegistry(Set.of(HANDLER), registryBoth);

        // engineV1 — used to start instances (only v1 known).
        engineV1 = PgWorkflowEngineTestSupport.create(
                pool,
                registryV1,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC(),
                branches,
                joins);

        // engineMigrate — used to invoke migrate(...); knows both versions.
        engineMigrate = PgWorkflowEngineTestSupport.create(
                pool,
                registryBoth,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC(),
                branches,
                joins,
                null, // propagator — migration ITs do not exercise branch-create durable capture
                Optional.of(migrationRegistry));

        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_history, workflow_dedup, workflow_instances"
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
     * AC #6 happy path: start v1 instance via engineV1 (lands at WAITING), migrate to v2 via
     * engineMigrate, assert re-pin + history entry + instance advanced to COMPLETED on v2 plan
     * (AnchorAtInitial → init-v2 → CompleteNode).
     */
    @Test
    @DisplayName("AC #6 — migrate v1→v2 re-pins instance, records WORKFLOW_MIGRATED history, advances on v2 plan")
    void migrateHappyPath(VertxTestContext ctx) {
        String idempotencyKey = "migrate-happy-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(DEF_ID, new StartPayload("alice"), idempotencyKey, null, null);

        engineV1.start(cmd)
                .compose(id -> {
                    // The v1 instance must be WAITING for "pause" signal.
                    return engineV1.query(id).map(view -> {
                        assertEquals(WorkflowStatus.WAITING, view.instance().status(), "pre-migrate: must be WAITING");
                        assertEquals(1L, view.instance().definitionVersion(), "pre-migrate: must be on v1");
                        assertEquals(v1PlanHash, view.instance().planHash(), "pre-migrate: planHash must be v1 hash");
                        return id;
                    });
                })
                .compose(id -> engineMigrate.migrate(id, 2L).map(v -> id))
                .compose(id -> engineMigrate.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "migrate+query must succeed: " + ar.cause());
                    WorkflowView view = ar.result();

                    // Instance is re-pinned to v2.
                    assertEquals(2L, view.instance().definitionVersion(), "definitionVersion must be 2");
                    assertEquals(v2PlanHash, view.instance().planHash(), "planHash must match v2");

                    // AnchorAtInitial → init-v2 → CompleteNode → COMPLETED.
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            view.instance().status(),
                            "after migrate+drive, instance must be COMPLETED on v2 plan");

                    // Wait fields cleared (post-migration).
                    assertTrue(view.instance().waitType() == null, "waitType must be null after migration");
                    assertTrue(view.instance().waitKey() == null, "waitKey must be null after migration");
                    assertTrue(view.instance().waitAuxId() == null, "waitAuxId must be null after migration");

                    // WORKFLOW_MIGRATED history entry exists.
                    boolean hasMigratedEntry = view.recentHistory().stream()
                            .anyMatch(e -> e.entryType() == WorkflowEntryType.WORKFLOW_MIGRATED);
                    assertTrue(hasMigratedEntry, "history must contain a WORKFLOW_MIGRATED entry");

                    // State JSON reflects handler output (migrated=true).
                    assertNotNull(view.instance().stateJson(), "stateJson must not be null");
                    assertTrue(
                            view.instance().stateJson().contains("\"migrated\":true"),
                            "stateJson must contain migrated=true field from handler; got: "
                                    + view.instance().stateJson());

                    ctx.completeNow();
                }));
    }

    /**
     * Verifies the instance's plan hash after migration matches the target plan exactly.
     */
    @Test
    @DisplayName("plan hash: target v2 hash written to instance row after migration")
    void migratePlanHashIsCorrect(VertxTestContext ctx) {
        String idempotencyKey = "migrate-hash-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(DEF_ID, new StartPayload("bob"), idempotencyKey, null, null);

        engineV1.start(cmd)
                .compose(id -> engineMigrate.migrate(id, 2L).map(v -> id))
                .compose(id -> engineMigrate.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "migrate must succeed: " + ar.cause());
                    WorkflowView view = ar.result();

                    assertEquals(v2PlanHash, view.instance().planHash(), "instance planHash must be v2 hash");
                    assertEquals(2L, view.instance().definitionVersion(), "definitionVersion must be 2");

                    ctx.completeNow();
                }));
    }
}
