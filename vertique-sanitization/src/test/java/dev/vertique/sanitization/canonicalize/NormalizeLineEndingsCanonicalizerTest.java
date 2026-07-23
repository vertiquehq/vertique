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
 * Verifies that {@link NormalizeLineEndingsCanonicalizer} correctly replaces all line ending
 * variants with LF, in the right order, and satisfies idempotency and null-safety contracts.
 */
class NormalizeLineEndingsCanonicalizerTest {

    private final NormalizeLineEndingsCanonicalizer canonicalizer = new NormalizeLineEndingsCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    @Test
    @DisplayName("replaces CRLF (Windows) with LF")
    void replacesCrLf() {
        assertEquals("line1\nline2", canonicalizer.canonicalize("line1\r\nline2", ctx));
    }

    @Test
    @DisplayName("replaces standalone CR (old Mac) with LF")
    void replacesCr() {
        assertEquals("line1\nline2", canonicalizer.canonicalize("line1\rline2", ctx));
    }

    @Test
    @DisplayName("does not double-replace CRLF into double LF")
    void doesNotDoubleReplaceCrLf() {
        // If \r\n were replaced in wrong order (\r first), we'd get \n\n
        assertEquals("a\nb", canonicalizer.canonicalize("a\r\nb", ctx));
    }

    @Test
    @DisplayName("handles mixed line endings in one string")
    void handlesMixedLineEndings() {
        assertEquals("a\nb\nc\nd", canonicalizer.canonicalize("a\r\nb\rc\nd", ctx));
    }

    @Test
    @DisplayName("returns already-LF value unchanged (idempotent)")
    void idempotentOnLfInput() {
        var input = "line1\nline2\nline3";
        assertEquals(input, canonicalizer.canonicalize(input, ctx));
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
