// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionPattern}.
 *
 * <p>Verifies that a pattern is either an exact canonical action or a trailing-suffix wildcard
 * ({@code "a.b.*"}, {@code "a.*"}). Covers {@link ActionPattern#matches(ActionRef)} for exact and
 * wildcard forms, and compact-constructor rejection of a {@code '*'} in any non-terminal position
 * and of otherwise-malformed patterns.
 */
class ActionPatternTest {

    // --- matches() exact ---

    @Test
    @DisplayName("exact canonical pattern matches the identical action")
    void matches_exactCanonical_true() {
        assertTrue(new ActionPattern("cms.content.read").matches(ActionRef.of("cms", "content", "read")));
    }

    @Test
    @DisplayName("exact canonical pattern does not match a different verb")
    void matches_exactCanonical_differentVerb_false() {
        assertFalse(new ActionPattern("cms.content.read").matches(ActionRef.of("cms", "content", "write")));
    }

    // --- matches() wildcard ---

    @Test
    @DisplayName("verb wildcard matches all verbs under the same subsystem.resource")
    void matches_verbWildcard_matchesAllVerbs() {
        assertTrue(new ActionPattern("cms.content.*").matches(ActionRef.of("cms", "content", "read")));
    }

    @Test
    @DisplayName("resource wildcard matches all actions under the same subsystem")
    void matches_resourceWildcard_matchesAll() {
        assertTrue(new ActionPattern("cms.*").matches(ActionRef.of("cms", "content", "read")));
    }

    @Test
    @DisplayName("resource wildcard does not match a different subsystem")
    void matches_resourceWildcard_diffSubsystem_false() {
        assertFalse(new ActionPattern("cms.*").matches(ActionRef.of("authz", "content", "read")));
    }

    // --- compact constructor rejection ---

    @Test
    @DisplayName("wildcard in the middle position is rejected")
    void compactCtor_wildcardInMiddlePosition_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("cms.*.read"));
    }

    @Test
    @DisplayName("wildcard in the first position is rejected")
    void compactCtor_wildcardInFirstPosition_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("*.content.read"));
    }

    @Test
    @DisplayName("blank pattern is rejected")
    void compactCtor_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("   "));
    }

    @Test
    @DisplayName("bare wildcard \"*\" is rejected (would be a blanket allow)")
    void compactCtor_bareWildcard_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("*"));
    }

    @Test
    @DisplayName("a bare \"*\" does not slip through as a match-all pattern (constructor rejects it)")
    void bareWildcard_neverMatchesEverything() {
        // The blanket-allow hole is closed at construction time: "*" can never be built, so it can
        // never be used to match an arbitrary action.
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("*"));
    }

    @Test
    @DisplayName("resource-level wildcard \"a.*\" is still accepted and matches")
    void compactCtor_resourceWildcard_stillValid() {
        ActionPattern pattern = new ActionPattern("a.*");
        assertTrue(pattern.isWildcard());
        assertTrue(pattern.matches(ActionRef.of("a", "b", "c")));
    }

    @Test
    @DisplayName("verb-level wildcard \"a.b.*\" is still accepted and matches")
    void compactCtor_verbWildcard_stillValid() {
        ActionPattern pattern = new ActionPattern("a.b.*");
        assertTrue(pattern.isWildcard());
        assertTrue(pattern.matches(ActionRef.of("a", "b", "c")));
    }

    @Test
    @DisplayName("isWildcard() is false for an exact canonical pattern")
    void isWildcard_exactPattern_false() {
        assertFalse(new ActionPattern("a.b.c").isWildcard());
    }

    // --- compact constructor rejection: never-matching segment counts (C1 residual) ---

    @Test
    @DisplayName("a two-segment exact pattern is rejected — it could never match a 3-segment ActionRef")
    void rejectsTwoSegmentExactPattern() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("payments.refund"));
    }

    @Test
    @DisplayName("a four-segment exact pattern is rejected — it could never match a 3-segment ActionRef")
    void rejectsFourSegmentExactPattern() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("payments.refund.approve.extra"));
    }

    @Test
    @DisplayName("a wildcard pattern with three leading segments is rejected — \"a.b.c.*\" could never match "
            + "a 3-segment ActionRef")
    void rejectsWildcardWithThreeLeadingSegments() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("a.b.c.*"));
    }

    @Test
    @DisplayName("a single-segment exact pattern is rejected — it could never match a 3-segment ActionRef")
    void rejectsSingleSegmentExactPattern() {
        assertThrows(IllegalArgumentException.class, () -> new ActionPattern("cms"));
    }
}
