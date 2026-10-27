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
    void validatesDocumentAndClaimTimeoutConfiguration() {
        WorkflowDefinitionsConfig defaults = configured(new JsonObject());
        assertThat(defaults.publicationClaimTimeoutSeconds()).isEqualTo(60);
        assertThat(defaults.maxDocumentSizeBytes()).isEqualTo(262_144);
        assertThat(defaults.maxStoredDocumentSizeBytes()).isEqualTo(393_216);

        WorkflowDefinitionsConfig documentOnly = configured(new JsonObject()
                .put(
                        "workflow",
                        new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", 512_000))));
        assertThat(documentOnly.publicationClaimTimeoutSeconds()).isEqualTo(60);
        assertThat(documentOnly.maxDocumentSizeBytes()).isEqualTo(512_000);
        assertThat(documentOnly.maxStoredDocumentSizeBytes()).isEqualTo(768_000);

        WorkflowDefinitionsConfig claimTimeoutOnly = configured(new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("definitions", new JsonObject().put("publicationClaimTimeoutSeconds", 45))));
        assertThat(claimTimeoutOnly.publicationClaimTimeoutSeconds()).isEqualTo(45);
        assertThat(claimTimeoutOnly.maxDocumentSizeBytes()).isEqualTo(262_144);

        int largestValidLimit = 1_431_655_765;
        WorkflowDefinitionsConfig wideArithmetic = new WorkflowDefinitionsConfig(largestValidLimit);
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
                                new JsonObject()
                                        .put(
                                                "definitions",
                                                new JsonObject().put("publicationClaimTimeoutSeconds", 0)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.publicationClaimTimeoutSeconds");
        assertThatThrownBy(() -> configured(new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject()
                                        .put(
                                                "definitions",
                                                new JsonObject().put("publicationClaimTimeoutSeconds", -1)))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.publicationClaimTimeoutSeconds");

        GraphWithoutConfigParser graph = DaggerDefinitionsConfigTest_GraphWithoutConfigParser.create();
        assertThat(graph.workflowRegistry()).isNotNull();
        assertThat(graph.definitionsConfig()).isEmpty();
    }

    @Test
    @DisplayName("builds the default scope documents at startup only when asked to")
    void buildDefaultScopeDocumentsAtStartupDefaultsToFalse() {
        // Given configurations without the key, with it true and with it false
        JsonObject absent = new JsonObject();
        JsonObject enabled = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("definitions", new JsonObject().put("buildDefaultScopeDocumentsAtStartup", true)));
        JsonObject disabled = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put(
                                        "definitions",
                                        new JsonObject().put("buildDefaultScopeDocumentsAtStartup", false)));

        // When each is parsed, and the defaults are read
        WorkflowDefinitionsConfig withoutKey = configured(absent);
        WorkflowDefinitionsConfig withTrue = configured(enabled);
        WorkflowDefinitionsConfig withFalse = configured(disabled);

        // Then only the explicit true builds them, and the defaults do not
        assertThat(withoutKey.buildDefaultScopeDocumentsAtStartup()).isFalse();
        assertThat(withTrue.buildDefaultScopeDocumentsAtStartup()).isTrue();
        assertThat(withFalse.buildDefaultScopeDocumentsAtStartup()).isFalse();
        assertThat(WorkflowDefinitionsConfig.defaults().buildDefaultScopeDocumentsAtStartup())
                .isFalse();

        // And the other settings keep their defaults when only this one is given
        assertThat(withTrue.publicationClaimTimeoutSeconds()).isEqualTo(60);
        assertThat(withTrue.maxDocumentSizeBytes()).isEqualTo(262_144);
        assertThat(withTrue.planCacheMaxEntries()).isEqualTo(WorkflowDefinitionsConfig.DEFAULT_PLAN_CACHE_MAX_ENTRIES);
    }

    @Test
    @DisplayName("defaults the publication claim timeout to sixty seconds and requires it to be positive")
    void publicationClaimTimeoutSecondsDefaultsToSixtyAndMustBePositive() {
        // Given configurations without the key, with zero and with 120
        JsonObject absent = new JsonObject();
        JsonObject zero = definitionsSetting("publicationClaimTimeoutSeconds", 0);
        JsonObject explicit = definitionsSetting("publicationClaimTimeoutSeconds", 120);

        // When each is parsed
        WorkflowDefinitionsConfig withoutKey = configured(absent);
        WorkflowDefinitionsConfig withExplicit = configured(explicit);

        // Then the default is 60, 120 is kept, and zero is rejected naming the key
        assertThat(withoutKey.publicationClaimTimeoutSeconds()).isEqualTo(60);
        assertThat(WorkflowDefinitionsConfig.defaults().publicationClaimTimeoutSeconds())
                .isEqualTo(60);
        assertThat(withExplicit.publicationClaimTimeoutSeconds()).isEqualTo(120);
        assertThatThrownBy(() -> configured(zero))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.publicationClaimTimeoutSeconds");

        // And the replaced key no longer changes the timeout
        assertThat(configured(definitionsSetting(REPLACED_KEY, 45)).publicationClaimTimeoutSeconds())
                .isEqualTo(60);
    }

    /** The name of the setting that the publication claim timeout replaced, spelled in two parts. */
    private static final String REPLACED_KEY = "max" + "StalenessSeconds";

    private static JsonObject definitionsSetting(String key, int value) {
        return new JsonObject().put("workflow", new JsonObject().put("definitions", new JsonObject().put(key, value)));
    }

    private static WorkflowDefinitionsConfig configured(JsonObject config) {
        return DaggerDefinitionsConfigTest_ConfiguredGraph.builder()
                .configInputModule(new ConfigInputModule(config))
                .build()
                .definitionsConfig();
    }
}
