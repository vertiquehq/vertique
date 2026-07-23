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
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationIllegalStateException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
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
 * Integration tests verifying that {@link WorkflowEngineHandle#migrate} throws
 * {@link WorkflowMigrationIllegalStateException} in disallowed states (AC #15 / PRD-WF-003 §7.4):
 * <ul>
 *   <li>Migration on a COMPLETED instance.</li>
 *   <li>Migration on a CANCELLED instance.</li>
 *   <li>Migration on an instance with active fork-branch state.</li>
 * </ul>
 *
 * <p>Two separate engine pairs are used:
 * <ul>
 *   <li>{@code engineSimpleV1} / {@code engineSimpleMigrate} — for the simple definition tests
 *       (COMPLETED, CANCELLED). V1 engine starts instances; migrate engine calls
 *       {@code migrate(...)}.
 *   <li>{@code engineForkV1} / {@code engineForkMigrate} — for the fork definition test
 *       (active branches).
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowEngineMigrateIllegalStateIT {

    // --- Testcontainers ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_migrate_illegal_state_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    /** Starts simple-def v1 instances (only v1 registered → resolveCurrent returns v1). */
    static WorkflowEngineHandle engineSimpleV1;

    /** Performs migrate on simple-def instances (both v1+v2 registered). */
    static WorkflowEngineHandle engineSimpleMigrate;

    /** Starts fork-def v1 instances. */
    static WorkflowEngineHandle engineForkV1;

    /** Performs migrate on fork-def instances. */
    static WorkflowEngineHandle engineForkMigrate;

    // --- Types ---

    /**
     * Simple state record.
     *
     * @param value an arbitrary value
     */
    record SimpleState(String value) {}

    /**
     * Target state for migration.
     *
     * @param value carried value
     */
    record TargetState(String value) {}

    /** Marker contract for v1. */
    interface IllegalStateContractV1 {}

    /** Marker contract for v2. */
    interface IllegalStateContractV2 {}

    /** Marker contract for fork v1. */
    interface ForkContractV1 {}

    /** Marker contract for fork v2. */
    interface ForkContractV2 {}

    static final String SIMPLE_DEF_ID = "migrate-illegal-simple";
    static final String FORK_DEF_ID = "migrate-illegal-fork";

    // --- Simple definition: init → waitForSignal("go") → complete ---

    static final WorkflowDefinition<SimpleState, IllegalStateContractV1> SIMPLE_DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<IllegalStateContractV1> contract() {
            return IllegalStateContractV1.class;
        }

        @Override
        public Class<SimpleState> stateType() {
            return SimpleState.class;
        }

        @Override
        public String definitionId() {
            return SIMPLE_DEF_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<SimpleState> wf) {
            wf.init(SimpleState.class, s -> s)
                    .initialStep("wait")
                    .waitForSignal("wait", "go", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("done")
                    .build()
                    .complete("done");
        }
    };

    static final WorkflowDefinition<TargetState, IllegalStateContractV2> SIMPLE_DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<IllegalStateContractV2> contract() {
            return IllegalStateContractV2.class;
        }

        @Override
        public Class<TargetState> stateType() {
            return TargetState.class;
        }

        @Override
        public String definitionId() {
            return SIMPLE_DEF_ID;
        }

        @Override
        public long definitionVersion() {
            return 2L;
        }

        @Override
        public void define(WorkflowBuilder<TargetState> wf) {
            wf.init(TargetState.class, s -> s).initialStep("done-v2").complete("done-v2");
        }
    };

    // --- Fork definition: fork → two branches waiting for signals → join → complete ---

    static final WorkflowDefinition<SimpleState, ForkContractV1> FORK_DEF_V1 = new WorkflowDefinition<>() {
        @Override
        public Class<ForkContractV1> contract() {
            return ForkContractV1.class;
        }

        @Override
        public Class<SimpleState> stateType() {
            return SimpleState.class;
        }

        @Override
        public String definitionId() {
            return FORK_DEF_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<SimpleState> wf) {
            wf.init(SimpleState.class, s -> s)
                    .fork("fork")
                    .branch("a", "wait-a")
                    .branch("b", "wait-b")
                    .join("join")
                    // Branch A waits for signal "sig-a".
                    .waitForSignal("wait-a", "sig-a", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("done-a")
                    .build()
                    .complete("done-a")
                    // Branch B waits for signal "sig-b".
                    .waitForSignal("wait-b", "sig-b", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("done-b")
                    .build()
                    .complete("done-b")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("fin")
                    .endJoin()
                    .complete("fin");
            wf.initialStep("fork");
        }
    };

    static final WorkflowDefinition<TargetState, ForkContractV2> FORK_DEF_V2 = new WorkflowDefinition<>() {
        @Override
        public Class<ForkContractV2> contract() {
            return ForkContractV2.class;
        }

        @Override
        public Class<TargetState> stateType() {
            return TargetState.class;
        }

        @Override
        public String definitionId() {
            return FORK_DEF_ID;
        }

        @Override
        public long definitionVersion() {
            return 2L;
        }

        @Override
        public void define(WorkflowBuilder<TargetState> wf) {
            wf.init(TargetState.class, s -> s).initialStep("done-fork-v2").complete("done-fork-v2");
        }
    };

    // --- Migration handlers ---

    static final WorkflowMigrationHandler<SimpleState, TargetState> SIMPLE_HANDLER = new WorkflowMigrationHandler<>() {
        @Override
        public String definitionId() {
            return SIMPLE_DEF_ID;
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
        public Class<SimpleState> sourceStateType() {
            return SimpleState.class;
        }

        @Override
        public Class<TargetState> targetStateType() {
            return TargetState.class;
        }

        @Override
        public MigrationResult<TargetState> migrate(SimpleState sourceState, MigrationContext ctx) {
            return MigrationResult.anchorAtInitial(new TargetState(sourceState.value()));
        }
    };

    static final WorkflowMigrationHandler<SimpleState, TargetState> FORK_HANDLER = new WorkflowMigrationHandler<>() {
        @Override
        public String definitionId() {
            return FORK_DEF_ID;
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
        public Class<SimpleState> sourceStateType() {
            return SimpleState.class;
        }

        @Override
        public Class<TargetState> targetStateType() {
            return TargetState.class;
        }

        @Override
        public MigrationResult<TargetState> migrate(SimpleState sourceState, MigrationContext ctx) {
            return MigrationResult.anchorAtInitial(new TargetState(sourceState.value()));
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
        // --- Simple definition engines ---
        DefaultWorkflowRegistry simpleV1Registry = new DefaultWorkflowRegistry();
        simpleV1Registry.register(SIMPLE_DEF_V1);

        DefaultWorkflowRegistry simpleBothRegistry = new DefaultWorkflowRegistry();
        simpleBothRegistry.register(SIMPLE_DEF_V1);
        simpleBothRegistry.register(SIMPLE_DEF_V2);

        DefaultWorkflowMigrationRegistry simpleMigrationRegistry =
                new DefaultWorkflowMigrationRegistry(Set.of(SIMPLE_HANDLER), simpleBothRegistry);

        engineSimpleV1 = PgWorkflowEngineTestSupport.create(
                pool,
                simpleV1Registry,
                instances,
                history,
                dedup,
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());

        engineSimpleMigrate = PgWorkflowEngineTestSupport.create(
                pool,
                simpleBothRegistry,
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
                Optional.of(simpleMigrationRegistry));

        // --- Fork definition engines ---
        DefaultWorkflowRegistry forkV1Registry = new DefaultWorkflowRegistry();
        forkV1Registry.register(FORK_DEF_V1);

        DefaultWorkflowRegistry forkBothRegistry = new DefaultWorkflowRegistry();
        forkBothRegistry.register(FORK_DEF_V1);
        forkBothRegistry.register(FORK_DEF_V2);

        DefaultWorkflowMigrationRegistry forkMigrationRegistry =
                new DefaultWorkflowMigrationRegistry(Set.of(FORK_HANDLER), forkBothRegistry);

        engineForkV1 = PgWorkflowEngineTestSupport.create(
                pool,
                forkV1Registry,
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

        engineForkMigrate = PgWorkflowEngineTestSupport.create(
                pool,
                forkBothRegistry,
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
                Optional.of(forkMigrationRegistry));

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
     * AC #15a — Migrating a COMPLETED instance throws
     * {@link WorkflowMigrationIllegalStateException}.
     */
    @Test
    @DisplayName("AC #15a — migrate on COMPLETED instance throws WorkflowMigrationIllegalStateException")
    void migrateCompletedInstance(VertxTestContext ctx) {
        String key = "migrate-completed-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(SIMPLE_DEF_ID, new SimpleState("x"), key, null, null);

        // Start via v1 engine, signal to complete.
        engineSimpleV1
                .start(cmd)
                .compose(id -> engineSimpleV1
                        .signal(id, "go", "payload", "dedup-" + UUID.randomUUID())
                        .compose(v -> engineSimpleV1.query(id).map(view -> {
                            assertEquals(
                                    WorkflowStatus.COMPLETED,
                                    view.instance().status(),
                                    "instance must be COMPLETED before migrate");
                            return id;
                        })))
                .compose(id -> engineSimpleMigrate
                        .migrate(id, 2L)
                        .map(v -> (WorkflowInstanceId) null)
                        .recover(e -> {
                            assertInstanceOf(
                                    WorkflowMigrationIllegalStateException.class,
                                    e,
                                    "must throw WorkflowMigrationIllegalStateException; got: " + e);
                            return io.vertx.core.Future.succeededFuture(null);
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "test chain must complete: " + ar.cause());
                    ctx.completeNow();
                }));
    }

    /**
     * AC #15a — Migrating a CANCELLED instance throws
     * {@link WorkflowMigrationIllegalStateException}.
     */
    @Test
    @DisplayName("AC #15a — migrate on CANCELLED instance throws WorkflowMigrationIllegalStateException")
    void migrateCancelledInstance(VertxTestContext ctx) {
        String key = "migrate-cancelled-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(SIMPLE_DEF_ID, new SimpleState("y"), key, null, null);

        engineSimpleV1
                .start(cmd)
                .compose(id -> engineSimpleV1.cancel(id, "test-cancel").map(v -> id))
                .compose(id -> engineSimpleV1.query(id).map(view -> {
                    assertEquals(
                            WorkflowStatus.CANCELLED,
                            view.instance().status(),
                            "instance must be CANCELLED before migrate");
                    return id;
                }))
                .compose(id -> engineSimpleMigrate
                        .migrate(id, 2L)
                        .map(v -> (WorkflowInstanceId) null)
                        .recover(e -> {
                            assertInstanceOf(
                                    WorkflowMigrationIllegalStateException.class,
                                    e,
                                    "must throw WorkflowMigrationIllegalStateException; got: " + e);
                            return io.vertx.core.Future.succeededFuture(null);
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "test chain must complete: " + ar.cause());
                    ctx.completeNow();
                }));
    }

    /**
     * AC #15b — Migrating an instance with active fork-branch state throws
     * {@link WorkflowMigrationIllegalStateException}.
     *
     * <p>The fork definition starts and parks the instance at {@code WAITING} (join wait) with two
     * active branch tokens each waiting for a signal. No signals are sent so the branches remain
     * active.
     */
    @Test
    @DisplayName(
            "AC #15b — migrate on instance with active fork-branch state throws WorkflowMigrationIllegalStateException")
    void migrateInstanceWithActiveBranches(VertxTestContext ctx) {
        String key = "migrate-fork-" + UUID.randomUUID();
        StartCommand cmd = new StartCommand(FORK_DEF_ID, new SimpleState("z"), key, null, null);

        engineForkV1
                .start(cmd)
                .compose(id -> engineForkV1.query(id).map(view -> {
                    // Instance is parked at the join with waitType=JOIN and status=RUNNING
                    // (handleForkNode does not change status to WAITING — it keeps RUNNING
                    // with waitType=JOIN while branches are in flight).
                    assertEquals(
                            dev.vertique.workflow.state.WaitType.JOIN,
                            view.instance().waitType(),
                            "instance must have waitType=JOIN after fork; got: "
                                    + view.instance().waitType());
                    return id;
                }))
                .compose(id -> engineForkMigrate
                        .migrate(id, 2L)
                        .map(v -> (WorkflowInstanceId) null)
                        .recover(e -> {
                            assertInstanceOf(
                                    WorkflowMigrationIllegalStateException.class,
                                    e,
                                    "must throw WorkflowMigrationIllegalStateException for active branches; got: " + e);
                            return io.vertx.core.Future.succeededFuture(null);
                        }))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "test chain must complete: " + ar.cause());
                    ctx.completeNow();
                }));
    }
}
