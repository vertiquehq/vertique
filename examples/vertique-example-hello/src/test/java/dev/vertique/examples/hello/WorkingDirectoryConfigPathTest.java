// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.ConfigBootstrap;
import io.vertx.config.ConfigStoreOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the documented {@code mvn exec:java} config path for this example: ConfigBootstrap
 * reads the project-root {@code config/} directory (not a classpath copy under
 * {@code src/main/resources/config}), matching the archetype working-directory contract
 * (vertique-dev#19).
 *
 * <p>Surefire runs with the module basedir as the working directory and with
 * {@code VERTX_CONFIG_LOCATIONS} neutralized blank by vertique-parent (vertique-dev#20).
 */
class WorkingDirectoryConfigPathTest {

    @Test
    @DisplayName("project-root config/application.json exists for the exec:java working-directory loader")
    void projectRootConfigFileIsPresent() {
        assertTrue(
                Files.isRegularFile(Path.of("config/application.json")),
                "examples must ship config/application.json at the module root so ConfigBootstrap "
                        + "finds it when mvn exec:java uses the basedir as cwd");
    }

    @Test
    @DisplayName("ConfigBootstrap default directory stores target the working-directory config/ path")
    void defaultOptionsTargetWorkingDirectoryConfig() {
        List<ConfigStoreOptions> stores = ConfigBootstrap.defaultOptions().getStores();
        assertEquals("config", stores.get(0).getConfig().getString("path"));
        assertEquals("config", stores.get(1).getConfig().getString("path"));
    }
}
