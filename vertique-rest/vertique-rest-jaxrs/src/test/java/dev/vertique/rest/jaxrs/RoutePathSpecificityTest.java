// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
