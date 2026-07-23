// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link CollapseWhitespaceCanonicalizer} collapses runs of whitespace to a single
 * space, including tabs and newlines, and satisfies idempotency and null-safety contracts.
 */
class CollapseWhitespaceCanonicalizerTest {

    private final CollapseWhitespaceCanonicalizer canonicalizer = new CollapseWhitespaceCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    @Test
    @DisplayName("collapses multiple spaces into one")
    void collapsesMultipleSpaces() {
        assertEquals("hello world", canonicalizer.canonicalize("hello   world", ctx));
    }

    @Test
    @DisplayName("collapses tabs into a single space")
    void collapsesTabs() {
        assertEquals("a b", canonicalizer.canonicalize("a\t\tb", ctx));
    }

    @Test
    @DisplayName("collapses newlines into a single space")
    void collapsesNewlines() {
        assertEquals("a b", canonicalizer.canonicalize("a\n\nb", ctx));
    }

    @Test
    @DisplayName("collapses mixed whitespace types into a single space")
    void collapsesMixedWhitespace() {
        assertEquals("a b", canonicalizer.canonicalize("a \t\n b", ctx));
    }

    @Test
    @DisplayName("preserves single spaces (idempotent)")
    void idempotentOnSingleSpaces() {
        assertEquals("hello world", canonicalizer.canonicalize("hello world", ctx));
    }

    @Test
    @DisplayName("returns empty string for input with only whitespace")
    void singleSpaceForAllWhitespace() {
        // \s+ matches the whole string and replaces with a single space
        assertEquals(" ", canonicalizer.canonicalize("   \t\n  ", ctx));
    }

    @Test
    @DisplayName("returns empty string for empty input")
    void emptyForEmptyInput() {
        assertEquals("", canonicalizer.canonicalize("", ctx));
    }

    @Test
    @DisplayName("returns null for null input")
    void nullForNullInput() {
        assertNull(canonicalizer.canonicalize(null, ctx));
    }
}
