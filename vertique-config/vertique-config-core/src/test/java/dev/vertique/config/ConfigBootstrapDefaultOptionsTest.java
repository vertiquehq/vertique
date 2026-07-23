// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the default {@link ConfigRetrieverOptions} returned by
 * {@link ConfigBootstrap#defaultOptions()}.
 *
 * <p>These are pure synchronous tests — no Vert.x instance required.
 * They validate the store count, ordering, and per-store configuration
 * without performing any I/O.
 */
class ConfigBootstrapDefaultOptionsTest {

    @Test
    @DisplayName("Default options should contain exactly four stores")
    void shouldContainFourStores() {
        ConfigRetrieverOptions options = ConfigBootstrap.defaultOptions();
        assertEquals(4, options.getStores().size(), "Expected four default stores: json-dir, properties-dir, env, sys");
    }

    @Test
    @DisplayName("First store should be directory store for *.json in config/")
    void shouldHaveJsonDirectoryStoreFirst() {
        List<ConfigStoreOptions> stores = ConfigBootstrap.defaultOptions().getStores();
        ConfigStoreOptions first = stores.get(0);

        assertEquals("directory", first.getType(), "First store must be a directory store");
        assertTrue(first.isOptional(), "Directory store must be optional");
        assertEquals("config", first.getConfig().getString("path"), "Directory store path must be 'config'");

        JsonArray filesets = first.getConfig().getJsonArray("filesets");
        assertNotNull(filesets, "Directory store must have filesets");
        assertEquals(1, filesets.size(), "Must have exactly one fileset");

        JsonObject fileset = filesets.getJsonObject(0);
        assertEquals("*.json", fileset.getString("pattern"), "Pattern must be *.json");
        assertEquals("json", fileset.getString("format"), "Format must be json");
    }

    @Test
    @DisplayName("Second store should be directory store for *.properties in config/ with hierarchical keys")
    void shouldHavePropertiesDirectoryStoreSecond() {
        List<ConfigStoreOptions> stores = ConfigBootstrap.defaultOptions().getStores();
        ConfigStoreOptions second = stores.get(1);

        assertEquals("directory", second.getType(), "Second store must be a directory store");
        assertTrue(second.isOptional(), "Directory store must be optional");
        assertEquals("config", second.getConfig().getString("path"), "Directory store path must be 'config'");

        JsonArray filesets = second.getConfig().getJsonArray("filesets");
        assertNotNull(filesets, "Directory store must have filesets");
        assertEquals(1, filesets.size(), "Must have exactly one fileset");

        JsonObject fileset = filesets.getJsonObject(0);
        assertEquals("*.properties", fileset.getString("pattern"), "Pattern must be *.properties");
        assertEquals("properties", fileset.getString("format"), "Format must be properties");
        assertTrue(fileset.getBoolean("hierarchical"), "Properties fileset must enable hierarchical keys");
    }

    @Test
    @DisplayName("Third store should be env store")
    void shouldHaveEnvStoreThird() {
        List<ConfigStoreOptions> stores = ConfigBootstrap.defaultOptions().getStores();
        ConfigStoreOptions third = stores.get(2);

        assertEquals("env", third.getType(), "Third store must be an environment variable store");
    }

    @Test
    @DisplayName("Fourth store should be sys store")
    void shouldHaveSysStoreFourth() {
        List<ConfigStoreOptions> stores = ConfigBootstrap.defaultOptions().getStores();
        ConfigStoreOptions fourth = stores.get(3);

        assertEquals("sys", fourth.getType(), "Fourth store must be a system properties store");
    }

    @Test
    @DisplayName("Constants should have expected values")
    void shouldVerifyConstants() {
        assertEquals(
                "VERTX_CONFIG_LOCATIONS",
                ConfigBootstrap.CONFIG_LOCATIONS_ENV,
                "CONFIG_LOCATIONS_ENV must be VERTX_CONFIG_LOCATIONS");
        assertEquals("config", ConfigBootstrap.DEFAULT_CONFIG_DIR, "DEFAULT_CONFIG_DIR must be config");
    }

    // --- defaultOptions(List<ConfigStoreOptions>) overload ---

    @Nested
    @DisplayName("defaultOptions(List) overload")
    class DeclaredStoresOverloadTests {

        @Test
        @DisplayName(
                "Empty declared stores list returns the same chain as no-arg defaultOptions(); no-arg delegates to empty-list overload")
        void shouldReturnSameChainForEmptyList() {
            List<ConfigStoreOptions> stores =
                    ConfigBootstrap.defaultOptions(List.of()).getStores();

            assertEquals(4, stores.size(), "Empty declared list must produce exactly 4 stores");
            assertEquals("directory", stores.get(0).getType(), "Store[0] must be json directory");
            assertEquals("directory", stores.get(1).getType(), "Store[1] must be properties directory");
            assertEquals("env", stores.get(2).getType(), "Store[2] must be env");
            assertEquals("sys", stores.get(3).getType(), "Store[3] must be sys");

            // Verify json dir config matches no-arg output exactly (proves no-arg delegates correctly)
            List<ConfigStoreOptions> noArgStores =
                    ConfigBootstrap.defaultOptions().getStores();
            assertEquals(noArgStores.size(), stores.size(), "Store count must be identical");
            for (int i = 0; i < noArgStores.size(); i++) {
                assertEquals(
                        noArgStores.get(i).getType(),
                        stores.get(i).getType(),
                        "Store[" + i + "] type must match between no-arg and empty-list overload");
            }
            assertEquals(
                    noArgStores.get(0).getConfig().getString("path"),
                    stores.get(0).getConfig().getString("path"),
                    "Directory store path must match between no-arg and empty-list overload");
        }

        @Test
        @DisplayName("Two declared stores are inserted after directory stores and before env store")
        void shouldInsertDeclaredStoresBetweenDirStoresAndEnvStore() {
            ConfigStoreOptions declared1 =
                    new ConfigStoreOptions().setType("json").setConfig(new JsonObject().put("key", "declared1"));
            ConfigStoreOptions declared2 =
                    new ConfigStoreOptions().setType("json").setConfig(new JsonObject().put("key", "declared2"));

            List<ConfigStoreOptions> stores = ConfigBootstrap.defaultOptions(List.of(declared1, declared2))
                    .getStores();

            // 2 dir stores + 2 declared + env + sys = 6 total
            assertEquals(6, stores.size(), "Must have 6 stores: 2 dir + 2 declared + env + sys");
            assertEquals("directory", stores.get(0).getType(), "Store[0] must be json directory");
            assertEquals("directory", stores.get(1).getType(), "Store[1] must be properties directory");
            assertEquals("json", stores.get(2).getType(), "Store[2] must be first declared store");
            assertEquals(
                    "declared1",
                    stores.get(2).getConfig().getString("key"),
                    "Store[2] must be first declared store (order preserved)");
            assertEquals("json", stores.get(3).getType(), "Store[3] must be second declared store");
            assertEquals(
                    "declared2",
                    stores.get(3).getConfig().getString("key"),
                    "Store[3] must be second declared store (order preserved)");
            assertEquals("env", stores.get(4).getType(), "Store[4] must be env");
            assertEquals("sys", stores.get(5).getType(), "Store[5] must be sys");
        }
    }
}
