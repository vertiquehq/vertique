// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code apidocs} configuration and the validation the documentation module runs when at least
 * one document is enabled. The declared applications come from the shared fixture's registrations
 * through the component's {@link RestApplications} view.
 */
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
                Arguments.of("/zq7/docs", true));
    }

    @ParameterizedTest(name = "apidocs.path {0} valid={1}")
    @MethodSource("prefixValues")
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
    void absentEnabledOnAnEntryParsesToNull() {
        // Given an entry without enabled
        JsonObject config = DocsConfigs.shared();

        // When the configuration provider runs
        ApidocsConfig apidocs = OpenApiDocsModule.apidocsConfig(config, parser);

        // Then the entry's enabled is null, not a default
        assertNull(apidocs.documents().get(0).enabled());
    }

    @Test
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
}
