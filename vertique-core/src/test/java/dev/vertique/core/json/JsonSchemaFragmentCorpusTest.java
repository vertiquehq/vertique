// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the canonical text {@link JsonSchemaFragment#parse} produces across numbers, nesting, escapes and
 * duplicate keys, so a change of the parser behind it cannot silently change a fragment's canonical form
 * or equality.
 */
class JsonSchemaFragmentCorpusTest {

    private static final String WELL_FORMED = "JSON schema fragment must be well-formed JSON";

    static Stream<Arguments> canonicalForms() {
        return Stream.of(
                Arguments.of(
                        "{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647}",
                        "{\"maximum\":2147483647,\"minimum\":0,\"type\":\"integer\"}"),
                Arguments.of(
                        "{\"maximum\":9223372036854775807,\"minimum\":-9223372036854775808}",
                        "{\"maximum\":9223372036854775807,\"minimum\":-9223372036854775808}"),
                Arguments.of(
                        "{\"maximum\":123456789012345678901234567890}", "{\"maximum\":123456789012345678901234567890}"),
                Arguments.of("{\"multipleOf\":0.1,\"maximum\":2.5}", "{\"maximum\":2.5,\"multipleOf\":0.1}"),
                Arguments.of(
                        "{\"maximum\":1e10,\"minimum\":1E-5,\"x\":1.5e+3}",
                        "{\"maximum\":1.0E10,\"minimum\":1.0E-5,\"x\":1500.0}"),
                Arguments.of("{\"maximum\":1e400}", "{\"maximum\":\"Infinity\"}"),
                Arguments.of("{\"minimum\":1e-400}", "{\"minimum\":0.0}"),
                Arguments.of("{\"minimum\":-0.0,\"maximum\":-0}", "{\"maximum\":0,\"minimum\":-0.0}"),
                Arguments.of(
                        "{\"maximum\":0.30000000000000004,\"minimum\":1.2345678901234567}",
                        "{\"maximum\":0.30000000000000004,\"minimum\":1.2345678901234567}"),
                Arguments.of(
                        "{\"maximum\":3.14159265358979323846264338327950288419716939937510}",
                        "{\"maximum\":3.141592653589793}"),
                Arguments.of(
                        "{\"properties\":{\"z\":{\"type\":\"string\"},\"a\":{\"items\":[{\"b\":1,\"a\":2},[3,{\"y\":1,\"x\":2}]]}}}",
                        "{\"properties\":{\"a\":{\"items\":[{\"a\":2,\"b\":1},[3,{\"x\":2,\"y\":1}]]},\"z\":{\"type\":\"string\"}}}"),
                Arguments.of(
                        "{\"title\":\"caf\\u00e9 \\ud83d\\ude00 \\\"q\\\" \\\\ \\n\",\"description\":\"\\u0000\"}",
                        "{\"description\":\"\\u0000\",\"title\":\"caf\u00e9 \ud83d\ude00 \\\"q\\\" \\\\ \\n\"}"),
                Arguments.of("{\"type\":\"string\",\"type\":\"integer\"}", "{\"type\":\"integer\"}"),
                Arguments.of("{\"enum\":[true,false,null,\"\",[],{}]}", "{\"enum\":[true,false,null,\"\",[],{}]}"));
    }

    @ParameterizedTest
    @MethodSource("canonicalForms")
    @DisplayName("parse produces the pinned canonical text")
    void producesPinnedCanonicalText(String input, String expected) {
        assertEquals(expected, JsonSchemaFragment.parse(input).canonicalJson());
    }

    @ParameterizedTest
    @MethodSource("canonicalForms")
    @DisplayName("fragments with the same canonical text are equal")
    void equalityFollowsCanonicalText(String input, String expected) {
        assertEquals(JsonSchemaFragment.parse(expected), JsonSchemaFragment.parse(input));
    }

    static Stream<String> commentOnlyDifferences() {
        return Stream.of("{/*c*/\"type\":\"string\"}", "{\"type\":\"string\"} // trailing comment");
    }

    /** Comments are accepted in trusted, developer-written fragment text and leave no trace in the canonical form. */
    @ParameterizedTest
    @MethodSource("commentOnlyDifferences")
    @DisplayName("a fragment that differs only by a comment has the comment-free canonical text")
    void commentsAreIgnored(String input) {
        assertEquals("{\"type\":\"string\"}", JsonSchemaFragment.parse(input).canonicalJson());
        assertEquals(JsonSchemaFragment.parse("{\"type\":\"string\"}"), JsonSchemaFragment.parse(input));
    }

    @Test
    @DisplayName("a fragment nested past the parser's depth limit is rejected without echoing input")
    void deepNestingIsRejectedWithTheConstantMessage() {
        String marker = "nested-marker";
        String deep = "{\"" + marker + "\":" + "[".repeat(1100) + "]".repeat(1100) + "}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> JsonSchemaFragment.parse(deep));
        assertEquals(WELL_FORMED, e.getMessage());
        assertFalse(e.getMessage().contains(marker));
    }

    @Test
    @DisplayName("content after the root object is rejected")
    void trailingContentIsRejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> JsonSchemaFragment.parse("{\"type\":\"string\"} {}"));
        assertEquals(WELL_FORMED, e.getMessage());
    }
}
