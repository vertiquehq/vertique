// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import static dev.vertique.config.placeholder.PlaceholderParser.parse;
import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.placeholder.PlaceholderParser.Literal;
import dev.vertique.config.placeholder.PlaceholderParser.Malformed;
import dev.vertique.config.placeholder.PlaceholderParser.Placeholder;
import dev.vertique.config.placeholder.PlaceholderParser.Segment;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PlaceholderParser}.
 *
 * <p>Verifies the placeholder grammar: all syntactic forms (bare reference, default, escape,
 * nested), edge cases (blank key, lone {@code $}, unterminated braces), and the
 * {@link PlaceholderParser#containsPlaceholder(String)} convenience method.
 *
 * <p>Tests assert on the <em>concatenation</em> of literal segments, not on the exact
 * segmentation count, to give the implementation freedom in how it merges adjacent literals.
 */
class PlaceholderParserTest {

    // --- Helper methods ---

    /**
     * Returns the concatenated text of all {@link Literal} segments in the given result list,
     * in order.
     *
     * @param segments the parsed segment list
     * @return concatenated literal text
     */
    private static String allLiterals(List<Segment> segments) {
        return segments.stream()
                .filter(s -> s instanceof Literal)
                .map(s -> ((Literal) s).text())
                .collect(Collectors.joining());
    }

    /**
     * Asserts that the given segment list contains exactly one {@link Placeholder} at the given
     * position within the placeholder-typed segments (index among placeholders only).
     *
     * @param segments the segment list
     * @param index    zero-based index among placeholder segments only
     * @return the placeholder for further assertions
     */
    private static Placeholder placeholderAt(List<Segment> segments, int index) {
        List<Placeholder> placeholders = segments.stream()
                .filter(s -> s instanceof Placeholder)
                .map(s -> (Placeholder) s)
                .collect(Collectors.toList());
        assertTrue(index < placeholders.size(), "Expected at least " + (index + 1) + " Placeholder segment(s)");
        return placeholders.get(index);
    }

    /**
     * Returns all {@link Placeholder} segments from the list.
     *
     * @param segments the segment list
     * @return ordered list of placeholder segments
     */
    private static List<Placeholder> allPlaceholders(List<Segment> segments) {
        return segments.stream()
                .filter(s -> s instanceof Placeholder)
                .map(s -> (Placeholder) s)
                .collect(Collectors.toList());
    }

    /**
     * Returns all {@link Malformed} segments from the list.
     *
     * @param segments the segment list
     * @return ordered list of malformed segments
     */
    private static List<Malformed> allMalformed(List<Segment> segments) {
        return segments.stream()
                .filter(s -> s instanceof Malformed)
                .map(s -> (Malformed) s)
                .collect(Collectors.toList());
    }

    // --- Basic literal ---

    @Nested
    @DisplayName("Plain literal (no placeholders)")
    class PlainLiteralTests {

        @Test
        @DisplayName("empty input yields empty list")
        void emptyInput() {
            List<Segment> result = parse("");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("plain text with no dollar signs is a single Literal")
        void plainText() {
            List<Segment> result = parse("hello world");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals(0, allMalformed(result).size());
            assertEquals("hello world", allLiterals(result));
        }

        @Test
        @DisplayName("lone dollar sign is treated as literal")
        void loneDollar() {
            List<Segment> result = parse("$");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals("$", allLiterals(result));
        }

        @Test
        @DisplayName("dollar not followed by brace is literal")
        void dollarNotFollowedByBrace() {
            List<Segment> result = parse("$x");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals("$x", allLiterals(result));
        }

        @Test
        @DisplayName("trailing dollar sign is literal")
        void trailingDollar() {
            List<Segment> result = parse("hello$");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals("hello$", allLiterals(result));
        }

        @Test
        @DisplayName("trailing backslash is literal")
        void trailingBackslash() {
            List<Segment> result = parse("hello\\");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals("hello\\", allLiterals(result));
        }
    }

    // --- Single placeholder ---

    @Nested
    @DisplayName("Single placeholder")
    class SinglePlaceholderTests {

        @Test
        @DisplayName("${key} parses as Placeholder with null default")
        void bareReference() {
            List<Segment> result = parse("${key}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("key", phs.get(0).key());
            assertNull(phs.get(0).defaultValue());
        }

        @Test
        @DisplayName("${key:default} parses with correct key and default")
        void withDefault() {
            List<Segment> result = parse("${key:default}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("key", phs.get(0).key());
            assertEquals("default", phs.get(0).defaultValue());
        }

        @Test
        @DisplayName("${key:} parses with empty-string default (not null)")
        void emptyDefault() {
            List<Segment> result = parse("${key:}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("key", phs.get(0).key());
            assertEquals("", phs.get(0).defaultValue());
        }

        @Test
        @DisplayName("no colon means no default (null)")
        void noColonNullDefault() {
            List<Segment> result = parse("${mykey}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertNull(phs.get(0).defaultValue());
        }
    }

    // --- URL-shaped default ---

    @Nested
    @DisplayName("URL default with multiple colons")
    class UrlDefaultTests {

        @Test
        @DisplayName("${endpoint:https://collector:4317} has key 'endpoint' and default 'https://collector:4317'")
        void urlDefault() {
            List<Segment> result = parse("${endpoint:https://collector:4317}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("endpoint", phs.get(0).key());
            assertEquals("https://collector:4317", phs.get(0).defaultValue());
        }

        @Test
        @DisplayName("${url:http://host:8080/path?a=1&b=2} captures full URL")
        void fullUrl() {
            List<Segment> result = parse("${url:http://host:8080/path?a=1&b=2}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("url", phs.get(0).key());
            assertEquals("http://host:8080/path?a=1&b=2", phs.get(0).defaultValue());
        }
    }

    // --- Multi-placeholder concatenation ---

    @Nested
    @DisplayName("Multi-placeholder concatenation")
    class MultiPlaceholderTests {

        @Test
        @DisplayName("jdbc:postgresql://${db.host}:${db.port}/app — two placeholders, literal colons outside")
        void jdbcUrl() {
            // The top-level colons between placeholders are outside any ${} and are literals.
            String input = "jdbc:postgresql://${db.host}:${db.port}/app";
            List<Segment> result = parse(input);

            // Two placeholder segments
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(2, phs.size());

            // First placeholder
            assertEquals("db.host", phs.get(0).key());
            assertNull(phs.get(0).defaultValue());

            // Second placeholder
            assertEquals("db.port", phs.get(1).key());
            assertNull(phs.get(1).defaultValue());

            // The literal text (all Literal segments joined) must contain the surrounding literals
            String literals = allLiterals(result);
            assertTrue(literals.contains("jdbc:postgresql://"), "Expected 'jdbc:postgresql://' in literals");
            assertTrue(literals.contains(":"), "Expected ':' separator between host and port in literals");
            assertTrue(literals.contains("/app"), "Expected '/app' suffix in literals");

            // No malformed segments
            assertEquals(0, allMalformed(result).size());
        }

        @Test
        @DisplayName("adjacent placeholders ${a}${b} produce two placeholder segments")
        void adjacentPlaceholders() {
            List<Segment> result = parse("${a}${b}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(2, phs.size());
            assertEquals("a", phs.get(0).key());
            assertEquals("b", phs.get(1).key());
        }

        @Test
        @DisplayName("text prefix and suffix around placeholder preserved as literals")
        void prefixSuffix() {
            List<Segment> result = parse("prefix_${key}_suffix");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("key", phs.get(0).key());
            String literals = allLiterals(result);
            assertTrue(literals.contains("prefix_"));
            assertTrue(literals.contains("_suffix"));
        }
    }

    // --- Nested defaults ---

    @Nested
    @DisplayName("Nested placeholders in defaults")
    class NestedDefaultTests {

        @Test
        @DisplayName("${a:${b}} has key 'a' and default text '${b}'")
        void oneLevel() {
            List<Segment> result = parse("${a:${b}}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("a", phs.get(0).key());
            assertEquals("${b}", phs.get(0).defaultValue());
        }

        @Test
        @DisplayName("${a:${b:${c}}} has key 'a' and default text '${b:${c}}'")
        void twoLevels() {
            List<Segment> result = parse("${a:${b:${c}}}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("a", phs.get(0).key());
            assertEquals("${b:${c}}", phs.get(0).defaultValue());
        }

        @Test
        @DisplayName("${a:${b}} followed by literal text")
        void nestedFollowedByLiteral() {
            List<Segment> result = parse("${a:${b}} rest");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("a", phs.get(0).key());
            assertEquals("${b}", phs.get(0).defaultValue());
            assertTrue(allLiterals(result).contains(" rest"));
        }
    }

    // --- Escape handling ---

    @Nested
    @DisplayName("Escape handling")
    class EscapeTests {

        @Test
        @DisplayName("\\${not.a.ref} → literal '${not.a.ref}'")
        void escapedPlaceholder() {
            List<Segment> result = parse("\\${not.a.ref}");
            // No Placeholder or Malformed segments; all literal
            assertEquals(0, allPlaceholders(result).size());
            assertEquals(0, allMalformed(result).size());
            assertEquals("${not.a.ref}", allLiterals(result));
        }

        @Test
        @DisplayName("prefix before escape: text\\${a} → literal 'text${a}'")
        void prefixBeforeEscape() {
            List<Segment> result = parse("text\\${a}");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals("text${a}", allLiterals(result));
        }

        @Test
        @DisplayName("escaped placeholder followed by real placeholder: \\${x}${real}")
        void escapedFollowedByReal() {
            List<Segment> result = parse("\\${x}${real}");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            assertEquals("real", phs.get(0).key());
            // The escape produces literal '${x}'
            assertTrue(allLiterals(result).contains("${x}"));
        }

        @Test
        @DisplayName("\\\\${a} → literal '\\' + literal '${a}' (second backslash escapes the placeholder)")
        void doubleBackslashBeforePlaceholder() {
            // Char sequence: \, \, $, {, a, }
            // At pos 0: char '\' followed by '\' (not '$') → emit '\', advance 1
            // At pos 1: char '\' followed by '$' '{' → escape → emit '${', advance 3
            // At pos 4: char 'a', then '}' → literal 'a}'
            // Combined literal: '\' + '${' + 'a}' = '\${a}'
            List<Segment> result = parse("\\\\${a}");
            assertEquals(0, allPlaceholders(result).size());
            assertEquals(0, allMalformed(result).size());
            assertEquals("\\${a}", allLiterals(result));
        }

        @Test
        @DisplayName("containsPlaceholder returns false for escaped-only string")
        void escapedOnlyFalseForContains() {
            assertFalse(PlaceholderParser.containsPlaceholder("\\${escaped}"));
        }
    }

    // --- Malformed / unterminated ---

    @Nested
    @DisplayName("Malformed / unterminated placeholders")
    class MalformedTests {

        @Test
        @DisplayName("unterminated ${abc → Malformed('${abc')")
        void unterminatedSimple() {
            List<Segment> result = parse("${abc");
            List<Malformed> malformed = allMalformed(result);
            assertEquals(1, malformed.size());
            assertEquals("${abc", malformed.get(0).rawText());
        }

        @Test
        @DisplayName("unterminated nested ${a:${b} → Malformed for the whole outer placeholder")
        void unterminatedNested() {
            // ${a: starts, then ${b} closes the inner but the outer ${a:... never closes
            List<Segment> result = parse("${a:${b}");
            List<Malformed> malformed = allMalformed(result);
            assertEquals(1, malformed.size());
            // rawText must start with ${
            assertTrue(malformed.get(0).rawText().startsWith("${"));
        }

        @Test
        @DisplayName("blank key ${} → Malformed")
        void blankKeyEmptyBraces() {
            List<Segment> result = parse("${}");
            List<Malformed> malformed = allMalformed(result);
            assertEquals(1, malformed.size());
            assertEquals("${}", malformed.get(0).rawText());
        }

        @Test
        @DisplayName("blank key ${:default} → Malformed (key before colon is blank)")
        void blankKeyWithDefault() {
            List<Segment> result = parse("${:default}");
            List<Malformed> malformed = allMalformed(result);
            assertEquals(1, malformed.size());
            assertEquals("${:default}", malformed.get(0).rawText());
        }

        @Test
        @DisplayName("valid placeholder followed by unterminated placeholder")
        void validThenMalformed() {
            // ${real} closes at depth 0, then ${abc never closes → one Placeholder + one Malformed
            List<Segment> result = parse("${real}${abc");
            List<Malformed> malformed = allMalformed(result);
            List<Placeholder> phs = allPlaceholders(result);
            // One valid placeholder with key 'real'
            assertEquals(1, phs.size());
            assertEquals("real", phs.get(0).key());
            // One Malformed for the unterminated '${abc'
            assertEquals(1, malformed.size());
            assertEquals("${abc", malformed.get(0).rawText());
        }

        @Test
        @DisplayName("${abc${real} is entirely one Malformed due to brace-depth counting")
        void nestedUnclosedIsOneMalformed() {
            // Depth tracking: ${abc starts depth=1, ${real increments to depth=2,
            // the single } brings it back to depth=1, end-of-input → whole thing is unterminated
            List<Segment> result = parse("${abc${real}");
            List<Malformed> malformed = allMalformed(result);
            assertEquals(1, malformed.size());
            assertEquals("${abc${real}", malformed.get(0).rawText());
            assertEquals(0, allPlaceholders(result).size());
        }
    }

    // --- containsPlaceholder convenience method ---

    @Nested
    @DisplayName("containsPlaceholder convenience method")
    class ContainsPlaceholderTests {

        @Test
        @DisplayName("plain text → false")
        void plainTextFalse() {
            assertFalse(PlaceholderParser.containsPlaceholder("no placeholders here"));
        }

        @Test
        @DisplayName("${key} → true")
        void singlePlaceholderTrue() {
            assertTrue(PlaceholderParser.containsPlaceholder("${key}"));
        }

        @Test
        @DisplayName("text with placeholder → true")
        void mixedTrue() {
            assertTrue(PlaceholderParser.containsPlaceholder("prefix ${key} suffix"));
        }

        @Test
        @DisplayName("unterminated ${abc → true (Malformed is a placeholder-class segment)")
        void unterminatedTrue() {
            assertTrue(PlaceholderParser.containsPlaceholder("${abc"));
        }

        @Test
        @DisplayName("empty string → false")
        void emptyFalse() {
            assertFalse(PlaceholderParser.containsPlaceholder(""));
        }

        @Test
        @DisplayName("lone $ → false")
        void loneDollarFalse() {
            assertFalse(PlaceholderParser.containsPlaceholder("$"));
        }

        @Test
        @DisplayName("$x → false")
        void dollarXFalse() {
            assertFalse(PlaceholderParser.containsPlaceholder("$x"));
        }
    }

    // --- Whitespace key rule ---

    @Nested
    @DisplayName("Whitespace key rule (no trimming)")
    class WhitespaceKeyTests {

        @Test
        @DisplayName("${ a } is a valid Placeholder with key ' a ' (whitespace preserved)")
        void whitespaceKeyPreserved() {
            List<Segment> result = parse("${ a }");
            List<Placeholder> phs = allPlaceholders(result);
            assertEquals(1, phs.size());
            // Key whitespace is preserved as-is (not trimmed)
            assertEquals(" a ", phs.get(0).key());
        }

        @Test
        @DisplayName("${ : val} has blank key before colon → Malformed")
        void blankKeyWithSpaceAndDefault() {
            // Key part is ' ' (a single space), which is blank after the trim-check
            // Wait — the spec says "blank key" means blank. A space-only key IS blank.
            List<Segment> result = parse("${ : val}");
            // ' ' before ':' is blank → Malformed
            List<Malformed> malformed = allMalformed(result);
            assertEquals(1, malformed.size());
        }
    }
}
