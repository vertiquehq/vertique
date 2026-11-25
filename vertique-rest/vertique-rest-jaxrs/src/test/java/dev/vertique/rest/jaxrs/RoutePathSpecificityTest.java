// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoutePathSpecificity}, the route-registration specificity ordering that
 * prevents path-parameter routes from shadowing more-specific static routes (the auth-bypass
 * defect). Each test pins a pairwise specificity ordering or a full sort outcome.
 */
class RoutePathSpecificityTest {

    /**
     * Asserts that {@code more} is strictly more specific than {@code less}: it sorts earlier under the
     * most-specific-first comparator (negative compare) and its inverse is positive.
     *
     * @param more the path template expected to be more specific
     * @param less the path template expected to be less specific
     */
    private static void assertMoreSpecific(String more, String less) {
        assertTrue(
                RoutePathSpecificity.compare(more, less) < 0,
                () -> "'" + more + "' should sort before (be more specific than) '" + less + "'");
        assertTrue(
                RoutePathSpecificity.compare(less, more) > 0,
                () -> "'" + less + "' should sort after (be less specific than) '" + more + "'");
    }

    /**
     * Asserts that two templates have equal rank: the same key and segment comparison, so only their
     * name orders them, in either direction.
     */
    private static void assertEqualRank(String a, String b) {
        assertTrue(RoutePathSpecificity.equalRank(a, b), () -> "'" + a + "' and '" + b + "' should rank equal");
        assertTrue(RoutePathSpecificity.equalRank(b, a), () -> "'" + b + "' and '" + a + "' should rank equal");
    }

    @Nested
    @DisplayName("static vs parameter")
    class StaticVsParameter {

        @Test
        @DisplayName("/hello/secured is more specific than /hello/{name}")
        void staticBeatsParamAtSamePosition() {
            assertMoreSpecific("/hello/secured", "/hello/{name}");
        }

        @Test
        @DisplayName("a static segment wins at the first differing index: /a/b/{y} beats /a/{x}/b")
        void firstDifferingSegmentDecides() {
            // index 0: both LITERAL (a). index 1: /a/b/{y} has LITERAL (b) vs /a/{x}/b PARAM ({x}).
            // LITERAL > PARAM at the first differing index, so /a/b/{y} is more specific.
            assertMoreSpecific("/a/b/{y}", "/a/{x}/b");
        }
    }

    @Nested
    @DisplayName("regex vs plain parameter")
    class RegexVsParam {

        @Test
        @DisplayName("a regex-constrained param is more specific than a plain param")
        void regexBeatsPlainParam() {
            assertMoreSpecific("/n/{id:\\d+}", "/n/{id}");
        }

        @Test
        @DisplayName("a literal is more specific than a regex-constrained param")
        void literalBeatsRegex() {
            assertMoreSpecific("/n/active", "/n/{id:\\d+}");
        }
    }

    @Nested
    @DisplayName("slash inside a regex constraint")
    class SlashInsideConstraint {

        @Test
        @DisplayName("a static segment beats a regex whose constraint contains a slash")
        void staticBeatsRegexWithSlash() {
            assertMoreSpecific("/files/static", "/files/{name:[^/]+}");
        }

        @Test
        @DisplayName("a nested-brace constraint containing a slash stays one segment")
        void staticBeatsNestedBraceRegexWithSlash() {
            assertMoreSpecific("/files/static", "/files/{name:[a-z/]{1,3}}");
        }

        @Test
        @DisplayName("a {p:.+} constraint counts as one segment, so a longer path still wins")
        void greedyConstraintIsOneSegment() {
            assertMoreSpecific("/a/b/c", "/a/{p:.+}");
            assertMoreSpecific("/a/{p:[^/]+}/c", "/a/{p:[^/]+}");
        }

        @Test
        @DisplayName("a literal tail of a separator-spanning constraint ranks as a literal segment")
        void spanningConstraintLiteralTailBeatsParam() {
            assertMoreSpecific("/repos/{slug:[^/]+/admin}", "/repos/{owner:\\w+}/{name}");
            assertMoreSpecific("/repos/{slug:[^/]+/[^/]+}", "/repos/{owner}/{name}");
            assertMoreSpecific("/repos/{slug:[^/]+\\/admin}", "/repos/{owner}/{name}");
        }

        @Test
        @DisplayName("a constraint without a separator, or with one in a class or group, stays one segment")
        void constraintWithoutLiteralSeparatorStaysOneSegment() {
            assertMoreSpecific("/a/{x:abc}", "/a/{x}");
            assertMoreSpecific("/a/b", "/a/{x:abc}");
            assertMoreSpecific("/a/{p:[^/]+}/{q}", "/a/{p:(x/y)}");
        }

        @Test
        @DisplayName("an alternation or an optional separator counts as one regex variable: equal rank")
        void alternationOrOptionalSeparatorRanksAsOneRegexVariable() {
            assertEqualRank("/r/{a:\\w+}", "/r/{p:b/c|a}");
            assertEqualRank("/r/{a:\\w+}", "/r/{p:[^/]+/?admin}");
            assertEqualRank("/r/{a:\\w+}", "/r/{p:x/*y}");
            assertEqualRank("/r/{a:\\w+}", "/r/{p:x\\/?y}");
        }

        @Test
        @DisplayName("a mixed segment stays literal and beats a regex segment")
        void mixedSegmentStaysLiteral() {
            assertMoreSpecific("/files/{name}.txt", "/files/{name:[^/]+}");
        }

        @Test
        @DisplayName("unbalanced braces never throw")
        void unbalancedInputDoesNotThrow() {
            assertDoesNotThrow(() -> RoutePathSpecificity.compare("/a/{x:[^/]+", "/a/b"));
            assertDoesNotThrow(() -> RoutePathSpecificity.compare("/a/x}/b", "/a/{y"));
            assertDoesNotThrow(() -> RoutePathSpecificity.compare("/{{{/", "/}}}"));
        }
    }

    @Nested
    @DisplayName("length tie-break")
    class LengthTieBreak {

        @Test
        @DisplayName("when all shared positions tie, the longer path is more specific")
        void longerPathWinsWhenPrefixTies() {
            assertMoreSpecific("/a/b/c", "/a/b");
        }

        @Test
        @DisplayName("longer path with matching kinds at shared positions is more specific")
        void longerParamPathWins() {
            assertMoreSpecific("/a/{x}/{y}", "/a/{x}");
        }
    }

    @Nested
    @DisplayName("determinism and totality")
    class Determinism {

        @Test
        @DisplayName("equal templates compare to zero")
        void equalTemplatesAreZero() {
            assertEquals(0, RoutePathSpecificity.compare("/a/{x}", "/a/{x}"));
        }

        @Test
        @DisplayName("structurally-identical distinct templates fall back to lexicographic order")
        void lexicographicTieBreak() {
            // Same shape (LITERAL/PARAM), distinct strings → stable lexicographic order.
            assertMoreSpecific("/a/{x}", "/b/{x}");
        }

        @Test
        @DisplayName("a representative resource's routes sort static-and-regex before plain params")
        void fullSortOrdersMostSpecificFirst() {
            List<String> paths = new ArrayList<>(List.of(
                    "/hello/{name}",
                    "/hello/secured",
                    "/hello/admin",
                    "/hello/scoped",
                    "/hello/limited/{name}",
                    "/hello/greetings/{name}",
                    "/hello/{id:\\d+}"));
            paths.sort(RoutePathSpecificity.MOST_SPECIFIC_FIRST);

            // Every static second-segment route and the regex route must precede the plain {name} route.
            int nameIdx = paths.indexOf("/hello/{name}");
            assertTrue(paths.indexOf("/hello/secured") < nameIdx, "static /hello/secured must precede /hello/{name}");
            assertTrue(paths.indexOf("/hello/admin") < nameIdx, "static /hello/admin must precede /hello/{name}");
            assertTrue(paths.indexOf("/hello/scoped") < nameIdx, "static /hello/scoped must precede /hello/{name}");
            assertTrue(
                    paths.indexOf("/hello/{id:\\d+}") < nameIdx, "regex /hello/{id:\\d+} must precede /hello/{name}");
            // Deeper static-prefixed routes (3 segments) also precede the 2-segment {name} route.
            assertTrue(
                    paths.indexOf("/hello/limited/{name}") < nameIdx,
                    "deeper static-prefixed /hello/limited/{name} must precede /hello/{name}");
        }
    }

    @Nested
    @DisplayName("literal-character ranking")
    class LiteralCharacters {

        @Test
        @DisplayName("a catch-all /a/{p:.+} ranks behind /a/{id}/secret, whatever the names")
        void catchAllRanksBehindLiteralTail() {
            assertMoreSpecific("/a/{id}/secret", "/a/{p:.+}");
            assertMoreSpecific("/a/{zz}/secret", "/a/{aa:.+}");
        }

        @Test
        @DisplayName("a mixed segment with a longer literal beats one with a shorter literal")
        void mixedSegmentLiteralLength() {
            assertMoreSpecific("/f/~admin.txt", "/f/{name}.txt");
            assertMoreSpecific("/f/~admin.txt", "/f/{zz}.txt");
        }

        @Test
        @DisplayName("reversal: a later literal tail outweighs an earlier static segment")
        void laterLiteralTailWins() {
            assertMoreSpecific("/api/{tenant}/accounts", "/api/admin/{x}");
            assertMoreSpecific("/u/{name}/profile", "/u/{id:\\d+}/{action}");
        }

        @Test
        @DisplayName("reversal: at equal L and V, a constrained variable beats a plain one")
        void regexBreaksTieAtEqualLiteralsAndVariables() {
            assertMoreSpecific("/a/{x:\\w+}/b", "/a/b/{y}");
        }

        @Test
        @DisplayName("residual: an explicit broad regex beats a plain variable at equal L and V")
        void broadRegexBeatsPlainVariable() {
            assertMoreSpecific("/a/{p:.+}", "/a/{id}");
        }

        @Test
        @DisplayName("equal L, V and R fall back to the segment-kind tie-break: /a/b/{y} before /a/{x}/b")
        void segmentTieBreakOrdersEqualKeys() {
            assertMoreSpecific("/a/b/{y}", "/a/{x}/b");
            assertFalse(RoutePathSpecificity.equalRank("/a/b/{y}", "/a/{x}/b"));
            assertEquals(
                    RoutePathSpecificity.rankOf("/a/b/{y}").key(),
                    RoutePathSpecificity.rankOf("/a/{x}/b").key());
        }

        @Test
        @DisplayName("a colon segment is a variable in a plain template and literal in a regex template")
        void colonSegmentCountsAsVariableOnlyInPlainTemplate() {
            assertEquals(
                    new RoutePathSpecificity.Key(3, 1, 0),
                    RoutePathSpecificity.rankOf("/a/:id").key());
            assertEquals(
                    new RoutePathSpecificity.Key(7, 1, 1),
                    RoutePathSpecificity.rankOf("/a/:id/{n:\\d+}").key());
        }

        @Test
        @DisplayName("a separator-spanning constraint counts each separator and plain piece as literals")
        void spanningConstraintKey() {
            assertEquals(
                    new RoutePathSpecificity.Key(13, 1, 1),
                    RoutePathSpecificity.rankOf("/repos/{slug:[^/]+/admin}").key());
        }
    }

    @Nested
    @DisplayName("alternation variants")
    class Variants {

        @Test
        @DisplayName("a top-level alternation splits into one variant per alternative, most specific first")
        void topLevelAlternationSplits() {
            assertEquals(List.of("/r/{p:b/admin}", "/r/{p:a}"), RoutePathSpecificity.variants("/r/{p:a|b/admin}"));
        }

        @Test
        @DisplayName("a template without an alternation is its own single variant")
        void noAlternationIsSingleVariant() {
            assertEquals(List.of("/a/{id}"), RoutePathSpecificity.variants("/a/{id}"));
            assertEquals(List.of("/a/{id:\\d+}"), RoutePathSpecificity.variants("/a/{id:\\d+}"));
        }

        @Test
        @DisplayName("no split inside a character class")
        void noSplitInClass() {
            assertEquals(List.of("/a/{p:[a|b]}"), RoutePathSpecificity.variants("/a/{p:[a|b]}"));
        }

        @Test
        @DisplayName("no split inside a group")
        void noSplitInGroup() {
            assertEquals(List.of("/a/{p:(a|b)}"), RoutePathSpecificity.variants("/a/{p:(a|b)}"));
        }

        @Test
        @DisplayName("no split for an escaped bar")
        void noSplitForEscapedBar() {
            assertEquals(List.of("/a/{p:a\\|b}"), RoutePathSpecificity.variants("/a/{p:a\\|b}"));
        }

        @Test
        @DisplayName("no split when the constraint has a quoted section")
        void noSplitWithQuote() {
            assertEquals(List.of("/a/{p:\\Qa|b\\E}"), RoutePathSpecificity.variants("/a/{p:\\Qa|b\\E}"));
            assertEquals(List.of("/a/{p:x/y|\\Qa|b\\E}"), RoutePathSpecificity.variants("/a/{p:x/y|\\Qa|b\\E}"));
        }

        @Test
        @DisplayName("no split when the constraint has a numeric backreference")
        void noSplitWithBackreference() {
            assertEquals(List.of("/a/{p:(x)\\1|y}"), RoutePathSpecificity.variants("/a/{p:(x)\\1|y}"));
        }

        @Test
        @DisplayName("no split when the constraint has an inline flag")
        void noSplitWithInlineFlag() {
            assertEquals(List.of("/a/{p:(?i)a|b/c}"), RoutePathSpecificity.variants("/a/{p:(?i)a|b/c}"));
            assertEquals(List.of("/a/{p:(?i:a)|b/c}"), RoutePathSpecificity.variants("/a/{p:(?i:a)|b/c}"));
        }

        @Test
        @DisplayName("alternatives that contribute equally merge into one alternation")
        void equalContributionMerges() {
            assertEquals(List.of("/a/{p:ab|cd}"), RoutePathSpecificity.variants("/a/{p:ab|cd}"));
            assertEquals(List.of("/a/{p:x/y|z/w}"), RoutePathSpecificity.variants("/a/{p:x/y|z/w}"));
            assertEquals(
                    List.of("/a/{p:b/admin}", "/a/{p:ab|cd}"), RoutePathSpecificity.variants("/a/{p:ab|cd|b/admin}"));
        }

        @Test
        @DisplayName("two alternation variables multiply, ordered most specific first")
        void productOfTwoVariables() {
            List<String> variants = RoutePathSpecificity.variants("/a/{p:x|y/z}/{q:m|n/o}");
            assertEquals(4, variants.size());
            assertEquals("/a/{p:y/z}/{q:n/o}", variants.get(0));
            assertEquals("/a/{p:x}/{q:m}", variants.get(3));
            assertEquals(List.of("/a/{p:y/z}/{q:m}", "/a/{p:x}/{q:n/o}"), variants.subList(1, 3));
        }

        @Test
        @DisplayName("each variant ranks on its own alternative")
        void variantsRankPerAlternative() {
            List<RoutePathSpecificity.Rank> ranked = RoutePathSpecificity.rankedVariants("/r/{p:a|b/admin}");
            assertEquals(2, ranked.size());
            assertEquals(new RoutePathSpecificity.Key(10, 0, 0), ranked.get(0).key());
            assertEquals(new RoutePathSpecificity.Key(3, 1, 1), ranked.get(1).key());
        }

        @Test
        @DisplayName("the variant /r/{p:b/admin} outranks the open /r/{a:\\w+}/{b}")
        void variantBeatsLongerOpenRoute() {
            assertMoreSpecific("/r/{p:b/admin}", "/r/{a:\\w+}/{b}");
        }

        @Test
        @DisplayName("no split for an unusual character class")
        void noSplitForUnusualClasses() {
            assertEquals(List.of("/a/{p:[]|x]}"), RoutePathSpecificity.variants("/a/{p:[]|x]}"));
            assertEquals(List.of("/a/{p:[^]|x]}"), RoutePathSpecificity.variants("/a/{p:[^]|x]}"));
            assertEquals(List.of("/a/{p:[[a]|b]}"), RoutePathSpecificity.variants("/a/{p:[[a]|b]}"));
        }

        @Test
        @DisplayName("no split when the constraint declares a named group, but a lookbehind does not block it")
        void namedGroupBlocksSplit() {
            assertEquals(List.of("/a/{p:(?<g>a)|b/c}"), RoutePathSpecificity.variants("/a/{p:(?<g>a)|b/c}"));
            assertEquals(2, RoutePathSpecificity.variants("/a/{p:(?<=x)a|b/c}").size());
        }

        @Test
        @DisplayName("no split when an alternative does not compile")
        void noSplitForUncompilableAlternative() {
            assertEquals(List.of("/a/{p:+a|b/c}"), RoutePathSpecificity.variants("/a/{p:+a|b/c}"));
            assertEquals(List.of("/a/{p:*a|b/c}"), RoutePathSpecificity.variants("/a/{p:*a|b/c}"));
        }

        @Test
        @DisplayName("alternative text is kept exactly, not trimmed")
        void alternativeTextIsKeptExactly() {
            List<String> variants = RoutePathSpecificity.variants("/x/{p:b/admin | [^/]+}");
            assertEquals(2, variants.size());
            assertTrue(variants.stream().anyMatch(v -> v.contains("b/admin }")), variants::toString);
        }

        @Test
        @DisplayName("a literal outranks the same text inside a variable: /h/~a/b before /h/{p:~a/b}")
        void literalBeatsVariableWithSameText() {
            assertMoreSpecific("/h/~a/b", "/h/{p:~a/b}");
        }
    }

    @Nested
    @DisplayName("a template without variables at equal literal characters")
    class VariableFreeTemplate {

        @Test
        @DisplayName("a static route beats a variable route with the same literal text whose variable can be empty")
        void staticBeatsEmptyMatchingVariable() {
            assertMoreSpecific("/files", "/files{ext:(\\.json)?}");
            assertMoreSpecific("/a/b", "/a/b{p:.*}");
        }
    }
}
