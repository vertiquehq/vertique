// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that truncating workflow tables through {@link WorkflowTestTables} neither fails nor leaves the executor of
 * the test engine stopped, while starts keep arriving for the executor to claim.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowTestTablesIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_tables_it")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;

    interface Contract {}

    record State() {}

    static final WorkflowDefinition<State, Contract> DEF = new WorkflowDefinition<>() {
        @Override
        public Class<Contract> contract() {
            return Contract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "test-tables";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Void.class, ignored -> new State()).initialStep("done").complete("done");
        }
    };

    @BeforeAll
    static void setUp(Vertx vertx) {
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
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(DEF);
        InMemoryWorkflowDefinitionRegistryStore.of(registry);
        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                new PgWorkflowInstanceRepository(pool, ex),
                new PgWorkflowHistoryRepository(pool, ex),
                new PgWorkflowDedupRepository(pool, ex),
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(ex),
                new PgTaskStore(pool, ex),
                Clock.systemUTC());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("truncating under a polling executor never fails and leaves the executor running")
    void truncatingUnderPollingExecutorNeverFailsAndLeavesExecutorRunning() throws Exception {
        // Given: an engine whose executor polls fast and a start recorded right before every truncation
        for (int i = 0; i < 60; i++) {
            engine.start(start());

            // When: the tables are truncated through the shared helper
            WorkflowTestTables.truncating(
                            pool,
                            "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances"
                                    + " RESTART IDENTITY CASCADE")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
        }

        // Then: the executor is running again, so it is refused a second start, and it completes a new start
        CompletionException refused = assertThrows(CompletionException.class, () -> engine.executor()
                .start()
                .toCompletionStage()
                .toCompletableFuture()
                .join());
        assertEquals(IllegalStateException.class, refused.getCause().getClass());
        var started =
                engine.start(start()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        engine.executionProbe()
                .awaitIdle(started, Duration.ofSeconds(10))
                .toCompletionStage()
                .toCompletableFuture()
                .get(15, TimeUnit.SECONDS);
        assertEquals(
                "COMPLETED",
                pool.query("SELECT status FROM workflow_instances")
                        .execute()
                        .map(rows -> rows.iterator().next().getString(0))
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS));
    }

    private static StartCommand start() {
        return new StartCommand(
                "test-tables", Contract.class.getName(), null, "k-" + UUID.randomUUID(), null, null, null, null, null);
    }
}
