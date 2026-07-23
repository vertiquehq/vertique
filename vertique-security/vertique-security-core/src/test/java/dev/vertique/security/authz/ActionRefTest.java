// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionRef}.
 *
 * <p>Verifies the frozen action segment grammar: each of {@code subsystem}/{@code resource}/{@code
 * verb} matches {@code ^[a-z][a-z0-9]*$} (lowercase, leading letter), with {@code '.'} as the only
 * separator. Covers canonical {@link ActionRef#value()} rendering, {@link ActionRef#parse(String)}
 * round-trip and rejection of malformed canonical strings (uppercase, leading digit, wrong segment
 * count, blank, non-dot separators), null-segment rejection, and value equality.
 */
class ActionRefTest {

    // --- value() rendering ---

    @Test
    @DisplayName("value() returns canonical dot-separated subsystem.resource.verb")
    void value_returnsCanonicalDotSeparated() {
        assertEquals("cms.content.read", ActionRef.of("cms", "content", "read").value());
    }

    // --- parse() happy path ---

    @Test
    @DisplayName("parse() of a valid canonical string round-trips into segments")
    void parse_validCanonical_roundTrips() {
        ActionRef ref = ActionRef.parse("cms.content.read");
        assertEquals("cms", ref.subsystem());
        assertEquals("content", ref.resource());
        assertEquals("read", ref.verb());
    }

    // --- parse() grammar rejection ---

    @Test
    @DisplayName("parse() rejects an uppercase segment")
    void parse_uppercase_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse("CMS.content.read"));
    }

    @Test
    @DisplayName("parse() rejects a leading-digit segment")
    void parse_leadingDigit_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse("1cms.content.read"));
    }

    @Test
    @DisplayName("parse() rejects more than three segments")
    void parse_tooManySegments_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse("a.b.c.d"));
    }

    @Test
    @DisplayName("parse() rejects fewer than three segments")
    void parse_tooFewSegments_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse("a.b"));
    }

    @Test
    @DisplayName("parse() rejects a blank string")
    void parse_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse("   "));
    }

    @Test
    @DisplayName("parse() rejects an empty string")
    void parse_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse(""));
    }

    @Test
    @DisplayName("parse() rejects a non-dot separator")
    void parse_nonDotSeparator_throws() {
        assertThrows(IllegalArgumentException.class, () -> ActionRef.parse("a:b:c"));
    }

    // --- of() null rejection ---

    @Test
    @DisplayName("of() with a null segment throws")
    void of_nullSegment_throws() {
        assertThrows(RuntimeException.class, () -> ActionRef.of(null, "b", "c"));
    }

    // --- equality ---

    @Test
    @DisplayName("two ActionRefs with the same segments are equal with equal hashCode")
    void equality_sameSegments_equal() {
        ActionRef a = ActionRef.of("a", "b", "c");
        ActionRef b = ActionRef.of("a", "b", "c");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ActionRef.of("a", "b", "d"));
    }
}
