// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dev.vertique.cache.config.ObjectCacheConfig;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.workflow.engine.registry.PlanCache;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The plan cache's size and expiry as read from {@code workflow.definitions.cache} through the real parser. */
class WorkflowEnginePlanCacheSettingsTest {

    @Singleton
    @Component(modules = ConfigParsingModule.class)
    interface ParserGraph {

        ConfigParser parser();
    }

    private static ObjectCacheConfig read(JsonObject workflowDefinitions) {
        JsonObject config = new JsonObject().put("workflow", new JsonObject().put("definitions", workflowDefinitions));
        return WorkflowEngineModule.planCacheConfig(
                config,
                DaggerWorkflowEnginePlanCacheSettingsTest_ParserGraph.create().parser());
    }

    @Test
    @DisplayName("without a block the plan cache holds 500 plans and never expires them")
    void absentBlockGivesTheDefaults() {
        assertThat(read(new JsonObject())).isEqualTo(new ObjectCacheConfig(500, 0));
        assertThat(PlanCache.DEFAULT_CONFIG).isEqualTo(new ObjectCacheConfig(500, 0));
    }

    @Test
    @DisplayName("a block with only ttlSeconds keeps the default size")
    void onlyTtlKeepsTheDefaultSize() {
        JsonObject block = new JsonObject().put("cache", new JsonObject().put("ttlSeconds", 90));

        assertThat(read(block)).isEqualTo(new ObjectCacheConfig(500, 90));
    }

    @Test
    @DisplayName("a block with only maximumEntries keeps the default of no expiry")
    void onlyMaximumEntriesKeepsNoExpiry() {
        JsonObject block = new JsonObject().put("cache", new JsonObject().put("maximumEntries", 12));

        assertThat(read(block)).isEqualTo(new ObjectCacheConfig(12, 0));
    }

    @Test
    @DisplayName("explicit maximumEntries and ttlSeconds size and expire the plan cache")
    void explicitValuesAreUsed() {
        JsonObject block = new JsonObject()
                .put("cache", new JsonObject().put("maximumEntries", 12).put("ttlSeconds", 90));

        assertThat(read(block)).isEqualTo(new ObjectCacheConfig(12, 90));
    }

    @Test
    @DisplayName("an out-of-range value fails with a ConfigurationException")
    void invalidValuesAreRefused() {
        assertThatThrownBy(() -> read(new JsonObject().put("cache", new JsonObject().put("maximumEntries", 0))))
                .isInstanceOf(ConfigurationException.class);
        assertThatThrownBy(() -> read(new JsonObject().put("cache", new JsonObject().put("ttlSeconds", -1))))
                .isInstanceOf(ConfigurationException.class);
    }
}
