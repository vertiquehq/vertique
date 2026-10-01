// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.AnnotatedInfoApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.BlankTitleInfoApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.ConfigRegistrations;
import dev.vertique.rest.openapi.docs.fixture.startup.config.InheritedInfoApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsBlankRoleEntryApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsEmptyRoleEntryApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsProtectedBlankSchemeApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsProtectedWithoutSchemeApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsPublicWithRolesApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsPublicWithSchemeAndRolesApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsPublicWithSchemeApi;
import dev.vertique.rest.openapi.docs.fixture.startup.config.OpsValidProtectedApi;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code apidocs} configuration and the validation the documentation module runs when at least
 * one document is enabled. The declared applications come from the shared fixture's registrations
 * through the component's {@link RestApplications} view.
 */
@DisplayName("The apidocs configuration and enabled-document validation")
class ApidocsConfigTest {

    /** A marker no message may contain: it appears in every rejected value and description. */
    private static final String MARKER = "zq7";

    private static final String MARKER_DESCRIPTION = MARKER + "Description";

    private static final String PUBLIC_INTERFACE = "dev.vertique.rest.openapi.docs.fixture.PublicApi";

    private static RestApplications applications;
    private static ConfigParser parser;

    @BeforeAll
    static void buildView() {
        DocsTestComponents.SharedComponent component =
                DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());
        applications = component.restApplications();
        parser = component.configParser();
    }

    /** Runs the configuration provider, then the selection provider, as the composition does. */
    private static EnabledDocuments resolve(JsonObject config) {
        ApidocsConfig apidocsConfig = OpenApiDocsModule.apidocsConfig(config, parser);
        return OpenApiDocsModule.enabledDocuments(config, apidocsConfig, applications);
    }

    // ---- an invalid prefix fails startup naming the setting ----

    static Stream<Arguments> prefixValues() {
        return Stream.of(
                Arguments.of("/", false),
                Arguments.of("zq7docs", false),
                Arguments.of("/zq7docs/", false),
                Arguments.of("/zq7/docs/*", false),
                Arguments.of("/zq7/:x", false),
                Arguments.of("/zq7/{x}", false),
                Arguments.of("/zq7?x", false),
                Arguments.of("/zq7#x", false),
                Arguments.of("/zq7//docs", false),
                Arguments.of("/zq7/./docs", false),
                Arguments.of("/zq7/../docs", false),
                Arguments.of("/apidocs", true),
                Arguments.of("/zq7/docs", true),
                Arguments.of("/zq7 docs", false));
    }

    @ParameterizedTest(name = "apidocs.path {0} valid={1}")
    @MethodSource("prefixValues")
    @DisplayName(
            "An invalid apidocs.path fails startup naming the setting without echoing the value, and a valid one resolves")
    void invalidPrefixFailsNamingTheSetting(String value, boolean valid) {
        // Given the shared configuration with the prefix under test, and the same prefix with the
        // public document disabled
        JsonObject enabledConfig = DocsConfigs.withApidocsPath(DocsConfigs.shared(), value);
        JsonObject disabledConfig = DocsConfigs.withDocumentEnabled(
                DocsConfigs.withApidocsPath(DocsConfigs.shared(), value), "public", false);

        if (valid) {
            // When the providers run with the document enabled
            ApidocsConfig apidocsConfig = OpenApiDocsModule.apidocsConfig(enabledConfig, parser);
            EnabledDocuments documents = OpenApiDocsModule.enabledDocuments(enabledConfig, apidocsConfig, applications);

            // Then one document, public, resolves under that prefix
            assertEquals(value, apidocsConfig.path());
            assertEquals(1, documents.all().size());
            assertEquals("public", documents.all().get(0).name());
        } else {
            // When the providers run with the document enabled
            ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(enabledConfig));

            // Then the message names the setting and never echoes the value
            assertTrue(failure.getMessage().contains("apidocs.path"), failure.getMessage());
            assertFalse(failure.getMessage().contains(MARKER), failure.getMessage());
        }

        // When the providers run with the document disabled, whatever the value
        EnabledDocuments none = resolve(disabledConfig);

        // Then no document resolves and nothing is thrown
        assertTrue(none.isEmpty());
    }

    // ---- an enabled document needs its info from configuration ----

    static Stream<Arguments> invalidInfo() {
        return Stream.of(
                Arguments.of("info absent", "apidocs.documents.public.info", null, null),
                Arguments.of("title empty", "apidocs.documents.public.info.title", "", "1.0"),
                Arguments.of("title whitespace", "apidocs.documents.public.info.title", "   ", "1.0"),
                Arguments.of("version empty", "apidocs.documents.public.info.version", "Catalog", ""),
                Arguments.of("version whitespace", "apidocs.documents.public.info.version", "Catalog", "  "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInfo")
    @DisplayName(
            "An enabled document without a configured title or version fails naming its application, interface and the offending setting")
    void enabledDocumentNeedsConfiguredInfo(String variant, String expectedPath, String title, String version) {
        // Given the shared configuration changed by one rule
        JsonObject config = DocsConfigs.shared();
        if (title == null) {
            DocsConfigs.document(config, "public").remove("info");
        } else {
            DocsConfigs.withDocumentInfo(config, "public", title, version);
            DocsConfigs.document(config, "public").getJsonObject("info").put("description", MARKER_DESCRIPTION);
        }

        // When the providers run
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config));

        // Then the message names the application, its declaring interface and the offending path
        String message = failure.getMessage();
        assertTrue(message.contains("public"), message);
        assertTrue(message.contains(PUBLIC_INTERFACE), message);
        assertTrue(message.contains(expectedPath), message);
        assertFalse(message.contains(MARKER), message);
        if (title != null) {
            // and only the failing attribute is named
            String other = expectedPath.endsWith(".title")
                    ? "apidocs.documents.public.info.version"
                    : "apidocs.documents.public.info.title";
            assertFalse(message.contains(other), message);
        }
    }

    @Test
    @DisplayName("A disabled document is not validated")
    void disabledDocumentIsNotValidated() {
        // Given the public document disabled with no info
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.document(config, "public").remove("info");
        DocsConfigs.withDocumentEnabled(config, "public", false);

        // When the providers run
        EnabledDocuments documents = resolve(config);

        // Then no document resolves and nothing is thrown
        assertTrue(documents.isEmpty());
    }

    @Test
    @DisplayName("An application whose interface carries no @ApiDocs is not validated")
    void applicationWithoutApiDocsIsNotValidated() {
        // Given an entry for mgmt, whose interface carries no @ApiDocs, without info
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.document(config, "mgmt");

        // When the providers run
        EnabledDocuments documents = resolve(config);

        // Then only public resolves, with its configured info
        assertEquals(1, documents.all().size());
        EnabledDocuments.EnabledDocument document = documents.all().get(0);
        assertEquals("public", document.name());
        assertEquals("Catalog", document.info().title());
        assertEquals("1.0", document.info().version());
    }

    @Test
    @DisplayName("An explicit null enabled keeps the annotation decision")
    void explicitNullEnabledKeepsTheAnnotationDecision() {
        // Given the public entry with an explicit JSON null for enabled
        JsonObject config = DocsConfigs.withDocumentEnabled(DocsConfigs.shared(), "public", null);

        // When the providers run
        EnabledDocuments documents = resolve(config);

        // Then public is still enabled
        assertEquals(1, documents.all().size());
        assertEquals("public", documents.all().get(0).name());
    }

    // ---- parsing ----

    @Test
    @DisplayName("An absent apidocs section parses to the defaults")
    void absentSectionParsesToDefaults() {
        // Given a configuration without apidocs
        JsonObject config = DocsConfigs.loopback();

        // When the configuration provider runs
        ApidocsConfig apidocs = OpenApiDocsModule.apidocsConfig(config, parser);

        // Then the defaults apply
        assertEquals("/apidocs", apidocs.path());
        assertTrue(apidocs.enabled());
        assertEquals(List.of(), apidocs.documents());
    }

    @Test
    @DisplayName("Document entries are parsed with every attribute and without validation")
    void documentEntriesAreParsedWithoutValidation() {
        // Given a document entry with every attribute
        JsonObject config = DocsConfigs.loopback();
        JsonObject entry = DocsConfigs.document(config, "public");
        entry.put("enabled", false);
        entry.put("serverUrl", "https://example.invalid/api");
        entry.put("info", new JsonObject().put("title", "T").put("version", "V").put("description", "D"));

        // When the configuration provider runs
        ApidocsConfig apidocs = OpenApiDocsModule.apidocsConfig(config, parser);

        // Then the entry is keyed by its name and carries the attributes
        assertEquals(1, apidocs.documents().size());
        DocumentConfig document = apidocs.documents().get(0);
        assertEquals("public", document.name());
        assertEquals(Boolean.FALSE, document.enabled());
        assertEquals("https://example.invalid/api", document.serverUrl());
        assertEquals("T", document.info().title());
        assertEquals("V", document.info().version());
        assertEquals("D", document.info().description());
    }

    @Test
    @DisplayName("An absent enabled on an entry parses to null rather than a default")
    void absentEnabledOnAnEntryParsesToNull() {
        // Given an entry without enabled
        JsonObject config = DocsConfigs.shared();

        // When the configuration provider runs
        ApidocsConfig apidocs = OpenApiDocsModule.apidocsConfig(config, parser);

        // Then the entry's enabled is null, not a default
        assertNull(apidocs.documents().get(0).enabled());
    }

    @Test
    @DisplayName("A globally disabled apidocs skips the rest of its subtree")
    void globallyDisabledSkipsTheRestOfTheSubtree() {
        // Given enabled false beside an invalid path and a malformed documents value
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.withApidocsEnabled(config, false);
        DocsConfigs.withApidocsPath(config, MARKER + "docs/");
        DocsConfigs.apidocs(config).put("documents", "not an object");

        // When the providers run
        ApidocsConfig apidocs = OpenApiDocsModule.apidocsConfig(config, parser);
        EnabledDocuments documents = OpenApiDocsModule.enabledDocuments(config, apidocs, applications);

        // Then the configuration is the disabled default and no document resolves
        assertFalse(apidocs.enabled());
        assertEquals("/apidocs", apidocs.path());
        assertEquals(List.of(), apidocs.documents());
        assertTrue(documents.isEmpty());
    }

    static Stream<Object> malformedEnabled() {
        return Stream.of("yes", "true", 1, new JsonObject());
    }

    @ParameterizedTest
    @MethodSource("malformedEnabled")
    @DisplayName("A non-boolean apidocs.enabled fails naming the setting")
    void malformedEnabledFailsNamingTheSetting(Object value) {
        // Given apidocs.enabled that is not a JSON boolean
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.apidocs(config).put("enabled", value);

        // When the configuration provider runs
        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> OpenApiDocsModule.apidocsConfig(config, parser));

        // Then the message names the setting
        assertTrue(failure.getMessage().contains("apidocs.enabled"), failure.getMessage());
    }

    // ---- document entries: names, declared applications, keys, enabled, info, serverUrl ----

    /** The application-name grammar every document entry's key must match. */
    private static final String NAME_GRAMMAR = "[a-z0-9][a-z0-9_-]{0,63}";

    private static final String MGMT_INTERFACE = "dev.vertique.rest.openapi.docs.fixture.MgmtApi";

    private static final String BLANK_TITLE_INFO_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.BlankTitleInfoApi";

    private static final String INHERITED_INFO_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.InheritedInfoApi";

    private static final String OPS_PROTECTED_WITHOUT_SCHEME_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsProtectedWithoutSchemeApi";

    private static final String OPS_PROTECTED_BLANK_SCHEME_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsProtectedBlankSchemeApi";

    private static final String OPS_PUBLIC_WITH_SCHEME_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsPublicWithSchemeApi";

    private static final String OPS_PUBLIC_WITH_ROLES_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsPublicWithRolesApi";

    private static final String OPS_BLANK_ROLE_ENTRY_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsBlankRoleEntryApi";

    private static final String OPS_EMPTY_ROLE_ENTRY_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsEmptyRoleEntryApi";

    private static final String SECURITY_SCHEME_ATTRIBUTE = "@ApiDocs.securityScheme";

    private static final String ROLES_ALLOWED_ATTRIBUTE = "@ApiDocs.rolesAllowed";

    /** Builds the declared-application view over the given generated-shape registrations. */
    private static RestApplications view(GeneratedRestApplicationRegistration... registrations) {
        return DaggerConfigViewComponents_ViewComponent.factory()
                .create(DocsConfigs.shared(), new ConfigViewComponents.RegistrationList(List.of(registrations)))
                .restApplications();
    }

    /** The view of the shared declarations: the documented {@code PublicApi} and the undocumented {@code MgmtApi}. */
    private static RestApplications sharedView() {
        return view(ConfigRegistrations.publicApi(PublicApi.class), ConfigRegistrations.mgmtApi());
    }

    /** The view of the shared declarations plus one more registration. */
    private static RestApplications sharedViewWith(GeneratedRestApplicationRegistration added) {
        return view(ConfigRegistrations.publicApi(PublicApi.class), ConfigRegistrations.mgmtApi(), added);
    }

    /** Runs the configuration provider, then the selection provider, against the given view. */
    private static EnabledDocuments resolve(JsonObject config, RestApplications view) {
        ApidocsConfig apidocsConfig = OpenApiDocsModule.apidocsConfig(config, parser);
        return OpenApiDocsModule.enabledDocuments(config, apidocsConfig, view);
    }

    /** The enabled documents' names, in the order resolved. */
    private static List<String> names(EnabledDocuments documents) {
        return documents.all().stream()
                .map(EnabledDocuments.EnabledDocument::name)
                .toList();
    }

    /** An application name of the given length: {@code a} followed by {@code length - 1} {@code b}s. */
    private static String nameOfLength(int length) {
        return "a" + "b".repeat(length - 1);
    }

    /** An entry object holding only {@code info {title, version}}. */
    private static JsonObject infoEntry(String title, String version) {
        return new JsonObject().put("info", new JsonObject().put("title", title).put("version", version));
    }

    /** The shared configuration plus {@code apidocs.documents.<name>} merged with {@code entry}. */
    private static JsonObject sharedWithEntry(String name, JsonObject entry) {
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.document(config, name).mergeIn(entry);
        return config;
    }

    /** Asserts a failure names the application, quoted, and its declaring interface, and echoes no marker. */
    private static void assertNamesApplication(String message, String application, String declaringInterface) {
        assertTrue(message.contains("'" + application + "'"), message);
        assertTrue(message.contains(declaringInterface), message);
        assertFalse(message.contains(MARKER), message);
    }

    static Stream<Arguments> entryNames() {
        List<String> invalidKeys = List.of("Api", "-api", "api.v1", "a b", "a/b", "ä", nameOfLength(65));
        Stream.Builder<Arguments> rows = Stream.builder();
        for (String key : invalidKeys) {
            rows.add(Arguments.of(key, null, true, false));
            rows.add(Arguments.of(key, false, true, false));
            rows.add(Arguments.of(key, null, false, false));
        }
        rows.add(Arguments.of(nameOfLength(64), null, true, true));
        return rows.build();
    }

    @ParameterizedTest(name = "key {0}, entry enabled {1}, apidocs enabled {2}, valid {3}")
    @MethodSource("entryNames")
    @DisplayName("A document entry's key must match the application-name grammar, whether or not the entry is enabled")
    void entryNamesFollowTheApplicationNameGrammar(
            String key, Boolean entryEnabled, boolean apidocsEnabled, boolean valid) {
        // Given the shared declarations plus the undocumented 64-character application, and the shared
        // configuration plus one entry under the key whose title carries the marker
        RestApplications view = sharedViewWith(ConfigRegistrations.longNameApi());
        JsonObject config = sharedWithEntry(key, infoEntry(MARKER + "Title", "1"));
        if (entryEnabled != null) {
            DocsConfigs.withDocumentEnabled(config, key, entryEnabled);
        }
        if (!apidocsEnabled) {
            DocsConfigs.withApidocsEnabled(config, false);
        }

        if (!apidocsEnabled) {
            // When the providers run with apidocs disabled
            EnabledDocuments documents = resolve(config, view);

            // Then no document resolves and nothing is thrown
            assertTrue(documents.isEmpty());
        } else if (valid) {
            // When the providers run
            EnabledDocuments documents = resolve(config, view);

            // Then the entry is accepted and adds no document: its application carries no @ApiDocs
            assertEquals(List.of("public"), names(documents));
        } else {
            // When the providers run
            ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

            // Then the message names the entry's path and the grammar, and echoes no value
            String message = failure.getMessage();
            assertTrue(message.contains("apidocs.documents." + key), message);
            assertTrue(message.contains(NAME_GRAMMAR), message);
            assertFalse(message.contains(MARKER), message);
        }
    }

    static Stream<Arguments> entryApplications() {
        return Stream.of(
                Arguments.of(
                        "an entry for an undeclared application",
                        sharedWithEntry("nosuch", infoEntry(MARKER + "Title", "1")),
                        null),
                Arguments.of(
                        "a disabling entry for an undeclared application",
                        sharedWithEntry("nosuch", new JsonObject().put("enabled", false)),
                        null),
                Arguments.of(
                        "an entry for an inactive documented application",
                        sharedWithEntry("dormant", infoEntry("Dormant", "1")),
                        List.of("public")),
                Arguments.of(
                        "an entry for an undocumented application",
                        sharedWithEntry("mgmt", infoEntry("Mgmt", "1")),
                        List.of("public")),
                Arguments.of(
                        "an entry for an undeclared application with apidocs disabled",
                        DocsConfigs.withApidocsEnabled(
                                sharedWithEntry("nosuch", infoEntry(MARKER + "Title", "1")), false),
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryApplications")
    @DisplayName("A document entry must name a declared application, active or not")
    void entriesMustNameADeclaredApplication(String variant, JsonObject config, List<String> expectedDocuments) {
        // Given the shared declarations plus the inactive documented application dormant
        RestApplications view = sharedViewWith(ConfigRegistrations.dormantApi());

        if (expectedDocuments == null) {
            // When the providers run
            ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

            // Then the message names the entry's path and states that no such application is declared
            String message = failure.getMessage();
            assertTrue(message.contains("apidocs.documents.nosuch"), message);
            assertTrue(message.contains("no application of that name is declared"), message);
            assertFalse(message.contains(MARKER), message);
        } else {
            // When the providers run
            EnabledDocuments documents = resolve(config, view);

            // Then exactly the expected documents resolve and nothing is thrown
            assertEquals(expectedDocuments, names(documents));
        }
    }

    static Stream<Arguments> entryKeys() {
        return Stream.of(
                Arguments.of(
                        "access",
                        new JsonObject().put("access", new JsonObject().put("mode", "public")),
                        null,
                        true,
                        List.of("apidocs.documents.public.access")),
                Arguments.of(
                        "mount",
                        new JsonObject().put("mount", "/" + MARKER),
                        null,
                        true,
                        List.of("apidocs.documents.public.mount")),
                Arguments.of(
                        "Enabled beside enabled",
                        new JsonObject().put("Enabled", true),
                        true,
                        true,
                        List.of("apidocs.documents.public.Enabled")),
                Arguments.of(
                        "requiredAction",
                        new JsonObject().put("requiredAction", MARKER + ".read"),
                        null,
                        true,
                        List.of("apidocs.documents.public.requiredAction")),
                Arguments.of(
                        "access and mount together",
                        new JsonObject().put("mount", "/" + MARKER).put("access", new JsonObject().put("mode", MARKER)),
                        null,
                        true,
                        List.of("apidocs.documents.public.access", "apidocs.documents.public.mount")),
                Arguments.of(
                        "access in a disabled entry",
                        new JsonObject().put("access", new JsonObject().put("mode", MARKER)),
                        false,
                        true,
                        List.of("apidocs.documents.public.access")),
                Arguments.of(
                        "access with apidocs disabled",
                        new JsonObject().put("access", new JsonObject().put("mode", MARKER)),
                        null,
                        false,
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryKeys")
    @DisplayName("A document entry holds only enabled, info and serverUrl")
    void entriesAcceptOnlyEnabledInfoAndServerUrl(
            String variant,
            JsonObject extraKeys,
            Boolean entryEnabled,
            boolean apidocsEnabled,
            List<String> expectedKeys) {
        // Given the shared declarations and the shared configuration whose public entry also holds the
        // extra keys
        RestApplications view = sharedView();
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.document(config, "public").mergeIn(extraKeys);
        if (entryEnabled != null) {
            DocsConfigs.withDocumentEnabled(config, "public", entryEnabled);
        }
        if (!apidocsEnabled) {
            DocsConfigs.withApidocsEnabled(config, false);
        }

        if (!apidocsEnabled) {
            // When the providers run with apidocs disabled
            EnabledDocuments documents = resolve(config, view);

            // Then no document resolves and nothing is thrown
            assertTrue(documents.isEmpty());
            return;
        }

        // When the providers run
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

        // Then the message names the application, its declaring interface, every unknown key's full
        // path in sorted order and the supported keys, and echoes no value
        String message = failure.getMessage();
        assertNamesApplication(message, "public", PUBLIC_INTERFACE);
        int previous = -1;
        for (String path : expectedKeys) {
            int index = message.indexOf(path);
            assertTrue(index > previous, "expected " + path + " after the previous key in: " + message);
            previous = index;
        }
        assertTrue(message.contains("'enabled'"), message);
        assertTrue(message.contains("'info'"), message);
        assertTrue(message.contains("'serverUrl'"), message);
    }

    /** The shared configuration plus {@code apidocs.documents.ops.info} {@code {title: "Ops", version: "1"}}. */
    private static JsonObject sharedWithOpsInfo() {
        return DocsConfigs.withDocumentInfo(DocsConfigs.shared(), "ops", "Ops", "1");
    }

    static Stream<Arguments> enabledVariants() {
        List<String> both = List.of("ops", "public");
        return Stream.of(
                Arguments.of("no enabled key", sharedWithOpsInfo(), both),
                Arguments.of(
                        "public enabled null",
                        DocsConfigs.withDocumentEnabled(sharedWithOpsInfo(), "public", null),
                        both),
                Arguments.of(
                        "public enabled false",
                        DocsConfigs.withDocumentEnabled(sharedWithOpsInfo(), "public", false),
                        List.of("ops")),
                Arguments.of(
                        "public enabled true",
                        DocsConfigs.withDocumentEnabled(sharedWithOpsInfo(), "public", true),
                        both),
                Arguments.of(
                        "apidocs enabled false", DocsConfigs.withApidocsEnabled(sharedWithOpsInfo(), false), List.of()),
                Arguments.of(
                        "mgmt enabled true without @ApiDocs",
                        DocsConfigs.withDocumentEnabled(sharedWithOpsInfo(), "mgmt", true),
                        null),
                Arguments.of(
                        "mgmt enabled null", DocsConfigs.withDocumentEnabled(sharedWithOpsInfo(), "mgmt", null), both),
                Arguments.of(
                        "mgmt enabled false",
                        DocsConfigs.withDocumentEnabled(sharedWithOpsInfo(), "mgmt", false),
                        both));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledVariants")
    @DisplayName("An entry's enabled is tri-state and apidocs.enabled false disables every document")
    void enabledIsTriStateAndGlobalSwitchDisablesEverything(
            String variant, JsonObject config, List<String> expectedDocuments) {
        // Given the shared declarations plus the documented application ops
        RestApplications view = sharedViewWith(ConfigRegistrations.opsApi(OpsApi.class, true));

        if (expectedDocuments == null) {
            // When the providers run
            ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

            // Then the message names mgmt, its declaring interface and the setting, and states that the
            // interface declares no @ApiDocs
            String message = failure.getMessage();
            assertNamesApplication(message, "mgmt", MGMT_INTERFACE);
            assertTrue(message.contains("apidocs.documents.mgmt.enabled"), message);
            assertTrue(message.contains("declares no @ApiDocs"), message);
        } else {
            // When the providers run
            EnabledDocuments documents = resolve(config, view);

            // Then exactly the expected documents are enabled
            assertEquals(expectedDocuments, names(documents));
        }
    }

    static Stream<Arguments> infoSources() {
        JsonObject configured = new JsonObject().put("title", "Configured").put("version", "9");
        JsonObject blankTitle =
                new JsonObject().put("title", "  ").put("version", "1").put("description", MARKER);
        return Stream.of(
                Arguments.of(
                        "annotated info without configured info",
                        AnnotatedInfoApi.class,
                        null,
                        new InfoConfig("Annotated", "2", "From code"),
                        null),
                Arguments.of(
                        "configured info replaces the annotated info as a whole",
                        AnnotatedInfoApi.class,
                        configured.copy(),
                        new InfoConfig("Configured", "9", null),
                        null),
                Arguments.of(
                        "blank annotated title without configured info",
                        BlankTitleInfoApi.class,
                        null,
                        null,
                        List.of(
                                BLANK_TITLE_INFO_INTERFACE,
                                "@OpenAPIDefinition.info",
                                "apidocs.documents.public.info")),
                Arguments.of(
                        "annotation on a superinterface only",
                        InheritedInfoApi.class,
                        null,
                        null,
                        List.of(INHERITED_INFO_INTERFACE, "apidocs.documents.public.info")),
                Arguments.of(
                        "configured info beside a blank annotated title",
                        BlankTitleInfoApi.class,
                        configured.copy(),
                        new InfoConfig("Configured", "9", null),
                        null),
                Arguments.of(
                        "blank configured title without annotation",
                        PublicApi.class,
                        blankTitle,
                        null,
                        List.of(PUBLIC_INTERFACE, "apidocs.documents.public.info.title")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoSources")
    @DisplayName("A document's info comes from configuration, else from its declaring interface's own annotation")
    void infoComesFromConfigurationOrTheDeclaringInterface(
            String variant,
            Class<?> declaringInterface,
            JsonObject configuredInfo,
            InfoConfig expectedInfo,
            List<String> expectedFragments) {
        // Given application public declared by the interface under test beside mgmt, and the shared
        // configuration with the public entry's info replaced by the configured info, or removed
        RestApplications view = view(ConfigRegistrations.publicApi(declaringInterface), ConfigRegistrations.mgmtApi());
        JsonObject config = DocsConfigs.shared();
        DocsConfigs.document(config, "public").remove("info");
        if (configuredInfo != null) {
            DocsConfigs.document(config, "public").put("info", configuredInfo);
        }

        if (expectedInfo != null) {
            // When the providers run
            EnabledDocuments documents =
                    assertDoesNotThrow(() -> resolve(config, view), () -> "\"" + variant + "\": the document resolves");

            // Then public resolves with exactly the expected info
            assertEquals(List.of("public"), names(documents));
            assertEquals(expectedInfo, documents.all().get(0).info());
        } else {
            // When the providers run
            ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

            // Then the message names public, the declaring interface and the offending source
            String message = failure.getMessage();
            assertTrue(message.contains("'public'"), message);
            for (String fragment : expectedFragments) {
                assertTrue(message.contains(fragment), message);
            }
            assertFalse(message.contains(MARKER), message);
        }
    }

    static Stream<Arguments> serverUrls() {
        return Stream.of(
                Arguments.of(null, true),
                Arguments.of("/", true),
                Arguments.of("/api/public", true),
                Arguments.of("https://" + MARKER + ".example/v1", true),
                Arguments.of("http://127.0.0.1:8080", true),
                Arguments.of("HTTPS://" + MARKER + ".example", true),
                Arguments.of("", false),
                Arguments.of(" ", false),
                Arguments.of("api/" + MARKER, false),
                Arguments.of("//" + MARKER + ".example/v1", false),
                Arguments.of("ftp://" + MARKER + ".example", false),
                Arguments.of("https:" + MARKER, false),
                Arguments.of("https:///" + MARKER, false),
                Arguments.of("https://" + MARKER + ".example/{version}", false),
                Arguments.of("mailto:" + MARKER + "@example.com", false));
    }

    @ParameterizedTest(name = "serverUrl \"{0}\" valid={1}")
    @MethodSource("serverUrls")
    @DisplayName("A document's serverUrl is an absolute http or https URI with a host, or an absolute path")
    void serverUrlIsAnAbsoluteHttpUriOrAbsolutePath(String value, boolean valid) {
        // Given the shared declarations and the shared configuration with the public entry's serverUrl
        // set to the value (absent when null), and the same with the public document disabled
        RestApplications view = sharedView();
        JsonObject enabledConfig = DocsConfigs.shared();
        JsonObject disabledConfig = DocsConfigs.withDocumentEnabled(DocsConfigs.shared(), "public", false);
        if (value != null) {
            DocsConfigs.document(enabledConfig, "public").put("serverUrl", value);
            DocsConfigs.document(disabledConfig, "public").put("serverUrl", value);
        }

        if (valid) {
            // When the providers run with the document enabled
            EnabledDocuments documents = resolve(enabledConfig, view);

            // Then public resolves carrying the configured text unchanged
            assertEquals(List.of("public"), names(documents));
            assertEquals(value, documents.all().get(0).serverUrl());
        } else {
            // When the providers run with the document enabled
            ConfigurationException failure =
                    assertThrows(ConfigurationException.class, () -> resolve(enabledConfig, view));

            // Then the message names public, its declaring interface and the setting, and echoes no value
            String message = failure.getMessage();
            assertNamesApplication(message, "public", PUBLIC_INTERFACE);
            assertTrue(message.contains("apidocs.documents.public.serverUrl"), message);
        }

        // When the providers run with the document disabled, whatever the value
        EnabledDocuments none = resolve(disabledConfig, view);

        // Then no document resolves and nothing is thrown
        assertTrue(none.isEmpty());
    }

    /** The shared configuration plus {@code apidocs.documents.ops} {@code {enabled: false}}. */
    private static JsonObject sharedWithOpsDisabled() {
        return sharedWithEntry("ops", new JsonObject().put("enabled", false));
    }

    static Stream<Arguments> apiDocsShapes() {
        return Stream.of(
                Arguments.of(
                        "protected without a scheme",
                        OpsProtectedWithoutSchemeApi.class,
                        OPS_PROTECTED_WITHOUT_SCHEME_INTERFACE,
                        true,
                        sharedWithOpsInfo(),
                        SECURITY_SCHEME_ATTRIBUTE,
                        null),
                Arguments.of(
                        "protected with a blank scheme",
                        OpsProtectedBlankSchemeApi.class,
                        OPS_PROTECTED_BLANK_SCHEME_INTERFACE,
                        true,
                        sharedWithOpsInfo(),
                        SECURITY_SCHEME_ATTRIBUTE,
                        null),
                Arguments.of(
                        "public with a scheme",
                        OpsPublicWithSchemeApi.class,
                        OPS_PUBLIC_WITH_SCHEME_INTERFACE,
                        true,
                        sharedWithOpsInfo(),
                        SECURITY_SCHEME_ATTRIBUTE,
                        null),
                Arguments.of(
                        "public with roles",
                        OpsPublicWithRolesApi.class,
                        OPS_PUBLIC_WITH_ROLES_INTERFACE,
                        true,
                        sharedWithOpsInfo(),
                        ROLES_ALLOWED_ATTRIBUTE,
                        null),
                Arguments.of(
                        "protected with a blank role entry",
                        OpsBlankRoleEntryApi.class,
                        OPS_BLANK_ROLE_ENTRY_INTERFACE,
                        true,
                        sharedWithOpsInfo(),
                        ROLES_ALLOWED_ATTRIBUTE,
                        null),
                Arguments.of(
                        "protected with an empty role entry",
                        OpsEmptyRoleEntryApi.class,
                        OPS_EMPTY_ROLE_ENTRY_INTERFACE,
                        true,
                        sharedWithOpsInfo(),
                        ROLES_ALLOWED_ATTRIBUTE,
                        null),
                Arguments.of(
                        "well-formed protected declaration",
                        OpsValidProtectedApi.class,
                        null,
                        true,
                        sharedWithOpsInfo(),
                        null,
                        List.of("ops", "public")),
                Arguments.of(
                        "well-formed public declaration",
                        OpsApi.class,
                        null,
                        true,
                        sharedWithOpsInfo(),
                        null,
                        List.of("ops", "public")),
                Arguments.of(
                        "protected without a scheme on an inactive registration",
                        OpsProtectedWithoutSchemeApi.class,
                        null,
                        false,
                        sharedWithOpsInfo(),
                        null,
                        List.of("public")),
                Arguments.of(
                        "protected without a scheme with its document disabled",
                        OpsProtectedWithoutSchemeApi.class,
                        OPS_PROTECTED_WITHOUT_SCHEME_INTERFACE,
                        true,
                        sharedWithOpsDisabled(),
                        SECURITY_SCHEME_ATTRIBUTE,
                        null),
                Arguments.of(
                        "protected without a scheme with apidocs disabled",
                        OpsProtectedWithoutSchemeApi.class,
                        null,
                        true,
                        DocsConfigs.withApidocsEnabled(sharedWithOpsInfo(), false),
                        null,
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiDocsShapes")
    @DisplayName("A malformed @ApiDocs from a hand-written registration fails closed at startup")
    void apiDocsShapeIsRecheckedForHandWrittenRegistrations(
            String variant,
            Class<?> declaringInterface,
            String declaringInterfaceName,
            boolean active,
            JsonObject config,
            String expectedAttribute,
            List<String> expectedDocuments) {
        // Given the shared declarations plus application ops declared by the interface under test,
        // registered by hand as generated code would register it
        RestApplications view = sharedViewWith(ConfigRegistrations.opsApi(declaringInterface, active));

        if (expectedAttribute != null) {
            // When the providers run
            RestConfigurationException failure =
                    assertThrows(RestConfigurationException.class, () -> resolve(config, view));

            // Then the message names ops, its declaring interface and the attribute, and echoes no
            // attribute value
            String message = failure.getMessage();
            assertNamesApplication(message, "ops", declaringInterfaceName);
            assertTrue(message.contains(expectedAttribute), message);
            assertFalse(message.contains("bearerAuth"), message);
            assertFalse(message.contains("admin"), message);
        } else {
            // When the providers run
            EnabledDocuments documents = resolve(config, view);

            // Then the declaration is accepted and exactly the expected documents are enabled
            assertEquals(expectedDocuments, names(documents));
        }
    }

    // ---- supporting checks: key order, key escaping, several shape violations ----

    /** The binary name of the public declaration that both names a scheme and lists a role. */
    private static final String OPS_PUBLIC_WITH_SCHEME_AND_ROLES_INTERFACE =
            "dev.vertique.rest.openapi.docs.fixture.startup.config.OpsPublicWithSchemeAndRolesApi";

    /** The control character BEL (code point 7). */
    private static final String BELL = String.valueOf((char) 7);

    /** A single backslash. */
    private static final String BACKSLASH = "\\";

    @Test
    @DisplayName("Among several invalid document entry keys, the failure names the first key in sorted order")
    void invalidEntryKeysAreCheckedInSortedOrder() {
        // Given the shared declarations and the shared configuration plus two entries breaking the
        // grammar, inserted as Zz then Aa, so insertion order differs from sorted order
        RestApplications view = sharedView();
        JsonObject config = sharedWithEntry("Zz", infoEntry(MARKER + "Title", "1"));
        DocsConfigs.document(config, "Aa").mergeIn(infoEntry(MARKER + "Title", "1"));
        assertEquals(
                List.of("public", "Zz", "Aa"),
                List.copyOf(
                        DocsConfigs.apidocs(config).getJsonObject("documents").fieldNames()),
                "the entries keep their insertion order");

        // When the providers run
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

        // Then the message names only the sorted-first key's path and the grammar, and echoes no value
        String message = failure.getMessage();
        assertTrue(message.contains("apidocs.documents.Aa"), message);
        assertFalse(message.contains("apidocs.documents.Zz"), message);
        assertTrue(message.contains(NAME_GRAMMAR), message);
        assertFalse(message.contains(MARKER), message);
    }

    @Test
    @DisplayName("A control character in an echoed entry key appears as a four-digit Unicode escape, never raw")
    void controlCharacterInAnEntryKeyIsEscaped() {
        // Given the shared declarations and the shared configuration plus an entry whose key holds BEL
        RestApplications view = sharedView();
        String key = "a" + BELL + "b";
        JsonObject config = sharedWithEntry(key, infoEntry(MARKER + "Title", "1"));

        // When the providers run
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> resolve(config, view));

        // Then the path names the key with BEL written as backslash, u, 0007, and the raw BEL is absent
        String message = failure.getMessage();
        assertTrue(message.contains("apidocs.documents.a" + BACKSLASH + "u0007b"), message);
        assertFalse(message.contains(BELL), message);
        assertFalse(message.contains(MARKER), message);
    }

    @Test
    @DisplayName("Several @ApiDocs shape violations of one application fail together, one sorted line each")
    void severalShapeViolationsAreReportedTogetherInSortedOrder() {
        // Given the shared declarations plus application ops, whose public document both names a
        // scheme and lists a role, registered by hand as generated code would register it
        RestApplications view = sharedViewWith(ConfigRegistrations.opsApi(OpsPublicWithSchemeAndRolesApi.class, true));

        // When the providers run
        RestConfigurationException failure =
                assertThrows(RestConfigurationException.class, () -> resolve(sharedWithOpsInfo(), view));

        // Then one failure names ops and its interface, and lists the rolesAllowed line before the
        // securityScheme line, echoing neither attribute value
        String message = failure.getMessage();
        assertNamesApplication(message, "ops", OPS_PUBLIC_WITH_SCHEME_AND_ROLES_INTERFACE);
        int roles = message.indexOf(ROLES_ALLOWED_ATTRIBUTE);
        int scheme = message.indexOf(SECURITY_SCHEME_ATTRIBUTE);
        assertTrue(roles >= 0, message);
        assertTrue(scheme >= 0, message);
        assertTrue(roles < scheme, "the rolesAllowed line comes before the securityScheme line: " + message);
    }
}
