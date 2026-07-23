// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.SqlScanner;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SqlScanner} — verifies that parenthesis-depth-aware SQL scanning
 * correctly detects patterns at top-level while ignoring matches inside subqueries, CTEs,
 * window functions, and quoted literals.
 */
@DisplayName("SqlScanner")
class SqlScannerTest {

    private static final Pattern ORDER_BY = Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

    @Nested
    @DisplayName("top-level ORDER BY detection")
    class TopLevelOrderBy {

        @Test
        @DisplayName("detects top-level ORDER BY")
        void containsAtTopLevel_detectsTopLevelOrderBy() {
            assertTrue(SqlScanner.containsAtTopLevel("SELECT * FROM items ORDER BY name", ORDER_BY));
        }

        @Test
        @DisplayName("ignores ORDER BY inside subquery")
        void containsAtTopLevel_ignoresOrderByInSubquery() {
            String sql = "SELECT * FROM (SELECT * FROM items ORDER BY name) sub";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("ignores ORDER BY inside CTE")
        void containsAtTopLevel_ignoresOrderByInCte() {
            String sql = "WITH cte AS (SELECT * FROM items ORDER BY name) SELECT * FROM cte";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("ignores ORDER BY inside window function")
        void containsAtTopLevel_ignoresOrderByInWindowFunction() {
            String sql = "SELECT *, ROW_NUMBER() OVER (PARTITION BY cat ORDER BY name) FROM items";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("ignores ORDER BY inside single-quoted string literal")
        void containsAtTopLevel_ignoresOrderByInSingleQuotedString() {
            String sql = "SELECT * FROM items WHERE comment = 'ORDER BY name'";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("ignores ORDER BY inside double-quoted identifier")
        void containsAtTopLevel_ignoresOrderByInDoubleQuotedIdentifier() {
            String sql = "SELECT \"ORDER BY\" FROM items";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }
    }

    @Nested
    @DisplayName("top-level LIMIT detection")
    class TopLevelLimit {

        @Test
        @DisplayName("detects top-level LIMIT")
        void containsAtTopLevel_detectsTopLevelLimit() {
            assertTrue(SqlScanner.containsAtTopLevel("SELECT * FROM items LIMIT 10", LIMIT));
        }

        @Test
        @DisplayName("ignores LIMIT inside subquery")
        void containsAtTopLevel_ignoresLimitInSubquery() {
            String sql = "SELECT * FROM (SELECT * FROM items LIMIT 10) sub";
            assertFalse(SqlScanner.containsAtTopLevel(sql, LIMIT));
        }
    }

    @Nested
    @DisplayName("nested parentheses")
    class NestedParentheses {

        @Test
        @DisplayName("handles deeply nested parentheses")
        void containsAtTopLevel_handlesNestedParentheses() {
            String sql = "SELECT * FROM (SELECT * FROM (SELECT * FROM items ORDER BY name) a) b";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("detects pattern after closing all nested parentheses")
        void containsAtTopLevel_detectsAfterNestedParentheses() {
            String sql = "SELECT * FROM (SELECT * FROM items ORDER BY name) sub ORDER BY id";
            assertTrue(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }
    }

    @Nested
    @DisplayName("quoted string escaping")
    class QuotedStringEscaping {

        @Test
        @DisplayName("handles escaped single quotes inside string literals")
        void containsAtTopLevel_handlesEscapedSingleQuotes() {
            // The string 'ORDER '' BY' contains an escaped quote — still a literal
            String sql = "SELECT * FROM items WHERE x = 'ORDER '' BY'";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("handles escaped double quotes inside identifiers")
        void containsAtTopLevel_handlesEscapedDoubleQuotes() {
            // "OR""DER BY" is a single double-quoted identifier with an embedded quote
            String sql = "SELECT \"OR\"\"DER BY\" FROM items";
            assertFalse(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }

        @Test
        @DisplayName("detects pattern after a string literal closes")
        void containsAtTopLevel_detectsAfterStringLiteralCloses() {
            String sql = "SELECT * FROM items WHERE x = 'some value' ORDER BY name";
            assertTrue(SqlScanner.containsAtTopLevel(sql, ORDER_BY));
        }
    }

    @Nested
    @DisplayName("case insensitivity")
    class CaseInsensitivity {

        @Test
        @DisplayName("matches lowercase order by")
        void containsAtTopLevel_caseInsensitive_lower() {
            assertTrue(SqlScanner.containsAtTopLevel("SELECT * FROM items order by name", ORDER_BY));
        }

        @Test
        @DisplayName("matches mixed-case Order By")
        void containsAtTopLevel_caseInsensitive_mixed() {
            assertTrue(SqlScanner.containsAtTopLevel("SELECT * FROM items Order By name", ORDER_BY));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty string returns false")
        void containsAtTopLevel_emptyString() {
            assertFalse(SqlScanner.containsAtTopLevel("", ORDER_BY));
        }

        @Test
        @DisplayName("string with only whitespace returns false")
        void containsAtTopLevel_whitespaceOnly() {
            assertFalse(SqlScanner.containsAtTopLevel("   ", ORDER_BY));
        }

        @Test
        @DisplayName("null sql throws NullPointerException")
        void containsAtTopLevel_nullSql_throws() {
            assertThrows(NullPointerException.class, () -> SqlScanner.containsAtTopLevel(null, ORDER_BY));
        }

        @Test
        @DisplayName("null pattern throws NullPointerException")
        void containsAtTopLevel_nullPattern_throws() {
            assertThrows(NullPointerException.class, () -> SqlScanner.containsAtTopLevel("SELECT 1", null));
        }
    }
}
