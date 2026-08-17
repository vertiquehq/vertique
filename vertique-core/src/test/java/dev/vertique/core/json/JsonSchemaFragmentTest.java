// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonSchemaFragment} parsing, dialect-keyword rejection, canonicalization,
 * and detachment from the caller's input (PRD-JSON-005 §6.2).
 */
class JsonSchemaFragmentTest {

    /** A value that must never appear in a rejection message. */
    private static final String SENSITIVE_VALUE = "SENSITIVE-VALUE-DO-NOT-ECHO";

    /** A property name that must never appear in a rejection message. */
    private static final String SENSITIVE_PROPERTY = "sensitivePropertyName";

    /** The dialect keywords the fragment contract rejects at any depth. */
    private static final List<String> DIALECT_KEYWORDS =
            List.of("$schema", "$id", "$anchor", "$dynamicAnchor", "$ref", "$dynamicRef", "$defs");

    @Test
    @DisplayName("parse rejects null, blank, malformed, and non-object-root input without echoing it")
    void parseRejectsNullBlankMalformedAndNonObjectRoot() {
        assertBoundedRejection(null);
        assertBoundedRejection("");
        assertBoundedRejection("   ");
        assertBoundedRejection(SENSITIVE_VALUE + " not json");
        assertBoundedRejection("{\"type\":\"string\"} " + SENSITIVE_VALUE);
        assertBoundedRejection("[\"" + SENSITIVE_VALUE + "\"]");
        assertBoundedRejection("42");
        assertBoundedRejection("\"" + SENSITIVE_VALUE + "\"");
        assertBoundedRejection("null");
    }

    @Test
    @DisplayName("parse rejects every dialect keyword at any depth, including as a properties member name")
    void parseRejectsDialectKeywordsAtAnyDepth() {
        for (String keyword : DIALECT_KEYWORDS) {
            // Root-level occurrence.
            assertKeywordRejection(keyword, "{\"type\":\"object\",\"" + keyword + "\":\"" + SENSITIVE_VALUE + "\"}");
            // Nested occurrence inside a property schema.
            assertKeywordRejection(
                    keyword,
                    "{\"properties\":{\"" + SENSITIVE_PROPERTY + "\":{\"" + keyword + "\":\"" + SENSITIVE_VALUE
                            + "\"}}}");
            // Deliberate over-rejection: the keyword used as a property name under "properties".
            assertKeywordRejection(
                    keyword,
                    "{\"properties\":{\"" + keyword + "\":{\"type\":\"string\",\"title\":\"" + SENSITIVE_VALUE
                            + "\"}}}");
            // Occurrence nested inside an array element.
            assertKeywordRejection(keyword, "{\"allOf\":[{\"" + keyword + "\":\"" + SENSITIVE_VALUE + "\"}]}");
        }
    }

    @Test
    @DisplayName("canonicalJson sorts object keys recursively without reordering arrays")
    void canonicalJsonSortsKeysRecursivelyWithoutReorderingArrays() {
        JsonSchemaFragment fragment = JsonSchemaFragment.parse("{\"b\":1,\"a\":{\"d\":[3,1,2],\"c\":0}}");

        assertEquals("{\"a\":{\"c\":0,\"d\":[3,1,2]},\"b\":1}", fragment.canonicalJson());
    }

    @Test
    @DisplayName("canonicalJson is a detached, stable, compact value independent of interleaved parses")
    void canonicalJsonDetachedFromCallerTree() {
        String spacedInput = "{\n  \"type\" : \"string\",\n  \"maxLength\" : 100\n}";
        JsonSchemaFragment fragment = JsonSchemaFragment.parse(spacedInput);
        String first = fragment.canonicalJson();

        // A fresh serialization, not the caller's text: compact, no source whitespace retained.
        assertEquals("{\"maxLength\":100,\"type\":\"string\"}", first);

        // Interleaved parses of unrelated fragments must not disturb this instance.
        JsonSchemaFragment other = JsonSchemaFragment.parse("{\"type\":\"integer\",\"minimum\":0}");
        JsonSchemaFragment another = JsonSchemaFragment.parse("{\"b\":[1,2],\"a\":{\"z\":true}}");

        assertEquals(first, fragment.canonicalJson());
        assertEquals(first, JsonSchemaFragment.parse(spacedInput).canonicalJson());
        assertEquals("{\"minimum\":0,\"type\":\"integer\"}", other.canonicalJson());
        assertEquals("{\"a\":{\"z\":true},\"b\":[1,2]}", another.canonicalJson());
    }

    private static void assertBoundedRejection(String schemaJson) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> JsonSchemaFragment.parse(schemaJson));
        assertMessageIsBoundedAndValueFree(failure);
    }

    private static void assertKeywordRejection(String keyword, String schemaJson) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> JsonSchemaFragment.parse(schemaJson));
        assertMessageIsBoundedAndValueFree(failure);
        assertTrue(
                failure.getMessage().contains(keyword),
                "rejection message must identify the violated rule for " + keyword + ": " + failure.getMessage());
    }

    private static void assertMessageIsBoundedAndValueFree(IllegalArgumentException failure) {
        String message = failure.getMessage();
        assertNotNull(message, "rejection message must not be null");
        assertFalse(message.isBlank(), "rejection message must not be blank");
        assertTrue(message.length() <= 256, "rejection message must stay bounded: " + message);
        assertFalse(message.contains(SENSITIVE_VALUE), "rejection message must not echo fragment content: " + message);
        assertFalse(
                message.contains(SENSITIVE_PROPERTY), "rejection message must not echo fragment content: " + message);
    }
}
