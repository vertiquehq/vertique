// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.vertique.core.VertxModule;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.postgresql.executor.ReservedNameFixture;
import dev.vertique.workflow.services.signal.WorkflowSignalEndpoint;
import dev.vertique.workflow.services.signal.WorkflowSignalRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReservedSignalNameIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_reserved_signal_name_it")
            .withMigration("classpath:db/migration/workflow");

    @BeforeAll
    static void migrateMessaging() {
        for (String location : new String[] {"inbox-outbox", "job"}) {
            Flyway.configure()
                    .dataSource(db.jdbcUrl(), db.username(), db.password())
                    .locations("classpath:db/migration/" + location)
                    .table("flyway_" + location.replace('-', '_') + "_history")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
        }
    }

    static Stream<Arguments> pathsAndNames() {
        return Stream.of(
                        "signal", "branch", "metadata", "relayed", "relayed-metadata", "key", "business-key", "service")
                .flatMap(path -> Stream.of("vertique.reply.ok.reserve", "vertique.reply.failed.reserve")
                        .map(name -> Arguments.of(path, name)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("pathsAndNames")
    void reservedNameIsRefusedOnEveryPublicPath(String path, String name) {
        if (!path.equals("service")) {
            ReservedNameFixture.assertRefused(db.toPoolConfig(), path, name);
            return;
        }
        ReservedNameFixture.assertServiceRefused(db.toPoolConfig(), (id, control) -> {
            Vertx vertx = Vertx.vertx();
            var options = RelayGraph.options(db);
            var pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            var config = RelayGraph.config(null);
            config.getJsonObject("workflow").getJsonObject("executor").put("enabled", false);
            var graph = DaggerRelayGraph_Plain.builder()
                    .vertxModule(new VertxModule(vertx, config))
                    .infrastructure(new RelayGraph.Infrastructure(pool, options))
                    .build();
            try {
                var handle = await(RelayGraph.start(vertx, graph));
                try {
                    var endpoint = graph.serviceClientFactory().create(WorkflowSignalEndpoint.class);
                    await(endpoint.post(WorkflowSignalRequest.instance(control, "other", "payload", "control-key")));
                    Throwable refused = catchThrowable(() ->
                            await(endpoint.post(WorkflowSignalRequest.instance(id, name, "payload", "service-key"))));
                    assertThat(refused)
                            .hasRootCauseInstanceOf(
                                    dev.vertique.workflow.exception.WorkflowInvalidPayloadException.class);
                } finally {
                    await(handle.shutdown());
                }
            } finally {
                await(pool.close());
                await(vertx.close());
            }
        });
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(45, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("future failed", e);
        }
    }
}
