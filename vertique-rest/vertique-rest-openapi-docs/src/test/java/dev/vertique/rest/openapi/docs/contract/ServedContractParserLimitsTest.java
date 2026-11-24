// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.contract;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit proof of the limits the two parsers of a served contract apply: the YAML parser's code-point
 * limit, Jackson's read limits for nesting, member names, number length and string length (no limit
 * on a document's length), the refusal of content after a JSON value, and the YAML anchors, aliases
 * and merge keys the parser leaves unexpanded. Every input is generated at test time.
 */
class ServedContractParserLimitsTest {

    private static final String SUBJECT = "apidocs.documents.partner: the contract";

    private static final int YAML_CODE_POINT_LIMIT = 3_145_728;

    private static final int NESTING_LIMIT = 1000;

    private static final int NAME_LIMIT = 50_000;

    private static final int YAML_PLAIN_KEY_LIMIT = 1024;

    private static final int NUMBER_LIMIT = 1000;

    private static final int STRING_LIMIT = 20_000_000;

    private static JsonNode parse(String content, boolean yaml) {
        return ServedContractLoader.parse(Buffer.buffer(content), yaml, SUBJECT);
    }

    private static void assertRefused(String content, boolean yaml) {
        RestConfigurationException failure = assertThrows(RestConfigurationException.class, () -> parse(content, yaml));
        assertEquals(SUBJECT + (yaml ? " is not valid YAML" : " is not valid JSON"), failure.getMessage());
    }

    @Test
    @DisplayName("a YAML contract is limited to 3,145,728 code points, counted as characters and not as bytes")
    void yamlCodePointLimit() {
        // "k: " is three code points; each "é" is one code point and two bytes in UTF-8
        String atLimit = "k: " + "é".repeat(YAML_CODE_POINT_LIMIT - 3);
        String overLimit = atLimit + "é";

        assertAll(
                () -> assertEquals(
                        YAML_CODE_POINT_LIMIT - 3,
                        parse(atLimit, true).path("k").textValue().length()),
                () -> assertRefused(overLimit, true));
    }

    @Test
    @DisplayName("a JSON contract has no limit on its length")
    void jsonDocumentLengthHasNoLimit() {
        String longerThanTheYamlLimit = "{\"k\":\"" + "a".repeat(YAML_CODE_POINT_LIMIT + 1) + "\"}";

        assertEquals(
                YAML_CODE_POINT_LIMIT + 1,
                parse(longerThanTheYamlLimit, false).path("k").textValue().length());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"JSON", "YAML"})
    @DisplayName("nesting is limited to 1000 levels in both formats")
    void nestingLimit(String format) {
        boolean yaml = format.equals("YAML");

        assertAll(
                () -> assertDoesNotThrow(() -> parse(nested(NESTING_LIMIT), yaml)),
                () -> assertRefused(nested(NESTING_LIMIT + 1), yaml));
    }

    @Test
    @DisplayName("a JSON member name is limited to 50,000 characters")
    void jsonMemberNameLimit() {
        assertAll(
                () -> assertDoesNotThrow(() -> parse(named(NAME_LIMIT, false), false)),
                () -> assertRefused(named(NAME_LIMIT + 1, false), false));
    }

    @Test
    @DisplayName("a YAML member name written as a plain key is limited to 1,024 characters")
    void yamlPlainKeyLimit() {
        assertAll(
                () -> assertDoesNotThrow(() -> parse(named(YAML_PLAIN_KEY_LIMIT, true), true)),
                () -> assertRefused(named(YAML_PLAIN_KEY_LIMIT + 1, true), true));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"JSON", "YAML"})
    @DisplayName("a number is limited to 1000 digits in both formats")
    void numberLengthLimit(String format) {
        boolean yaml = format.equals("YAML");

        assertAll(
                () -> assertDoesNotThrow(() -> parse(numbered(NUMBER_LIMIT, yaml), yaml)),
                () -> assertRefused(numbered(NUMBER_LIMIT + 1, yaml), yaml));
    }

    @Test
    @DisplayName("a JSON string value is limited to 20,000,000 characters")
    void jsonStringLimit() {
        assertAll(
                () -> assertDoesNotThrow(() -> parse(stringOf(STRING_LIMIT), false)),
                () -> assertRefused(stringOf(STRING_LIMIT + 1), false));
    }

    @Test
    @DisplayName("a JSON contract with content after its one JSON value is refused")
    void jsonTrailingContentIsRefused() {
        assertAll(
                () -> assertDoesNotThrow(() -> parse("{\"openapi\":\"3.1.0\"}\n", false)),
                () -> assertRefused("{\"openapi\":\"3.1.0\"}{\"openapi\":\"3.1.0\"}", false),
                () -> assertRefused("{\"openapi\":\"3.1.0\"} x", false));
    }

    @Test
    @DisplayName("a YAML contract holds exactly one document")
    void yamlSecondDocumentIsRefused() {
        assertAll(
                () -> assertDoesNotThrow(() -> parse("openapi: 3.1.0\n", true)),
                () -> assertRefused("openapi: 3.1.0\n---\nopenapi: 3.1.0\n", true));
    }

    @Test
    @DisplayName("a YAML alias is served as its anchor's name, not expanded")
    void yamlAliasIsNotExpanded() {
        JsonNode tree = parse("a: &shared value\nb: *shared\n", true);

        assertAll(
                () -> assertEquals("value", tree.path("a").textValue()),
                () -> assertEquals("shared", tree.path("b").textValue()));
    }

    @Test
    @DisplayName("a YAML merge key is a member of its own, not merged")
    void yamlMergeKeyIsNotMerged() {
        JsonNode tree = parse("base: &base\n  x: 1\nchild:\n  <<: *base\n  y: 2\n", true);

        JsonNode child = tree.path("child");
        assertAll(
                () -> assertEquals(2, child.size()),
                () -> assertEquals("base", child.path("<<").textValue()),
                () -> assertEquals(2, child.path("y").intValue()),
                () -> assertEquals(true, child.path("x").isMissingNode()));
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    private static String named(int length, boolean yaml) {
        String name = "n".repeat(length);
        return yaml ? name + ": 1\n" : "{\"" + name + "\":1}";
    }

    private static String numbered(int digits, boolean yaml) {
        String number = "1".repeat(digits);
        return yaml ? "k: " + number + "\n" : "{\"k\":" + number + "}";
    }

    private static String stringOf(int length) {
        return "{\"k\":\"" + "a".repeat(length) + "\"}";
    }
}
