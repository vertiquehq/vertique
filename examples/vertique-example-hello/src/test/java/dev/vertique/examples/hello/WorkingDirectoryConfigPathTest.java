// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.ConfigBootstrap;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the supported filesystem config path for this example (vertique-dev#19).
 *
 * <p>Config lives at the module root ({@code config/application.json}), not under
 * {@code src/main/resources/config}. Surefire uses the module basedir as cwd. The documented
 * reactor-root {@code exec:java} launch keeps Maven's cwd, so it must set
 * {@code VERTX_CONFIG_LOCATIONS} to this module's {@code config/} directory — this test proves that
 * pointing a directory store at that absolute path loads a recognizable shipped value.
 */
class WorkingDirectoryConfigPathTest {

    @Test
    @DisplayName("module-root config/application.json exists for the filesystem loader")
    void projectRootConfigFileIsPresent() {
        assertTrue(
                Files.isRegularFile(Path.of("config/application.json")),
                "examples must ship config/application.json at the module root so ConfigBootstrap "
                        + "can load it via VERTX_CONFIG_LOCATIONS (reactor-root exec:java) or a "
                        + "module-basedir working directory");
    }

    @Test
    @DisplayName("ConfigBootstrap default directory stores target the relative config/ path")
    void defaultOptionsTargetWorkingDirectoryConfig() {
        var stores = ConfigBootstrap.defaultOptions().getStores();
        assertEquals("config", stores.get(0).getConfig().getString("path"));
        assertEquals("config", stores.get(1).getConfig().getString("path"));
    }

    @Test
    @DisplayName("directory store at module config/ loads the shipped jaxrs.validationStrategy")
    void loadsRecognizableValueFromModuleConfigDirectory() throws Exception {
        Path configDir = Path.of("config").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(configDir), "module config/ directory must exist");

        ConfigStoreOptions jsonDir = new ConfigStoreOptions()
                .setType("directory")
                .setOptional(false)
                .setConfig(new JsonObject()
                        .put("path", configDir.toString())
                        .put(
                                "filesets",
                                new JsonArray()
                                        .add(new JsonObject()
                                                .put("pattern", "*.json")
                                                .put("format", "json"))));

        Vertx vertx = Vertx.vertx();
        try {
            ConfigRetriever retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(jsonDir));
            JsonObject config = retriever
                    .getConfig()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertEquals(
                    "openapi-contract",
                    config.getJsonObject("jaxrs").getString("validationStrategy"),
                    "absolute VERTX_CONFIG_LOCATIONS-style path must load this example's shipped config");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
