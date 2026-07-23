// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.query.SqlIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SqlIdentifier} validation and quoting behaviour.
 *
 * <p>{@code validate()} must accept well-formed SQL identifiers (including dot-qualified names) and
 * reject anything containing illegal characters, SQL injection fragments, or structural problems.
 * {@code quote()} must wrap each segment in double quotes and double any embedded double-quote
 * characters.
 */
class SqlIdentifierTest {

    // --- Validate ---

    @Nested
    @DisplayName("validate()")
    class Validate {

        // -- Valid simple identifiers --

        @Test
        @DisplayName("accepts simple lowercase name")
        void accepts_simpleName() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("name"));
        }

        @Test
        @DisplayName("accepts 'id'")
        void accepts_id() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("id"));
        }

        @Test
        @DisplayName("accepts identifier starting with underscore")
        void accepts_underscorePrefix() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("_x"));
        }

        @Test
        @DisplayName("accepts snake_case column name")
        void accepts_snakeCase() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("created_at"));
        }

        @Test
        @DisplayName("accepts single-letter lowercase")
        void accepts_singleLetterLower() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("a"));
        }

        @Test
        @DisplayName("accepts single-letter uppercase")
        void accepts_singleLetterUpper() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("A"));
        }

        @Test
        @DisplayName("accepts camelCase identifier")
        void accepts_camelCase() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("camelCase"));
        }

        // -- Valid dot-qualified identifiers --

        @Test
        @DisplayName("accepts two-segment qualified name")
        void accepts_twoSegmentQualified() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("t.name"));
        }

        @Test
        @DisplayName("accepts three-segment qualified name")
        void accepts_threeSegmentQualified() {
            assertDoesNotThrow(() -> SqlIdentifier.validate("schema.table.column"));
        }

        // -- Returns identifier for fluent use --

        @Test
        @DisplayName("returns the validated identifier unchanged")
        void returns_identifierUnchanged() {
            assertEquals("name", SqlIdentifier.validate("name"));
        }

        @Test
        @DisplayName("returns dot-qualified identifier unchanged")
        void returns_dotQualifiedUnchanged() {
            assertEquals("t.name", SqlIdentifier.validate("t.name"));
        }

        // -- SQL injection attempts --

        @Test
        @DisplayName("rejects semicolon SQL injection")
        void rejects_semicolonInjection() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("id; DROP TABLE items--"));
        }

        @Test
        @DisplayName("rejects inline comment injection")
        void rejects_inlineCommentInjection() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("col--comment"));
        }

        @Test
        @DisplayName("rejects OR-based injection")
        void rejects_orInjection() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("name OR 1=1"));
        }

        // -- Invalid characters --

        @Test
        @DisplayName("rejects identifier with embedded space")
        void rejects_embeddedSpace() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("col name"));
        }

        @Test
        @DisplayName("rejects identifier with embedded double-quote")
        void rejects_embeddedDoubleQuote() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("col\"name"));
        }

        @Test
        @DisplayName("rejects identifier with wildcard asterisk")
        void rejects_asterisk() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("col*"));
        }

        @Test
        @DisplayName("rejects identifier with parentheses")
        void rejects_parentheses() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("col()"));
        }

        // -- Invalid start character --

        @Test
        @DisplayName("rejects identifier starting with digit")
        void rejects_digitStart() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("1col"));
        }

        // -- Invalid dot patterns --

        @Test
        @DisplayName("rejects identifier starting with dot")
        void rejects_leadingDot() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate(".col"));
        }

        @Test
        @DisplayName("rejects identifier ending with dot")
        void rejects_trailingDot() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("col."));
        }

        @Test
        @DisplayName("rejects four-segment qualified name (too many segments)")
        void rejects_fourSegments() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("a.b.c.d"));
        }

        @Test
        @DisplayName("rejects identifier with consecutive dots (empty segment)")
        void rejects_consecutiveDots() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate("a..b"));
        }

        // -- Null and blank --

        @Test
        @DisplayName("throws NullPointerException for null input")
        void rejects_null() {
            assertThrows(NullPointerException.class, () -> SqlIdentifier.validate(null));
        }

        @Test
        @DisplayName("rejects empty string")
        void rejects_empty() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate(""));
        }

        @Test
        @DisplayName("rejects blank string")
        void rejects_blank() {
            assertThrows(InvalidDataAccessUsageException.class, () -> SqlIdentifier.validate(" "));
        }
    }

    // --- Quote ---

    @Nested
    @DisplayName("quote()")
    class Quote {

        @Test
        @DisplayName("wraps simple identifier in double quotes")
        void quotes_simpleIdentifier() {
            assertEquals("\"name\"", SqlIdentifier.quote("name"));
        }

        @Test
        @DisplayName("wraps each segment of a two-part qualified name")
        void quotes_twoSegmentQualified() {
            assertEquals("\"t\".\"name\"", SqlIdentifier.quote("t.name"));
        }

        @Test
        @DisplayName("wraps each segment of a three-part qualified name")
        void quotes_threeSegmentQualified() {
            assertEquals("\"s\".\"t\".\"c\"", SqlIdentifier.quote("s.t.c"));
        }

        @Test
        @DisplayName("doubles embedded double-quote characters")
        void quotes_embeddedDoubleQuote() {
            // input: a"b  → output: "a""b"
            assertEquals("\"a\"\"b\"", SqlIdentifier.quote("a\"b"));
        }

        @Test
        @DisplayName("throws NullPointerException for null input")
        void rejects_null() {
            assertThrows(NullPointerException.class, () -> SqlIdentifier.quote(null));
        }
    }
}
