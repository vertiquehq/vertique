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
 * Verifies that {@link LowerCaseCanonicalizer} converts strings to lower case using the root locale,
 * satisfying idempotency and null-safety contracts.
 */
class LowerCaseCanonicalizerTest {

    private final LowerCaseCanonicalizer canonicalizer = new LowerCaseCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "code", "code", Object.class);

    @Test
    @DisplayName("converts uppercase ASCII to lowercase")
    void convertsUpperToLower() {
        assertEquals("hello world", canonicalizer.canonicalize("HELLO WORLD", ctx));
    }

    @Test
    @DisplayName("converts mixed case to lowercase")
    void convertsMixedToLower() {
        assertEquals("hello world", canonicalizer.canonicalize("Hello World", ctx));
    }

    @Test
    @DisplayName("does not apply Turkish dotless-I rule (root locale)")
    void noTurkishDotlessI() {
        // In Turkish locale, 'I' → 'ı' (dotless lowercase i). Root locale gives 'i'.
        assertEquals("i", canonicalizer.canonicalize("I", ctx));
    }

    @Test
    @DisplayName("already-lowercase value is unchanged (idempotent)")
    void idempotentOnLowerCase() {
        assertEquals("hello", canonicalizer.canonicalize("hello", ctx));
    }

    @Test
    @DisplayName("preserves non-letter characters")
    void preservesNonLetters() {
        assertEquals("abc-123", canonicalizer.canonicalize("ABC-123", ctx));
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
