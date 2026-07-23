// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ConfigBootstrap}.
 *
 * <p>Covers the async loading path, deployment config merge semantics, and
 * the usability of the returned {@link io.vertx.config.ConfigRetriever}.
 *
 * <p>Suppresses deprecation warnings because these tests deliberately exercise the legacy
 * {@code load} path that is kept for the {@code MainVerticle} bootstrap pattern.
 */
@SuppressWarnings("deprecation")
@ExtendWith(VertxExtension.class)
class ConfigBootstrapTest {

    // --- Load with default options ---

    @Nested
    @DisplayName("Default options")
    class DefaultOptionsTests {

        @Test
        @DisplayName("Should load successfully and return non-null result")
        void shouldLoadWithDefaultOptions(Vertx vertx, VertxTestContext testContext) {
            ConfigBootstrap.load(vertx, new JsonObject()).onComplete(testContext.succeeding(result -> {
                assertNotNull(result, "Result must not be null");
                assertNotNull(result.config(), "Config must not be null");
                assertNotNull(result.retriever(), "Retriever must not be null");
                testContext.completeNow();
            }));
        }
    }

    // --- Merge semantics ---

    @Nested
    @DisplayName("Merge semantics")
    class MergeTests {

        @Test
        @DisplayName("Deployment config wins over retriever config for same key")
        void shouldMergeDeploymentConfigOverRetrievedConfig(Vertx vertx, VertxTestContext testContext) {
            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(new ConfigStoreOptions()
                            .setType("json")
                            .setConfig(new JsonObject()
                                    .put("key", "from-retriever")
                                    .put("only-retriever", "yes")));

            JsonObject deploymentConfig =
                    new JsonObject().put("key", "from-deployment").put("only-deployment", "yes");

            ConfigBootstrap.load(vertx, deploymentConfig, options).onComplete(testContext.succeeding(result -> {
                JsonObject config = result.config();
                assertEquals(
                        "from-deployment",
                        config.getString("key"),
                        "Deployment config must win over retriever for overlapping keys");
                assertEquals(
                        "yes", config.getString("only-retriever"), "Keys present only in retriever must be preserved");
                assertEquals(
                        "yes",
                        config.getString("only-deployment"),
                        "Keys present only in deployment config must be present");
                testContext.completeNow();
            }));
        }

        @Test
        @DisplayName("Empty deployment config preserves retriever values")
        void shouldHandleEmptyDeploymentConfig(Vertx vertx, VertxTestContext testContext) {
            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(new ConfigStoreOptions().setType("json").setConfig(new JsonObject().put("key", "value")));

            ConfigBootstrap.load(vertx, new JsonObject(), options).onComplete(testContext.succeeding(result -> {
                assertEquals(
                        "value",
                        result.config().getString("key"),
                        "Retriever value must appear in config when deployment config is empty");
                testContext.completeNow();
            }));
        }
    }

    // --- Retriever usability ---

    @Nested
    @DisplayName("Retriever usability")
    class RetrieverTests {

        @Test
        @DisplayName("Returned retriever should have a non-null cached config")
        void shouldReturnUsableConfigRetriever(Vertx vertx, VertxTestContext testContext) {
            ConfigBootstrap.load(vertx, new JsonObject()).onComplete(testContext.succeeding(result -> {
                assertNotNull(
                        result.retriever().getCachedConfig(),
                        "getCachedConfig() must return a non-null JsonObject after initial load");
                testContext.completeNow();
            }));
        }
    }

    // --- Custom options ---

    @Nested
    @DisplayName("Custom options")
    class CustomOptionsTests {

        @Test
        @DisplayName("Custom options are applied and their values appear in the config")
        void shouldUseCustomOptions(Vertx vertx, VertxTestContext testContext) {
            ConfigRetrieverOptions options = new ConfigRetrieverOptions()
                    .addStore(new ConfigStoreOptions().setType("json").setConfig(new JsonObject().put("custom", true)));

            ConfigBootstrap.load(vertx, new JsonObject(), options).onComplete(testContext.succeeding(result -> {
                assertTrue(
                        result.config().getBoolean("custom"), "Value from custom store must appear in loaded config");
                testContext.completeNow();
            }));
        }
    }
}
