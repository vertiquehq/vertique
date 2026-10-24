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
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the default and the bound of the time a recording caller waits for an instance's execution lock. */
class WorkflowRecordingConfigTest {

    @Singleton
    @Component(modules = {ConfigParsingModule.class, WorkflowRecordingConfigModule.class, ConfigInputModule.class})
    interface ConfiguredGraph {

        WorkflowRecordingConfig recordingConfig();
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
    @DisplayName("defaults to five seconds and rejects non-positive timeouts")
    void defaultsToFiveSecondsAndRejectsNonPositiveTimeouts() {
        // Given no workflow.recording section, one with 250 and one with 0
        JsonObject absent = new JsonObject();
        JsonObject explicit = withLockTimeoutMs(250);
        JsonObject zero = withLockTimeoutMs(0);

        // When each is parsed through the config module
        // Then the defaults apply, the explicit value is kept, and zero names the offending key
        assertThat(configured(absent).lockTimeoutMs()).isEqualTo(5000L);
        assertThat(configured(explicit).lockTimeoutMs()).isEqualTo(250L);
        assertThatThrownBy(() -> configured(zero))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.lockTimeoutMs");
    }

    private static JsonObject withLockTimeoutMs(long value) {
        return new JsonObject()
                .put("workflow", new JsonObject().put("recording", new JsonObject().put("lockTimeoutMs", value)));
    }

    private static WorkflowRecordingConfig configured(JsonObject config) {
        return DaggerWorkflowRecordingConfigTest_ConfiguredGraph.builder()
                .configInputModule(new ConfigInputModule(config))
                .build()
                .recordingConfig();
    }
}
