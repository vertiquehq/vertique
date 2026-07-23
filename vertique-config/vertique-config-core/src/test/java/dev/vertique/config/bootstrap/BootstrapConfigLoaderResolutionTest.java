// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.placeholder.PlaceholderResolutionException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the three-pass placeholder resolution integrated into
 * {@link BootstrapConfigLoader#load(JsonObject)}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Pass 3 always runs — even with zero declared sources, tree-only placeholders resolve</li>
 *   <li>AC-3 precedence: sys-prop layer dominates at merge time; placeholders see the winner</li>
 *   <li>AC-4 grammar end-to-end: {@code ${key}}, default, multi-placeholder concat, escape
 *       passthrough, whole-value type preservation</li>
 *   <li>Declared {@code config.propertySources} — sources instantiated in order, first-hit-wins</li>
 *   <li>Pass-1 tree-ref resolution of source entry fields; source-requiring ref in entry fails with
 *       "tree references only" message</li>
 *   <li>Unknown source type → {@link BootstrapConfigException} with type name and dependency hint</li>
 *   <li>Factory {@code create()} failure → {@link BootstrapConfigException} naming instance + type;
 *       previously-created stubs closed exactly once</li>
 *   <li>Pass-3 unresolved failure → {@link PlaceholderResolutionException} propagates; stub closed</li>
 *   <li>Stores-declaration placeholder resolves before store instantiation (phase-2 closes the gap)</li>
 *   <li>Source values containing {@code ${...}} appear literally in the final tree</li>
 * </ul>
 *
 * <p>Uses {@link StubSourceFactory} (registered via ServiceLoader in test resources) as the
 * controllable {@code "stub-source"} property source.
 */
class BootstrapConfigLoaderResolutionTest {

    @BeforeEach
    void resetStubState() {
        StubSourceFactory.State.reset();
    }

    @AfterEach
    void resetStubStateAfter() {
        StubSourceFactory.State.reset();
    }

    // --- Helpers ---

    /**
     * Builds a deployment config with a {@code config.propertySources} array from the given entries.
     *
     * @param entries the property-source entry objects
     * @return a deployment config containing the propertySources array
     */
    private static JsonObject deploymentWithSources(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject e : entries) {
            array.add(e);
        }
        return new JsonObject()
                .put(
                        BootstrapConfigLoader.CONFIG_SECTION,
                        new JsonObject().put(BootstrapConfigLoader.PROPERTY_SOURCES_KEY, array));
    }

    /**
     * Builds a stub-source entry with the given name and values map.
     *
     * @param name   the source instance name
     * @param values the key→value map the stub will serve
     * @return a source declaration JsonObject
     */
    private static JsonObject stubEntry(String name, JsonObject values) {
        return new JsonObject().put("type", "stub-source").put("name", name).put("values", values);
    }

    /**
     * Builds a stub-source entry with default name (type + "[" + index + "]") — name field absent.
     *
     * @param values the key→value map the stub will serve
     * @return a source declaration JsonObject with no name field
     */
    private static JsonObject stubEntryNoName(JsonObject values) {
        return new JsonObject().put("type", "stub-source").put("values", values);
    }

    /**
     * Builds a stub-source entry that will fail on {@code create()}.
     *
     * @param name the source instance name
     * @return a source declaration JsonObject with {@code failCreate: true}
     */
    private static JsonObject stubEntryFailCreate(String name) {
        return new JsonObject().put("type", "stub-source").put("name", name).put("failCreate", true);
    }

    // ── Test 1: zero-declaration path ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Test 1 — zero-declaration path: pass 3 always runs")
    class ZeroDeclarationPathTests {

        @Test
        @DisplayName("no propertySources declared; tree placeholder + default both resolve via pass 3")
        void treeOnlyPlaceholdersResolveWithNoSources() {
            // tree: app.base = "/app"; app.full = "${app.base}/run"
            // default: app.missing uses default "fallback"
            JsonObject deployment = new JsonObject()
                    .put("app.base", "/app")
                    .put("app.full", "${app.base}/run")
                    .put("app.missing", "${no.such.key:fallback}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals(
                    "/app/run",
                    result.config().getString("app.full"),
                    "Tree placeholder must resolve even with zero sources");
            assertEquals(
                    "fallback",
                    result.config().getString("app.missing"),
                    "Default must apply when key is absent and no sources are declared");
            assertTrue(result.propertySources().isEmpty(), "propertySources must be empty when none are declared");
        }
    }

    // ── Test 2: AC-3 precedence end-to-end ────────────────────────────────────────────

    @Nested
    @DisplayName("Test 2 — AC-3 precedence: sys-prop + --conf overlay; placeholder sees winner")
    class PrecedenceEndToEndTests {

        private static final String MULTI_LAYER_KEY = "vertique.restest.multilayer";
        private String savedSysPropValue;

        @BeforeEach
        void setUpSysProp() {
            savedSysPropValue = System.getProperty(MULTI_LAYER_KEY);
            System.setProperty(MULTI_LAYER_KEY, "from-sys");
        }

        @AfterEach
        void tearDownSysProp() {
            if (savedSysPropValue != null) {
                System.setProperty(MULTI_LAYER_KEY, savedSysPropValue);
            } else {
                System.clearProperty(MULTI_LAYER_KEY);
            }
        }

        @Test
        @DisplayName("placeholder referencing a key present in sys-prop AND deployment: merged winner wins")
        void placeholderSeesPostMergeWinner() {
            // deployment overlay provides the key too; deploymentConfig wins over sys in mergeIn
            JsonObject deployment = new JsonObject()
                    .put(MULTI_LAYER_KEY, "from-deployment")
                    .put("app.marker", "${" + MULTI_LAYER_KEY + "}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            // deployment overlay wins over sys prop (deploymentConfig is highest precedence)
            assertEquals(
                    "from-deployment",
                    result.config().getString(MULTI_LAYER_KEY),
                    "Deployment overlay must win over sys prop");
            assertEquals(
                    "from-deployment",
                    result.config().getString("app.marker"),
                    "Placeholder must resolve to the post-merge winner");
        }

        @Test
        @DisplayName("sys prop key with no deployment overlay: placeholder resolves to sys-prop value")
        void placeholderFromSysPropKey() {
            // No deployment overlay for MULTI_LAYER_KEY → sys prop value survives merge
            JsonObject deployment = new JsonObject().put("app.marker2", "${" + MULTI_LAYER_KEY + "}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals(
                    "from-sys", result.config().getString(MULTI_LAYER_KEY), "Sys-prop key must appear in merged tree");
            assertEquals(
                    "from-sys",
                    result.config().getString("app.marker2"),
                    "Placeholder must resolve to sys-prop value when no deployment override exists");
        }
    }

    // ── Test 3: AC-4 grammar end-to-end ───────────────────────────────────────────────

    @Nested
    @DisplayName("Test 3 — AC-4 grammar: ${key}, default, concat, escape, type preservation")
    class GrammarEndToEndTests {

        /**
         * Verifies all five AC-4 grammar forms in a single combined deployment tree:
         * simple key reference, URL-like default, multi-placeholder concat, escape passthrough,
         * and whole-value type preservation. The per-form engine semantics are exhaustively
         * proven Vert.x-free in {@code PlaceholderResolverTest}; this test confirms that
         * {@link BootstrapConfigLoader#load(JsonObject)} correctly threads all five through the
         * full three-pass pipeline.
         */
        @Test
        @DisplayName("all five grammar forms resolve correctly through the full bootstrap pipeline")
        void allGrammarFormsInCombinedDeployment() {
            JsonObject deployment = new JsonObject()
                    // simple key reference: ${base.url}/api
                    .put("base.url", "https://example.com")
                    .put("full.url", "${base.url}/api")
                    // URL-like default: no.such.host absent → http://localhost:8080
                    .put("service.endpoint", "${no.such.host:http://localhost:8080}")
                    // multi-placeholder concat: jdbc:postgresql://${host}:${port}/mydb
                    .put("host", "db.example.com")
                    .put("port", 5432)
                    .put("jdbc.url", "jdbc:postgresql://${host}:${port}/mydb")
                    // escape passthrough: \${not.resolved} → ${not.resolved} literal
                    .put("literal", "\\${not.resolved}")
                    // whole-value Integer type preservation
                    .put("server.port", 9090)
                    .put("http.port", "${server.port}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            // simple key reference
            assertEquals(
                    "https://example.com/api", result.config().getString("full.url"), "${key} must resolve from tree");

            // URL-like default
            assertEquals(
                    "http://localhost:8080",
                    result.config().getString("service.endpoint"),
                    "${key:URL-default} must use default when key is absent");

            // multi-placeholder concat
            assertEquals(
                    "jdbc:postgresql://db.example.com:5432/mydb",
                    result.config().getString("jdbc.url"),
                    "multi-placeholder concat must produce concatenated string");

            // escape passthrough
            assertEquals(
                    "${not.resolved}", result.config().getString("literal"), "\\${escape} must pass through literally");

            // whole-value Integer type preservation
            Object portValue = result.config().getValue("http.port");
            assertInstanceOf(
                    Integer.class, portValue, "Whole-value Integer placeholder must preserve type through load()");
            assertEquals(9090, portValue, "Whole-value Integer placeholder must resolve to correct value");
        }
    }

    // ── Test 4: propertySources declared ──────────────────────────────────────────────

    @Nested
    @DisplayName("Test 4 — propertySources declared: stub serves key; order preserved; first-hit-wins")
    class PropertySourcesDeclaredTests {

        @Test
        @DisplayName("single stub-source: placeholder resolves from source; propertySources size 1")
        void singleSourceResolvesPlaceholder() {
            JsonObject deployment = deploymentWithSources(
                            stubEntry("primary", new JsonObject().put("db.password", "secret-value")))
                    .put("connection.url", "${db.password}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals(
                    "secret-value",
                    result.config().getString("connection.url"),
                    "Placeholder must resolve from the stub source");
            assertEquals(1, result.propertySources().size(), "Result must have exactly 1 property source");
        }

        @Test
        @DisplayName("two stub-sources: first declared source wins on collision; both instantiated in order")
        void twoSourcesFirstWins() {
            JsonObject deployment = deploymentWithSources(
                            stubEntry("first", new JsonObject().put("shared.key", "from-first")),
                            stubEntry("second", new JsonObject().put("shared.key", "from-second")))
                    .put("val", "${shared.key}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals("from-first", result.config().getString("val"), "First declared source must win on collision");
            assertEquals(2, result.propertySources().size(), "Both sources must appear in the result list");
            // Declared order preserved: first source is at index 0
            assertEquals(
                    "first",
                    result.propertySources().get(0).name(),
                    "Declared order must be preserved in propertySources list");
            assertEquals("second", result.propertySources().get(1).name(), "Second source must be at index 1");
            assertEquals(2, StubSourceFactory.State.createCount.get(), "Both stubs must have been created");
        }

        @Test
        @DisplayName("absent name field defaults to 'type[index]'")
        void absentNameDefaultsToTypeIndex() {
            JsonObject deployment = deploymentWithSources(
                            stubEntryNoName(new JsonObject().put("some.key", "from-stub")))
                    .put("target", "${some.key}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals("from-stub", result.config().getString("target"));
            assertEquals(1, result.propertySources().size());
            // Name must be "stub-source[0]" (type + "[" + index + "]")
            assertEquals(
                    "stub-source[0]",
                    result.propertySources().get(0).name(),
                    "Source with absent name must default to 'type[index]'");
        }
    }

    // ── Test 5: pass-1 tree-ref in source entry ────────────────────────────────────────

    @Nested
    @DisplayName("Test 5 — pass-1 tree-ref: entry field referencing tree key resolved before factory.create")
    class Pass1TreeRefTests {

        @Test
        @DisplayName("entry 'values.seed-key' contains ${seed.value}: source sees resolved value")
        void entryFieldResolvedFromTree() {
            // The stub source's "values" map has an entry whose value is a placeholder.
            // Pass 1 resolves the propertySources array against the merged tree.
            // After resolution, the stub is created with the resolved value, so it serves
            // the resolved string (not the placeholder text).
            JsonObject deployment = deploymentWithSources(new JsonObject()
                            .put("type", "stub-source")
                            .put("name", "resolved-stub")
                            .put("values", new JsonObject().put("actual.secret", "${seed.value}")))
                    .put("seed.value", "resolved-seed")
                    .put("result", "${actual.secret}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            // The stub was created with values.actual.secret = "resolved-seed"
            // so ${actual.secret} in the tree should resolve to "resolved-seed"
            assertEquals(
                    "resolved-seed",
                    result.config().getString("result"),
                    "Source entry field containing tree placeholder must be resolved before source creation");
        }

        @Test
        @DisplayName("source-requiring ref inside propertySources entry fails with 'tree references only' message")
        void sourceRequiringRefInEntryFails() {
            // This placeholder cannot be resolved from the tree (no stub sources exist yet in pass 1)
            JsonObject deployment = new JsonObject()
                    .put(
                            BootstrapConfigLoader.CONFIG_SECTION,
                            new JsonObject()
                                    .put(
                                            BootstrapConfigLoader.PROPERTY_SOURCES_KEY,
                                            new JsonArray()
                                                    .add(new JsonObject()
                                                            .put("type", "stub-source")
                                                            .put("name", "bad-entry")
                                                            .put(
                                                                    "values",
                                                                    new JsonObject().put("k", "${external.secret}")))));

            // external.secret is not in the tree, so pass 1 resolveAgainstTree must fail
            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "Source-requiring ref in propertySources entry must fail with PlaceholderResolutionException");

            assertTrue(
                    ex.getMessage().contains("tree references only"),
                    "Exception must include 'tree references only' message: " + ex.getMessage());
        }
    }

    // ── Test 6: unknown source type ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Test 6 — unknown source type: BootstrapConfigException with hint")
    class UnknownSourceTypeTests {

        @Test
        @DisplayName("type 'vault' → BootstrapConfigException naming 'vault' and 'vertique-config-vault' hint")
        void unknownTypeVault() {
            JsonObject deployment =
                    deploymentWithSources(new JsonObject().put("type", "vault").put("name", "my-vault"));

            BootstrapConfigException ex =
                    assertThrows(BootstrapConfigException.class, () -> BootstrapConfigLoader.load(deployment));

            assertTrue(ex.getMessage().contains("vault"), "Exception must name the unknown type: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("dev.vertique:vertique-config-vault"),
                    "Exception must include 'vertique-config-vault' hint: " + ex.getMessage());
        }

        @Test
        @DisplayName("completely unknown type → generic hint mentioning the type")
        void unknownTypeGenericHint() {
            JsonObject deployment = deploymentWithSources(
                    new JsonObject().put("type", "totally-unknown-type").put("name", "x"));

            BootstrapConfigException ex =
                    assertThrows(BootstrapConfigException.class, () -> BootstrapConfigLoader.load(deployment));

            assertTrue(
                    ex.getMessage().contains("totally-unknown-type"),
                    "Exception must name the type: " + ex.getMessage());
        }
    }

    // ── Test 7: factory create() failure ──────────────────────────────────────────────

    @Nested
    @DisplayName("Test 7 — factory create() failure: BootstrapConfigException; prior sources closed")
    class FactoryCreateFailureTests {

        @Test
        @DisplayName("second stub fails create; first stub's close() was called exactly once")
        void priorSourceClosedOnCreateFailure() {
            JsonObject deployment = deploymentWithSources(
                    stubEntry("ok-source", new JsonObject().put("k1", "v1")), stubEntryFailCreate("bad-source"));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "Factory create() failure must throw BootstrapConfigException");

            assertTrue(
                    ex.getMessage().contains("bad-source"),
                    "Exception must name the failing source instance: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("stub-source"), "Exception must name the type: " + ex.getMessage());

            // The first source was created successfully and must have been closed during cleanup
            assertEquals(
                    1,
                    StubSourceFactory.State.createCount.get(),
                    "Only the first (successful) source must have been created");
            assertEquals(
                    1,
                    StubSourceFactory.State.totalCloseCount.get(),
                    "The first source must have been closed exactly once during cleanup");
        }

        @Test
        @DisplayName("only source fails create; no close() calls needed")
        void onlySourceFailsCreateNoCloseCalled() {
            JsonObject deployment = deploymentWithSources(stubEntryFailCreate("only-bad"));

            assertThrows(BootstrapConfigException.class, () -> BootstrapConfigLoader.load(deployment));

            assertEquals(0, StubSourceFactory.State.createCount.get(), "No source was created — createCount must be 0");
            assertEquals(
                    0, StubSourceFactory.State.totalCloseCount.get(), "No source to close — totalCloseCount must be 0");
        }
    }

    // ── Test 8: pass-3 unresolved failure ─────────────────────────────────────────────

    @Nested
    @DisplayName("Test 8 — pass-3 unresolved: PlaceholderResolutionException propagates; stub closed")
    class Pass3UnresolvedTests {

        @Test
        @DisplayName(
                "placeholder ${unreachable.secret} unresolvable: exception propagates; stub closed; sentinel absent")
        void unresolvedPlaceholderPropagatesAndClosesSource() {
            // Seed a sentinel value that must NEVER appear in exception message (NFR-CONF-002)
            String sentinel = "VERY_SECRET_VALUE_SENTINEL_XYZ";

            JsonObject deployment = deploymentWithSources(
                            stubEntry("stub-for-close", new JsonObject().put("irrelevant.key", sentinel)))
                    .put("bad.placeholder", "${unreachable.secret}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "Unresolved placeholder must cause PlaceholderResolutionException to propagate from load()");

            // Exception must reference the unresolved key, not wrap it in BootstrapConfigException
            assertTrue(
                    ex.unresolvedReferences().contains("unreachable.secret"),
                    "Unresolved references must contain the key: " + ex.unresolvedReferences());

            // NFR-CONF-002: sentinel must NOT appear in exception message
            assertFalse(ex.getMessage().contains(sentinel), "Sentinel value must not appear in exception message");

            // The stub source must have been closed
            assertEquals(
                    1,
                    StubSourceFactory.State.totalCloseCount.get(),
                    "Stub source must be closed exactly once after pass-3 failure");
        }
    }

    // ── Test 9: stores-declaration placeholder ────────────────────────────────────────

    @Nested
    @DisplayName("Test 9 — stores-declaration placeholder: config.stores entry resolved before store instantiation")
    class StoresDeclarationPlaceholderTests {

        @BeforeEach
        void resetFactory() {
            CountingTestStoreFactory.reset();
        }

        @AfterEach
        void resetFactoryAfter() {
            CountingTestStoreFactory.reset();
        }

        @Test
        @DisplayName("stores entry containing ${tree.ref} in payload config: store sees resolved value")
        void storeEntryPlaceholderResolved() {
            // The counting-test store's payload is built from its config.payload field.
            // If config.stores[0].config.payload.store-key = "${tree.ref}", the stores-declaration
            // placeholder pass (step A) must resolve that before buildDeclaredStores reads it.
            //
            // We put tree.ref = "expected-store-value" in the deployment, and the store entry
            // has config.payload.store-key = "${tree.ref}".
            //
            // After store runs, result.config() must have store-key = "expected-store-value",
            // because the store was built with the RESOLVED payload.
            JsonObject storeEntry = new JsonObject()
                    .put("type", "counting-test")
                    .put("config", new JsonObject().put("payload", new JsonObject().put("store-key", "${tree.ref}")));

            JsonArray storesArray = new JsonArray().add(storeEntry);

            JsonObject deployment = new JsonObject()
                    .put("tree.ref", "expected-store-value")
                    .put(
                            BootstrapConfigLoader.CONFIG_SECTION,
                            new JsonObject().put(BootstrapConfigLoader.STORES_KEY, storesArray));

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            // The counting store contributed store-key; pass 3 now resolves the merged tree.
            // The store-key in the tree came from the store payload, so it was the RESOLVED value.
            assertEquals(
                    "expected-store-value",
                    result.config().getString("store-key"),
                    "Store must receive the resolved placeholder value in its payload config");
            assertEquals(1, CountingTestStoreFactory.createCount.get(), "Counting store must have been created once");
        }
    }

    // ── Test 10: source-values literal end-to-end ─────────────────────────────────────

    @Nested
    @DisplayName("Test 10 — source-values literal: ${...} returned by source appears literally")
    class SourceValuesLiteralTests {

        @Test
        @DisplayName("stub serves '${still.a.placeholder}' as a value; appears literally in final tree")
        void sourceValueContainingPlaceholderAppearsLiterally() {
            // The stub serves a value that looks like a placeholder.
            // Per the source-values-are-literal contract, it must NOT be re-expanded.
            //
            // We register the literal value via StubSourceFactory.State.staticValues to bypass
            // pass-1 source-entry resolution (which would fail on ${...} in the values map
            // because bootstrap mode only allows tree references). The real-world analogy is a
            // Vault source that fetches a secret at lookup time from the external system rather
            // than from its source-config entry.
            StubSourceFactory.State.staticValues.put("safe.key", "${still.a.placeholder}");

            JsonObject deployment = deploymentWithSources(stubEntry("literal-stub", new JsonObject()))
                    .put("result", "${safe.key}");

            BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deployment);

            assertEquals(
                    "${still.a.placeholder}",
                    result.config().getString("result"),
                    "Value returned by a source that looks like a placeholder must appear literally");
        }
    }

    // ── Test 11: validate-first prevents source leak on validation errors ──────────────

    /**
     * Verifies that {@link BootstrapConfigLoader#load(JsonObject)} validates ALL source entries
     * before creating ANY source (validate-first-then-create). A validation error on a later entry
     * must not cause earlier entries to have been created, because no source is created until the
     * entire array passes validation.
     *
     * <p>Tests:
     * <ul>
     *   <li>Two-entry declaration: entry 0 is a valid stub-source, entry 1 has an unknown type.
     *       After the validation error {@code StubSourceFactory.State.createCount} must be 0
     *       (no source was created for entry 0 before the validation failure on entry 1).</li>
     *   <li>Existing create-failure close path (Test 7 regression guard): entry 0 creates
     *       successfully, entry 1 fails during {@code factory.create()} — the first source is
     *       closed exactly once. This tests the separate create-failure path that still applies
     *       close-then-throw for {@code create()} exceptions.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Test 11 — validate-first: no source created before validation error; create-failure path unaffected")
    class ValidateFirstTests {

        @Test
        @DisplayName(
                "entry 0 valid stub-source, entry 1 unknown type → BootstrapConfigException; createCount == 0 (no leak)")
        void unknownTypeOnLaterEntryProducesNoCreatedSources() {
            // Entry 0 is a valid stub-source. Entry 1 declares an unknown type.
            // With validate-first, ALL entries are validated before any source is created.
            // Therefore createCount must be 0 even though entry 0 is valid.
            JsonObject deployment = deploymentWithSources(
                    stubEntry("valid-entry", new JsonObject().put("k", "v")),
                    new JsonObject()
                            .put("type", "totally-unknown-for-leak-test")
                            .put("name", "bad-entry"));

            BootstrapConfigException ex = assertThrows(
                    BootstrapConfigException.class,
                    () -> BootstrapConfigLoader.load(deployment),
                    "Unknown type on entry 1 must throw BootstrapConfigException");

            assertTrue(
                    ex.getMessage().contains("totally-unknown-for-leak-test"),
                    "Exception must name the unknown type: " + ex.getMessage());

            assertEquals(
                    0,
                    StubSourceFactory.State.createCount.get(),
                    "createCount must be 0: validate-first means no source is created before the validation error");
            assertEquals(
                    0,
                    StubSourceFactory.State.totalCloseCount.get(),
                    "totalCloseCount must be 0: nothing was created, so nothing needs closing");
        }

        @Test
        @DisplayName(
                "entry 0 creates OK, entry 1 fails create → first source closed exactly once (create-failure path)")
        void createFailureOnSecondEntryStillClosesFirst() {
            // This is a regression guard for the create-failure close path (Test 7).
            // Both entries pass validation (both have known types). Entry 1 fails during create().
            // The first successfully-created source must be closed exactly once.
            JsonObject deployment = deploymentWithSources(
                    stubEntry("ok-source", new JsonObject().put("k1", "v1")), stubEntryFailCreate("bad-source"));

            assertThrows(BootstrapConfigException.class, () -> BootstrapConfigLoader.load(deployment));

            assertEquals(
                    1,
                    StubSourceFactory.State.createCount.get(),
                    "Only the first source must have been created (entry 1 fails create, not validation)");
            assertEquals(
                    1,
                    StubSourceFactory.State.totalCloseCount.get(),
                    "First source must have been closed exactly once after create() failure on second entry");
        }
    }
}
