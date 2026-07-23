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
 * Verifies that {@link TrimCanonicalizer} strips leading and trailing Unicode whitespace correctly
 * and satisfies the idempotency and null-safety contracts.
 */
class TrimCanonicalizerTest {

    private final TrimCanonicalizer canonicalizer = new TrimCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "name", "name", Object.class);

    @Test
    @DisplayName("strips leading and trailing ASCII spaces")
    void stripsAsciiSpaces() {
        assertEquals("hello", canonicalizer.canonicalize("  hello  ", ctx));
    }

    @Test
    @DisplayName("strips leading and trailing em space (U+2003) — a true Unicode whitespace")
    void stripsUnicodeWhitespace() {
        // U+2003 EM SPACE is classified as Unicode whitespace by Character.isWhitespace(),
        // so strip() removes it. Note: U+00A0 NO-BREAK SPACE is NOT stripped by strip().
        assertEquals("hello", canonicalizer.canonicalize("\u2003hello\u2003", ctx));
    }

    @Test
    @DisplayName("does NOT strip non-breaking space (U+00A0) — not considered Unicode whitespace by strip()")
    void doesNotStripNonBreakingSpace() {
        // U+00A0 NO-BREAK SPACE: Character.isWhitespace(0xA0) == false, so strip() leaves it
        assertEquals("\u00A0hello\u00A0", canonicalizer.canonicalize("\u00A0hello\u00A0", ctx));
    }

    @Test
    @DisplayName("strips mixed leading/trailing tabs and newlines")
    void stripsMixedWhitespace() {
        assertEquals("hello world", canonicalizer.canonicalize("\t hello world \n", ctx));
    }

    @Test
    @DisplayName("returns already-trimmed value unchanged (idempotent)")
    void idempotentOnAlreadyTrimmed() {
        assertEquals("hello", canonicalizer.canonicalize("hello", ctx));
    }

    @Test
    @DisplayName("returns empty string for input that is only whitespace")
    void emptyForAllWhitespace() {
        assertEquals("", canonicalizer.canonicalize("   ", ctx));
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
