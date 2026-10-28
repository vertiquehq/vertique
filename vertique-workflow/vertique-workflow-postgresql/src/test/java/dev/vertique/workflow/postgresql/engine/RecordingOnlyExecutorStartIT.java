// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowNodeRoleException;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.RaceSafetyTargetContributor;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Shows that the executor control of an engine assembled by the engine factory refuses to start when the executor
 * configuration turns the executor off, whatever the caller does with the handle.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordingOnlyExecutorStartIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recording_only_start_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    void executorControlOfARecordingOnlyEngineRefusesToStart(Vertx vertx) throws Exception {
        // Given an engine assembled with an executor configuration that turns the executor off
        DbPoolConfig config = db.toPoolConfig();
        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(2))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        try {
            DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry(new WorkflowPlanValidator(
                    new DefaultRaceSafetyTargetRegistry(Set.<RaceSafetyTargetContributor>of())));
            WorkflowEngineHandle engine = PgWorkflowEngineTestSupport.executorEngine(pool, vertx, registry)
                    .executorConfig(new WorkflowExecutorConfig(1, 8, 100, 60, 3, 100, 1000, 1000, 300, false))
                    .build();

            // When its executor is started
            var start = engine.executor().start();
            start.toCompletionStage()
                    .toCompletableFuture()
                    .handle((v, t) -> null)
                    .get(15, TimeUnit.SECONDS);

            // Then the start fails with the node-role error and the executor stays stopped
            assertThat(start.failed()).isTrue();
            assertThat(start.cause()).isInstanceOf(WorkflowNodeRoleException.class);
            assertThat(engine.executor().stop().succeeded()).isTrue();
        } finally {
            pool.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        }
    }
}
