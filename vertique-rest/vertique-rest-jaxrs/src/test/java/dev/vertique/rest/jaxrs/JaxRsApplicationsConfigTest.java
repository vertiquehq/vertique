// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the strict {@code jaxrs.applications} keyed-object section that {@code
 * vertique-rest-jaxrs} parses through {@link RestModule#parseJaxRsApplications(JsonObject,
 * ConfigParser)} into {@link RestApplicationConfig} (T024, FR-029, AC-029.2).
 *
 * <p>TP-001 proves each key is injected into the record's name in section order, and that an
 * absent or empty section yields an empty list. TP-002 proves the raw-key check rejects a
 * non-object section, a non-object entry, and any entry key other than {@code openapiPath} before
 * parsing. TP-003 proves a blank configured {@code openapiPath} fails naming its path. TP-004
 * proves {@link JaxRsConfig} still ignores the section and exposes no applications accessor.
 *
 * <p>Every row calls the package-private parse method directly with the framework's real {@link
 * DefaultConfigParser}, as rest-core's {@code JaxRsSecurityConfigProviderTest} does. Rejections
 * assert the exact message, and that it never echoes a configuration value: every value that must
 * not be echoed carries the marker {@code zq7}; keys carry no marker.
 */
class JaxRsApplicationsConfigTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    /** Carried by every configuration value that a rejection message must never echo. */
    private static final String MARKER = "zq7";

    private static final String SECTION_NOT_OBJECT = "'jaxrs.applications' must be a JSON object";

    // --- TP-001 ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyedApplicationsRows")
    @DisplayName("jaxrs.applications parses as a keyed object, each key injected into the record's name")
    void keyedApplicationsParseWithInjectedNames(
            String label, String configJson, List<RestApplicationConfig> expected) {
        List<RestApplicationConfig> actual = RestModule.parseJaxRsApplications(new JsonObject(configJson), PARSER);

        assertEquals(expected, actual, label);
    }

    private static Stream<Arguments> keyedApplicationsRows() {
        return Stream.of(
                arguments(
                        "three entries keep section order; an absent or null openapiPath binds null",
                        """
                        {"jaxrs":{"applications":{"public":{"openapiPath":"public.yaml"},"mgmt":{},"partner":{"openapiPath":null}}}}
                        """,
                        List.of(
                                new RestApplicationConfig("public", "public.yaml"),
                                new RestApplicationConfig("mgmt", null),
                                new RestApplicationConfig("partner", null))),
                arguments("an empty applications section yields an empty list", """
                        {"jaxrs":{"applications":{}}}
                        """, List.of()),
                arguments("an absent applications section yields an empty list", """
                        {"jaxrs":{}}
                        """, List.of()),
                arguments("an absent jaxrs section yields an empty list", """
                        {}
                        """, List.of()));
    }

    // --- TP-002 ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("strictSectionRows")
    @DisplayName("jaxrs.applications rejects a non-object section or entry and any key but openapiPath, before parsing")
    void strictSectionRejectsNonObjectsAndUnknownKeys(String label, String applicationsJson, Expected expected) {
        assertOutcome(label, applicationsJson, expected);
    }

    private static Stream<Arguments> strictSectionRows() {
        return Stream.of(
                arguments("(a) the section is a string", """
                        "zq7"
                        """, rejected(SECTION_NOT_OBJECT)),
                arguments("(b) the section is null", """
                        null
                        """, rejected(SECTION_NOT_OBJECT)),
                arguments("(c) the section is an array", """
                        ["zq7"]
                        """, rejected(SECTION_NOT_OBJECT)),
                arguments("(d) an entry is a string", """
                        {"api":"zq7"}
                        """, rejected(invalidEntries("'jaxrs.applications.api'"))),
                arguments("(e) an entry is null", """
                        {"api":null}
                        """, rejected(invalidEntries("'jaxrs.applications.api'"))),
                arguments(
                        "(f) an unknown key beside openapiPath",
                        """
                        {"api":{"openapiPath":"zq7-secret.yaml","timeoutMs":5}}
                        """,
                        rejected(invalidEntries("'jaxrs.applications.api.timeoutMs'"))),
                arguments("(g) an explicit name key", """
                        {"api":{"name":"api"}}
                        """, rejected(invalidEntries("'jaxrs.applications.api.name'"))),
                arguments(
                        "(h) a case variant of openapiPath",
                        """
                        {"api":{"OpenapiPath":"zq7.yaml"}}
                        """,
                        rejected(invalidEntries("'jaxrs.applications.api.OpenapiPath'"))),
                arguments(
                        "(i) unknown keys in two entries, api before mgmt",
                        """
                        {"mgmt":{"extra":true},"api":{"bogus":1}}
                        """,
                        rejected(invalidEntries("'jaxrs.applications.api.bogus', 'jaxrs.applications.mgmt.extra'"))),
                arguments(
                        "(j) an unknown key beside an undeserializable openapiPath fails the key check first",
                        """
                        {"api":{"openapiPath":{"zq7":1},"bogus":1}}
                        """,
                        rejected(invalidEntries("'jaxrs.applications.api.bogus'"))),
                arguments(
                        "(k) a non-object entry and a bad key in another entry aggregate",
                        """
                        {"mgmt":"zq7","api":{"bogus":1}}
                        """,
                        rejected(invalidEntries("'jaxrs.applications.api.bogus', 'jaxrs.applications.mgmt'"))),
                arguments(
                        "control: an entry with openapiPath parses",
                        """
                        {"api":{"openapiPath":"a.json"}}
                        """,
                        parsed(new RestApplicationConfig("api", "a.json"))),
                arguments("control: an empty entry parses", """
                        {"api":{}}
                        """, parsed(new RestApplicationConfig("api", null))));
    }

    // --- TP-003 ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankOpenapiPathRows")
    @DisplayName("a blank configured openapiPath fails startup naming its path")
    void blankOpenapiPathFailsNamingThePath(String label, String applicationsJson, Expected expected) {
        assertOutcome(label, applicationsJson, expected);
    }

    private static Stream<Arguments> blankOpenapiPathRows() {
        return Stream.of(
                arguments(
                        "(a) an empty openapiPath", """
                        {"api":{"openapiPath":""}}
                        """, rejected(blankValues("'jaxrs.applications.api.openapiPath'"))),
                arguments(
                        "(b) a whitespace-only openapiPath",
                        """
                        {"api":{"openapiPath":"   "}}
                        """,
                        rejected(blankValues("'jaxrs.applications.api.openapiPath'"))),
                arguments(
                        "(c) blank openapiPath in two entries, api first",
                        """
                        {"mgmt":{"openapiPath":" "},"api":{"openapiPath":""}}
                        """,
                        rejected(blankValues(
                                "'jaxrs.applications.api.openapiPath', 'jaxrs.applications.mgmt.openapiPath'"))),
                arguments(
                        "control: a non-blank openapiPath parses",
                        """
                        {"api":{"openapiPath":"api.yaml"}}
                        """,
                        parsed(new RestApplicationConfig("api", "api.yaml"))),
                arguments(
                        "control: an absent openapiPath binds null",
                        """
                        {"api":{}}
                        """,
                        parsed(new RestApplicationConfig("api", null))),
                arguments(
                        "control: an explicit null openapiPath binds null",
                        """
                        {"api":{"openapiPath":null}}
                        """,
                        parsed(new RestApplicationConfig("api", null))));
    }

    // --- TP-004 ---

    @Test
    @DisplayName(
            "JaxRsConfig still parses the jaxrs section, ignoring applications, and exposes no applications accessor")
    void jaxRsConfigStillIgnoresTheApplicationsSection() {
        JsonObject section = new JsonObject("""
                {"basePath":"/api/*","zq7Unknown":"zq7","applications":{"api":{"openapiPath":"api.yaml"}},"security":{"requireExplicitPolicy":true}}
                """);

        JaxRsConfig config = PARSER.parse(section, JaxRsConfig.class);

        assertEquals("/api/*", config.basePath());
        assertTrue(config.security().requireExplicitPolicy());
        assertTrue(
                Arrays.stream(JaxRsConfig.class.getMethods())
                        .map(Method::getName)
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("application")),
                "JaxRsConfig must expose no applications accessor");
    }

    // --- Helpers ---

    private static List<RestApplicationConfig> parseApplications(String applicationsJson) {
        JsonObject config = new JsonObject("{\"jaxrs\":{\"applications\":%s}}".formatted(applicationsJson));
        return RestModule.parseJaxRsApplications(config, PARSER);
    }

    private static void assertOutcome(String label, String applicationsJson, Expected expected) {
        switch (expected) {
            case Rejected rejected -> assertRejected(applicationsJson, rejected.message());
            case Parsed parsed -> assertEquals(parsed.configs(), parseApplications(applicationsJson), label);
        }
    }

    /**
     * Asserts that parsing {@code applicationsJson} as the {@code jaxrs.applications} value throws a
     * {@link ConfigurationException} whose message equals {@code expectedMessage} and never contains
     * {@link #MARKER}.
     */
    private static void assertRejected(String applicationsJson, String expectedMessage) {
        ConfigurationException failure =
                assertThrows(ConfigurationException.class, () -> parseApplications(applicationsJson));
        String message = String.valueOf(failure.getMessage());
        assertAll(
                () -> assertFalse(
                        message.contains(MARKER), () -> "the message must not echo a configuration value: " + message),
                () -> assertEquals(expectedMessage, message));
    }

    private static String invalidEntries(String quotedPaths) {
        return "Invalid entries or keys under 'jaxrs.applications': " + quotedPaths
                + "; each entry must be a JSON object whose only key is 'openapiPath'";
    }

    private static String blankValues(String quotedPaths) {
        return "Blank values under 'jaxrs.applications': " + quotedPaths
                + "; set 'openapiPath' to a non-blank location or omit it";
    }

    private static Expected rejected(String message) {
        return new Rejected(message);
    }

    private static Expected parsed(RestApplicationConfig... configs) {
        return new Parsed(List.of(configs));
    }

    // --- Fixtures ---

    /** A row's expected outcome: an exact rejection message, or the full parsed list. */
    private sealed interface Expected permits Rejected, Parsed {}

    private record Rejected(String message) implements Expected {}

    private record Parsed(List<RestApplicationConfig> configs) implements Expected {}
}
