// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the defaults and bounds of the executor's instances, concurrency, poll interval and lease. */
class WorkflowExecutorConfigTest {

    @Singleton
    @Component(modules = {ConfigParsingModule.class, WorkflowExecutorConfigModule.class, ConfigInputModule.class})
    interface ConfiguredGraph {

        WorkflowExecutorConfig executorConfig();
    }

    @Module
    static class ConfigInputModule {

        private final JsonObject config;

        ConfigInputModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @VertxConfig
        JsonObject config() {
            return config;
        }
    }

    @Test
    @DisplayName("defaults and bounds of instances, concurrency, poll interval and lease")
    void defaultsAndBoundsOfInstancesConcurrencyPollAndLease() {
        // Given no workflow.executor section and a section with valid values at the edges of the bounds
        JsonObject absent = new JsonObject();
        JsonObject lowest = executor(1, 1, 100, 5);
        JsonObject highest = executor(64, 256, 3_600_000, 3_600);

        // When each is parsed through the config module
        WorkflowExecutorConfig defaults = configured(absent);
        WorkflowExecutorConfig low = configured(lowest);
        WorkflowExecutorConfig high = configured(highest);

        // Then the defaults apply and the valid values are kept
        assertThat(defaults.instances()).isEqualTo(1);
        assertThat(defaults.concurrency()).isEqualTo(8);
        assertThat(defaults.pollIntervalMs()).isEqualTo(5000L);
        assertThat(defaults.leaseSeconds()).isEqualTo(60);
        assertThat(low.instances()).isEqualTo(1);
        assertThat(low.concurrency()).isEqualTo(1);
        assertThat(low.pollIntervalMs()).isEqualTo(100L);
        assertThat(low.leaseSeconds()).isEqualTo(5);
        assertThat(high.instances()).isEqualTo(64);
        assertThat(high.concurrency()).isEqualTo(256);
        assertThat(high.pollIntervalMs()).isEqualTo(3_600_000L);
        assertThat(high.leaseSeconds()).isEqualTo(3_600);
        assertThat(WorkflowExecutorConfig.defaults()).isEqualTo(defaults);

        // And each invalid value is refused, naming its key
        assertRefused(executor(0, 8, 5000, 60), "workflow.executor.instances");
        assertRefused(executor(65, 8, 5000, 60), "workflow.executor.instances");
        assertRefused(executor(1, 0, 5000, 60), "workflow.executor.concurrency");
        assertRefused(executor(1, 257, 5000, 60), "workflow.executor.concurrency");
        assertRefused(executor(1, 8, 99, 60), "workflow.executor.pollIntervalMs");
        assertRefused(executor(1, 8, 5000, 4), "workflow.executor.leaseSeconds");
    }

    private static void assertRefused(JsonObject config, String key) {
        assertThatThrownBy(() -> configured(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(key);
    }

    private static JsonObject executor(int instances, int concurrency, long pollIntervalMs, int leaseSeconds) {
        return new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put(
                                        "executor",
                                        new JsonObject()
                                                .put("instances", instances)
                                                .put("concurrency", concurrency)
                                                .put("pollIntervalMs", pollIntervalMs)
                                                .put("leaseSeconds", leaseSeconds)));
    }

    private static WorkflowExecutorConfig configured(JsonObject config) {
        return DaggerWorkflowExecutorConfigTest_ConfiguredGraph.builder()
                .configInputModule(new ConfigInputModule(config))
                .build()
                .executorConfig();
    }
}
