// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import static dev.vertique.config.bootstrap.VertxThreadLeakAssertions.assertNoLeakedVertxThreads;
import static dev.vertique.config.bootstrap.VertxThreadLeakAssertions.liveVertxThreadNames;
import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the two-phase {@code config.stores} declaration logic in
 * {@link BootstrapConfigLoader}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Phase-2 is entirely skipped when no {@code config.stores} is declared</li>
 *   <li>Declared stores participate in the merged result</li>
 *   <li>Precedence: sys &gt; deployment &gt; declared stores &gt; files</li>
 *   <li>List order: later entry wins on key collision</li>
 *   <li>Unknown store type produces a helpful {@link BootstrapConfigException}</li>
 *   <li>A failing declared store aborts with a {@link BootstrapConfigException}</li>
 *   <li>Invalid declaration shapes produce a {@link BootstrapConfigException}</li>
 * </ul>
 *
 * <p>Uses {@link CountingTestStoreFactory} (registered via ServiceLoader SPI in test resources)
 * to provide controllable stores and count create invocations.
 *
 * <p>Validation-only tests ({@link UnknownTypeTests}, {@link InvalidDeclarationTests}) call
 * {@link BootstrapConfigLoader#buildDeclaredStores} directly — no Vert.x instance required.
 * One end-to-end {@code load()} test in {@link FailingStoreTests} serves as the integration proof
 * for the abort + thread-drain path.
 */
class BootstrapConfigLoaderStoresTest {

    @BeforeEach
    void resetFactory() {
        CountingTestStoreFactory.reset();
    }

    @AfterEach
    void resetFactoryAfter() {
        CountingTestStoreFactory.reset();
    }

    // --- JSON builder helpers ---

    /**
     * Builds a deployment config object containing a {@code config.stores} array from the given
     * store entries.
     *
     * @param entries the store entry objects to include in the array
     * @return a deployment config containing the stores array
     */
    private static JsonObject deploymentWithStores(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject e : entries) {
            array.add(e);
        }
        return new JsonObject()
                .put(
                        BootstrapConfigLoader.CONFIG_SECTION,
                        new JsonObject().put(BootstrapConfigLoader.STORES_KEY, array));
    }

    /**
     * Builds a counting-test store declaration with the given payload object in its config.
     *
     * @param payload the JSON payload the store will return
     * @return a store declaration object suitable for the stores array
     */
    private static JsonObject countingStore(JsonObject payload) {
        return new JsonObject().put("type", "counting-test").put("config", new JsonObject().put("payload", payload));
    }

    // --- Phase-2 skip ---

    @Nested
    @DisplayName("Phase-2 skip when no stores declared")
    class Phase2SkipTests {

        @Test
        @DisplayName("No config.stores → phase-2 skipped; createCount stays 0")
        void shouldSkipPhase2WhenNoStoresDeclared() {
            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(new JsonObject());

            assertNotNull(result);
            assertEquals(
                    0,
                    CountingTestStoreFactory.createCount.get(),
                    "No store should be created when config.stores is absent");
        }

        @Test
        @DisplayName("Empty config.stores array → phase-2 skipped; createCount stays 0")
        void shouldSkipPhase2WhenStoresArrayEmpty() {
            JsonObject deployment = new JsonObject()
                    .put(
                            BootstrapConfigLoader.CONFIG_SECTION,
                            new JsonObject().put(BootstrapConfigLoader.STORES_KEY, new JsonArray()));

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertNotNull(result);
            assertEquals(
                    0, CountingTestStoreFactory.createCount.get(), "Empty stores array should not create any stores");
        }
    }

    // --- Declared store participates ---

    @Nested
    @DisplayName("Declared store participates in merged result")
    class DeclaredStoreParticipationTests {

        @Test
        @DisplayName("Declared counting-test store contributes its payload; createCount == 1")
        void shouldLoadDeclaredStorePayload() {
            JsonObject deployment = deploymentWithStores(countingStore(
                    new JsonObject().put("fromStore", "storeValue").put("shared", "fromStore")));

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals(
                    "storeValue",
                    result.config().getString("fromStore"),
                    "Store payload key must appear in merged result");
            assertEquals(1, CountingTestStoreFactory.createCount.get(), "Exactly one store must be created");
        }
    }

    // --- Slot precedence ---

    @Nested
    @DisplayName("Slot precedence: sys > deployment > stores > files")
    class SlotPrecedenceTests {

        private static final String SHARED_KEY = "vertique.bootstrap.storestest.shared";
        private String savedSysPropValue;

        @BeforeEach
        void setUp() {
            savedSysPropValue = System.getProperty(SHARED_KEY);
        }

        @AfterEach
        void tearDown() {
            if (savedSysPropValue != null) {
                System.setProperty(SHARED_KEY, savedSysPropValue);
            } else {
                System.clearProperty(SHARED_KEY);
            }
        }

        @Test
        @DisplayName("Sys property beats store-provided value for same key")
        void sysPropBeatsStore() {
            System.setProperty(SHARED_KEY, "from-sys");

            JsonObject deployment = deploymentWithStores(countingStore(new JsonObject().put(SHARED_KEY, "from-store")));

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals("from-sys", result.config().getString(SHARED_KEY), "Sys property must beat store value");
        }

        @Test
        @DisplayName("Deployment config beats store-provided value for same key")
        void deploymentBeatsStore() {
            // Remove any sys prop interference
            System.clearProperty(SHARED_KEY);

            JsonObject storePayload =
                    new JsonObject().put(SHARED_KEY, "from-store").put("storeOnly", "store-value");

            JsonObject deployment =
                    deploymentWithStores(countingStore(storePayload)).put(SHARED_KEY, "from-deployment");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals(
                    "from-deployment",
                    result.config().getString(SHARED_KEY),
                    "Deployment config must beat store value");
            // store-only key still survives
            assertEquals("store-value", result.config().getString("storeOnly"), "Store-only key must survive");
        }
    }

    // --- Beats files ---

    @Nested
    @DisplayName("Declared store beats file-layer value")
    class StoreBeatsFileTests {

        private Path configDir;
        private Path testFile;

        @BeforeEach
        void createConfigFile() throws IOException {
            // Surefire runs in module dir; create config/ there so the default dir loader picks it up
            configDir = Path.of("config");
            Files.createDirectories(configDir);
            testFile = configDir.resolve("bootstrap-stores-test.json");
            String fileJson = new JsonObject()
                    .put("shared", "fromFile")
                    .put("fileOnly", "yes")
                    .encode();
            Files.writeString(testFile, fileJson);
        }

        @AfterEach
        void deleteConfigFile() {
            try {
                Files.deleteIfExists(testFile);
                // Only remove the directory if we can (it may have other files)
                try (var dirStream = Files.list(configDir)) {
                    if (dirStream.findFirst().isEmpty()) {
                        Files.deleteIfExists(configDir);
                    }
                }
            } catch (IOException e) {
                // Best-effort cleanup; not worth failing the test for
            }
        }

        @Test
        @DisplayName("Store payload beats file value; file-only key survives")
        void storeBeatsFile() {
            JsonObject deployment = deploymentWithStores(countingStore(new JsonObject().put("shared", "fromStore")));

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals("fromStore", result.config().getString("shared"), "Store value must override file value");
            assertEquals("yes", result.config().getString("fileOnly"), "File-only key must survive");
        }
    }

    // --- List order ---

    @Nested
    @DisplayName("List order: later entry wins on collision")
    class ListOrderTests {

        @Test
        @DisplayName("Two counting-test entries: later payload value wins; createCount == 2")
        void laterEntryWins() {
            JsonObject deployment = deploymentWithStores(
                    countingStore(new JsonObject().put("colliding", "first").put("onlyInFirst", "yes")),
                    countingStore(new JsonObject().put("colliding", "second").put("onlyInSecond", "yes")));

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals("second", result.config().getString("colliding"), "Later entry must win on collision");
            assertEquals("yes", result.config().getString("onlyInFirst"), "First-only key must survive");
            assertEquals("yes", result.config().getString("onlyInSecond"), "Second-only key must survive");
            assertEquals(2, CountingTestStoreFactory.createCount.get(), "Both stores must be created");
        }
    }

    // --- Unknown type (direct unit tests of buildDeclaredStores — no Vertx) ---

    @Nested
    @DisplayName("Unknown store type produces helpful exception")
    class UnknownTypeTests {

        @Test
        @DisplayName("Type 'configmap' → BootstrapConfigException with configmap and kubernetes hint")
        void unknownTypeConfigmap() {
            JsonArray stores = new JsonArray().add(new JsonObject().put("type", "configmap"));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertTrue(ex.getMessage().contains("configmap"), "Exception must name the unknown type");
            assertTrue(
                    ex.getMessage().contains("io.vertx:vertx-config-kubernetes-configmap"),
                    "Exception must include the dependency hint for configmap");
        }

        @Test
        @DisplayName("Completely unknown type 'nope' → BootstrapConfigException with generic hint")
        void unknownTypeGeneric() {
            JsonArray stores = new JsonArray().add(new JsonObject().put("type", "nope"));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertTrue(ex.getMessage().contains("nope"), "Exception must name the unknown type");
            assertTrue(ex.getMessage().contains("ConfigStoreFactory"), "Exception must include generic hint");
        }

        @Test
        @DisplayName("Type 'aws-ssm' → BootstrapConfigException with aws-ssm hint")
        void unknownTypeAwsSsm() {
            JsonArray stores = new JsonArray().add(new JsonObject().put("type", "aws-ssm"));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertTrue(ex.getMessage().contains("aws-ssm"), "Exception must name the unknown type");
            assertTrue(
                    ex.getMessage().contains("dev.vertique:vertique-config-aws-ssm"),
                    "Exception must include the dependency hint for aws-ssm");
        }

        /** End-to-end proof: unknown type travels through the full {@code load()} abort path. */
        @Test
        @DisplayName("End-to-end: unknown type through load() → BootstrapConfigException")
        void unknownTypeThroughLoad() {
            String conf = "{\"config\":{\"stores\":[{\"type\":\"no-such-store\"}]}}";
            JsonObject deployment = new JsonObject(conf);

            assertThrows(
                    BootstrapConfigException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "Unknown store type must cause BootstrapConfigException through load()");
        }
    }

    // --- Invalid declaration shape (direct unit tests of buildDeclaredStores — no Vertx) ---

    @Nested
    @DisplayName("Invalid declaration shapes produce BootstrapConfigException")
    class InvalidDeclarationTests {

        @Test
        @DisplayName("Entry missing 'type' field → BootstrapConfigException naming the problem")
        void entryMissingType() {
            JsonArray stores = new JsonArray().add(new JsonObject().put("config", new JsonObject()));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertNotNull(ex.getMessage(), "Exception message must not be null");
            assertTrue(
                    ex.getMessage().toLowerCase().contains("type")
                            || ex.getMessage().contains("index"),
                    "Exception must mention 'type' or 'index': " + ex.getMessage());
        }

        @Test
        @DisplayName("Entry with blank 'type' string → BootstrapConfigException naming the problem")
        void entryBlankType() {
            JsonArray stores = new JsonArray().add(new JsonObject().put("type", "  "));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("Entry is not a JsonObject (e.g. a string) → BootstrapConfigException naming the index")
        void entryNotJsonObject() {
            JsonArray stores = new JsonArray().add("not-an-object");

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertNotNull(ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("0")
                            || ex.getMessage().toLowerCase().contains("index"),
                    "Exception must reference the offending index (0): " + ex.getMessage());
        }
    }

    // --- Null entry in arrays (item 6) ---

    @Nested
    @DisplayName("Null entry in config.stores array")
    class NullStoreEntryTests {

        @Test
        @DisplayName("null entry in config.stores[0] → BootstrapConfigException naming the index")
        void nullStoreEntryThrows() {
            // A JsonArray can hold Java null (explicit null value)
            JsonArray stores = new JsonArray();
            stores.addNull(); // stores[0] = null

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class, () -> BootstrapConfigLoader.buildDeclaredStores(stores));

            assertNotNull(ex.getMessage(), "exception message must not be null");
            // Must name either the index or the constraint
            assertTrue(
                    ex.getMessage().contains("0")
                            || ex.getMessage().toLowerCase().contains("null"),
                    "exception must reference index 0 or 'null'; got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Null entry in config.propertySources array")
    class NullSourceEntryTests {

        @Test
        @DisplayName("null entry in config.propertySources[0] → BootstrapConfigException naming the index")
        void nullPropertySourceEntryThrows() {
            JsonArray sources = new JsonArray();
            sources.addNull(); // propertySources[0] = null

            JsonObject deployment = new JsonObject()
                    .put(
                            BootstrapConfigLoader.CONFIG_SECTION,
                            new JsonObject().put(BootstrapConfigLoader.PROPERTY_SOURCES_KEY, sources));

            assertThrows(
                    BootstrapConfigException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "null propertySources entry must throw BootstrapConfigException");
        }
    }

    // --- Failing declared store ---

    @Nested
    @DisplayName("Failing declared store aborts bootstrap")
    class FailingStoreTests {

        @BeforeEach
        void enableFailMode() {
            CountingTestStoreFactory.failMode = true;
        }

        @AfterEach
        void disableFailMode() {
            CountingTestStoreFactory.failMode = false;
        }

        @Test
        @DisplayName("failMode=true → BootstrapConfigException; no thread leak")
        void failingStoreCausesException() throws InterruptedException {
            Set<String> threadsBefore = liveVertxThreadNames();

            JsonObject deployment = deploymentWithStores(countingStore(new JsonObject().put("key", "value")));

            assertThrows(
                    BootstrapConfigException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "A failing store must cause BootstrapConfigException");

            assertNoLeakedVertxThreads(threadsBefore);
        }
    }
}
