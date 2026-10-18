// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the public limits and optional generated configuration graph for durable documents. */
class DefinitionsConfigTest {

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, ValidatorModule.class})
    interface GraphWithoutConfigParser {

        WorkflowRegistry workflowRegistry();

        java.util.Optional<WorkflowDefinitionsConfig> definitionsConfig();
    }

    @Singleton
    @Component(modules = {ConfigParsingModule.class, WorkflowDefinitionsConfigModule.class, ConfigInputModule.class})
    interface ConfiguredGraph {

        WorkflowDefinitionsConfig definitionsConfig();
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

    @Module
    static class ValidatorModule {

        @Provides
        @Singleton
        static WorkflowPlanValidator workflowPlanValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(java.util.Set.of()));
        }
    }

    @Test
    @DisplayName("validates independent defaults, partial overrides, wide envelope math, and optional parser wiring")
    void validatesDocumentAndCadenceConfiguration() {
        WorkflowDefinitionsConfig defaults = configured(new JsonObject());
        assertThat(defaults.maxStalenessSeconds()).isEqualTo(30);
        assertThat(defaults.maxDocumentSizeBytes()).isEqualTo(262_144);
        assertThat(defaults.maxStoredDocumentSizeBytes()).isEqualTo(393_216);

        WorkflowDefinitionsConfig documentOnly = configured(new JsonObject()
                .put(
                        "workflow",
                        new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", 512_000))));
        assertThat(documentOnly.maxStalenessSeconds()).isEqualTo(30);
        assertThat(documentOnly.maxDocumentSizeBytes()).isEqualTo(512_000);
        assertThat(documentOnly.maxStoredDocumentSizeBytes()).isEqualTo(768_000);

        WorkflowDefinitionsConfig cadenceOnly = configured(new JsonObject()
                .put("workflow", new JsonObject().put("definitions", new JsonObject().put("maxStalenessSeconds", 45))));
        assertThat(cadenceOnly.maxStalenessSeconds()).isEqualTo(45);
        assertThat(cadenceOnly.maxDocumentSizeBytes()).isEqualTo(262_144);

        int largestValidLimit = 1_431_655_765;
        WorkflowDefinitionsConfig wideArithmetic = new WorkflowDefinitionsConfig(30, largestValidLimit);
        assertThat(wideArithmetic.maxStoredDocumentSizeBytes()).isEqualTo(Integer.MAX_VALUE);

        assertThatThrownBy(() -> configured(new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", 0)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.maxDocumentSizeBytes");
        assertThatThrownBy(() -> configured(new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", -1)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.maxDocumentSizeBytes");
        assertThatThrownBy(() -> configured(new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject()
                                        .put(
                                                "definitions",
                                                new JsonObject().put("maxDocumentSizeBytes", largestValidLimit + 1)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.maxDocumentSizeBytes");
        assertThatThrownBy(() -> configured(new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject().put("definitions", new JsonObject().put("maxStalenessSeconds", 0)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.maxStalenessSeconds");
        assertThatThrownBy(() -> configured(new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject().put("definitions", new JsonObject().put("maxStalenessSeconds", -1)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.maxStalenessSeconds");

        GraphWithoutConfigParser graph = DaggerDefinitionsConfigTest_GraphWithoutConfigParser.create();
        assertThat(graph.workflowRegistry()).isNotNull();
        assertThat(graph.definitionsConfig()).isEmpty();
    }

    private static WorkflowDefinitionsConfig configured(JsonObject config) {
        return DaggerDefinitionsConfigTest_ConfiguredGraph.builder()
                .configInputModule(new ConfigInputModule(config))
                .build()
                .definitionsConfig();
    }
}
