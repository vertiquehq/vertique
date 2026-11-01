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
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.exception.WorkflowUnavailableException;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code workflow.recording.intake} configuration: its defaults, its keys, and the failures that name the
 * key a value breaks, and the refusal type that the limit fails requests with.
 */
class WorkflowIntakeConfigTest {

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
    @DisplayName("is on by default with a high mark of 10000, a low mark of 5000 and a sample interval of one second")
    void defaults() {
        WorkflowIntakeConfig absent = configured(new JsonObject()).intake();
        WorkflowIntakeConfig partial =
                configured(intake(new JsonObject().put("highMark", 20000))).intake();

        assertThat(absent).isEqualTo(new WorkflowIntakeConfig(true, 10_000, 5_000, 1_000L));
        assertThat(absent).isEqualTo(WorkflowIntakeConfig.defaults());
        assertThat(WorkflowRecordingConfig.defaults().intake()).isEqualTo(absent);
        assertThat(partial).isEqualTo(new WorkflowIntakeConfig(true, 20_000, 5_000, 1_000L));
    }

    @Test
    @DisplayName("reads every key")
    void readsEveryKey() {
        WorkflowIntakeConfig parsed = configured(intake(new JsonObject()
                        .put("enabled", false)
                        .put("highMark", 50)
                        .put("lowMark", 10)
                        .put("sampleIntervalMs", 250)))
                .intake();

        assertThat(parsed).isEqualTo(new WorkflowIntakeConfig(false, 50, 10, 250L));
    }

    @Test
    @DisplayName("fails naming the key when the low mark is not below the high mark or is below one")
    void rejectsInvertedMarks() {
        JsonObject equal = intake(new JsonObject().put("highMark", 100).put("lowMark", 100));
        JsonObject above = intake(new JsonObject().put("highMark", 100).put("lowMark", 200));
        JsonObject zero = intake(new JsonObject().put("highMark", 100).put("lowMark", 0));
        JsonObject lowOnly = intake(new JsonObject().put("lowMark", 10_000));

        assertThatThrownBy(() -> configured(equal))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.intake.lowMark");
        assertThatThrownBy(() -> configured(above))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.intake.lowMark");
        assertThatThrownBy(() -> configured(zero))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.intake.lowMark");
        assertThatThrownBy(() -> configured(lowOnly))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.intake.lowMark");
    }

    @Test
    @DisplayName("fails naming the key when the sample interval is below 100 milliseconds, and accepts 100")
    void rejectsTooShortAnInterval() {
        JsonObject tooShort = intake(new JsonObject().put("sampleIntervalMs", 99));
        JsonObject shortest = intake(new JsonObject().put("sampleIntervalMs", 100));

        assertThatThrownBy(() -> configured(tooShort))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.intake.sampleIntervalMs");
        assertThat(configured(shortest).intake().sampleIntervalMs()).isEqualTo(100L);
    }

    @Test
    @DisplayName("keeps the marks of a disabled limit valid, so that turning it on needs no other change")
    void validatesADisabledLimitToo() {
        JsonObject disabled = intake(new JsonObject().put("enabled", false).put("lowMark", 20_000));

        assertThatThrownBy(() -> configured(disabled))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.recording.intake.lowMark");
    }

    @Test
    @DisplayName("a recording configuration built without the intake keys carries the default limit")
    void olderConstructorsCarryTheDefault() {
        assertThat(new WorkflowRecordingConfig(5000L, 1024, 10, 8).intake()).isEqualTo(WorkflowIntakeConfig.defaults());
        assertThat(new WorkflowRecordingConfig(5000L).intake()).isEqualTo(WorkflowIntakeConfig.defaults());
    }

    @Test
    @DisplayName("a null intake component means the default limit")
    void nullIntakeMeansTheDefault() {
        assertThat(new WorkflowRecordingConfig(5000L, 1024, 10, 8, null).intake())
                .isEqualTo(WorkflowIntakeConfig.defaults());
    }

    @Test
    @DisplayName("the refusal is a retryable unavailable failure that names no request")
    void refusalIsUnavailable() {
        WorkflowIntakeRefusedException refusal = new WorkflowIntakeRefusedException();

        assertThat(refusal).isInstanceOf(WorkflowUnavailableException.class);
        assertThat(refusal.getMessage()).isNotBlank();
    }

    private static JsonObject intake(JsonObject intake) {
        return new JsonObject()
                .put("workflow", new JsonObject().put("recording", new JsonObject().put("intake", intake)));
    }

    private static WorkflowRecordingConfig configured(JsonObject config) {
        return DaggerWorkflowIntakeConfigTest_ConfiguredGraph.builder()
                .configInputModule(new ConfigInputModule(config))
                .build()
                .recordingConfig();
    }
}
