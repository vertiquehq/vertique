// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.db.flyway.FlywayMode;
import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Set;

/** Builders for the typed configuration objects the node tests share. */
final class TestConfigs {

    private TestConfigs() {}

    static DbPoolConfig db(String applicationName) {
        DbPoolConfig.DbPoolConfigBuilder builder = DbPoolConfig.builder()
                .host("localhost")
                .port(5432)
                .database("d")
                .user("u")
                .password("never-printed");
        if (applicationName != null) {
            builder.properties(Map.of("application_name", applicationName));
        }
        return builder.maxPoolSize(20).build();
    }

    static FlywayConfig flyway(FlywayMode mode) {
        return FlywayConfig.builder().mode(mode).build();
    }

    static EffectiveConfig effective(
            Set<WorkflowExecutorObserver> executorObservers, Set<OutboxPublishObserver> publishObservers) {
        return new EffectiveConfig(
                LoadConfig.defaults(),
                WorkflowExecutorConfig.defaults(),
                WorkflowRecordingConfig.defaults(),
                OutboxRelayConfig.builder().build(),
                db("load-node-1"),
                flyway(FlywayMode.DISABLED),
                executorObservers,
                publishObservers,
                new JsonObject()
                        .put("vertx", new JsonObject().put("options", new JsonObject().put("eventLoopPoolSize", 3))),
                org.mockito.Mockito.mock(io.vertx.core.Vertx.class));
    }

    static EffectiveConfig effective(io.vertx.core.Vertx vertx) {
        return new EffectiveConfig(
                LoadConfig.defaults(),
                WorkflowExecutorConfig.defaults(),
                WorkflowRecordingConfig.defaults(),
                OutboxRelayConfig.builder().build(),
                db("load-node-1"),
                flyway(FlywayMode.DISABLED),
                Set.of(),
                Set.of(),
                new JsonObject(),
                vertx);
    }

    static EffectiveConfig effective() {
        return effective(Set.of(), Set.of());
    }
}
