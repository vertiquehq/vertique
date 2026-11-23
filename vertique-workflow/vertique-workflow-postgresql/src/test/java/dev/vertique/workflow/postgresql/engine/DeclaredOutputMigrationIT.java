// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
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
import java.time.Clock;
import java.time.Duration;
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
 * Verifies that an instance migrated from a version that declares no output to one that declares an output completes
 * with the output of the version it is pinned to when it completes.
 *
 * <p>Two engines share the repositories and differ in their registries: the first knows only the first version, so an
 * instance starts and waits on it; the second knows both and the migration handler, so it migrates the instance and
 * drives it on the second version's plan.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DeclaredOutputMigrationIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);
    private static final long WAIT_SECONDS = 15;
    private static final String DEFINITION_ID = "declared-output-migrate";

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_declared_output_migration_it")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engineV1;
    static WorkflowEngineHandle engineMigrate;

    // --- Types ---

    /**
     * Start payload of both versions.
     *
     * @param name an arbitrary name
     */
    record StartPayload(String name) {}

    /**
     * State of both versions.
     *
     * @param name the name from the start payload
     * @param migrated whether the migration handler produced this state
     */
    record MigState(String name, boolean migrated) {}

    /**
     * What the second version produces.
     *
     * @param name the name of the state
     * @param migrated whether the state was migrated
     */
    record MigReceipt(String name, boolean migrated) {}

    interface MigContract {
        @WorkflowSignal("pause")
        Future<Void> pause(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Version 1: waits for a signal, then completes; declares no output. */
    static final WorkflowDefinition<MigState, MigContract> DEF_V1 = new Definition(1L) {
        @Override
        public void define(WorkflowBuilder<MigState> wf) {
            wf.init(StartPayload.class, p -> new MigState(p.name(), false))
                    .initialStep("wait-v1")
                    .waitForSignal("wait-v1", "pause", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("done-v1")
                    .build()
                    .complete("done-v1");
        }
    };

    /** Version 2: completes at once, and declares an output. */
    static final WorkflowDefinition<MigState, MigContract> DEF_V2 = new Definition(2L) {
        @Override
        public void define(WorkflowBuilder<MigState> wf) {
            wf.output(MigReceipt.class, s -> new MigReceipt(s.name(), s.migrated()));
            wf.init(StartPayload.class, p -> new MigState(p.name(), false))
                    .initialStep("init-v2")
                    .complete("init-v2");
        }
    };

    private abstract static class Definition implements WorkflowDefinition<MigState, MigContract> {
        private final long version;

        Definition(long version) {
            this.version = version;
        }

        @Override
        public Class<MigContract> contract() {
            return MigContract.class;
        }

        @Override
        public Class<MigState> stateType() {
            return MigState.class;
        }

        @Override
        public String definitionId() {
            return DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return version;
        }
    }

    static final WorkflowMigrationHandler<MigState, MigState> HANDLER = new WorkflowMigrationHandler<>() {
        @Override
        public String definitionId() {
            return DEFINITION_ID;
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
        public Class<MigState> sourceStateType() {
            return MigState.class;
        }

        @Override
        public Class<MigState> targetStateType() {
            return MigState.class;
        }

        @Override
        public MigrationResult<MigState> migrate(MigState sourceState, MigrationContext ctx) {
            return MigrationResult.anchorAtInitial(new MigState(sourceState.name(), true));
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

        DefaultWorkflowRegistry registryV1 = new DefaultWorkflowRegistry();
        registryV1.register(DEF_V1);
        DefaultWorkflowRegistry registryBoth = new DefaultWorkflowRegistry();
        registryBoth.register(DEF_V1);
        registryBoth.register(DEF_V2);
        DefaultWorkflowMigrationRegistry migrations =
                new DefaultWorkflowMigrationRegistry(Set.of(HANDLER), registryBoth);

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
                null,
                Optional.of(migrations));
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() {
        join(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                                + " workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("an instance migrated from a version without an output completes with the output of the new version")
    void migratedInstanceCompletesWithTheOutputOfItsNewVersion() {
        // Given an instance waiting on version 1, which declares no output
        StartCommand start = new StartCommand(
                DEFINITION_ID,
                MigContract.class.getName(),
                new StartPayload("alice"),
                "declared-output-migrate-" + UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null);
        WorkflowInstanceId id = join(engineV1.start(start));
        join(engineV1.executionProbe().awaitIdle(id, IDLE_TIMEOUT));
        WorkflowView waiting = join(engineV1.query(id));
        assertThat(waiting.instance().status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(waiting.instance().definitionVersion()).isEqualTo(1L);
        assertThat(waiting.instance().outputJson()).isNull();
        assertThat(waiting.instance().outputTypeName()).isNull();

        // When the instance is migrated to version 2, which declares an output, and runs to its end
        join(engineMigrate.migrate(id, 2L));
        join(engineMigrate.executionProbe().awaitIdle(id, IDLE_TIMEOUT));

        // Then it is completed on version 2 with version 2's output, computed from the migrated state
        WorkflowView completed = join(engineMigrate.query(id));
        assertThat(completed.instance().definitionVersion()).isEqualTo(2L);
        assertThat(completed.instance().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.instance().outputTypeName()).isEqualTo(MigReceipt.class.getName());
        assertThat(new JsonObject(completed.instance().outputJson()))
                .isEqualTo(new JsonObject().put("name", "alice").put("migrated", true));
        assertThat(join(engineMigrate.result(id, MigReceipt.class))).isEqualTo(new MigReceipt("alice", true));
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future did not complete: " + e, e);
        }
    }
}
