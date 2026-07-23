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
 * Verifies that {@link UpperCaseCanonicalizer} converts strings to upper case using the root locale,
 * satisfying idempotency and null-safety contracts.
 */
class UpperCaseCanonicalizerTest {

    private final UpperCaseCanonicalizer canonicalizer = new UpperCaseCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "code", "code", Object.class);

    @Test
    @DisplayName("converts lowercase ASCII to uppercase")
    void convertsLowerToUpper() {
        assertEquals("HELLO WORLD", canonicalizer.canonicalize("hello world", ctx));
    }

    @Test
    @DisplayName("converts mixed case to uppercase")
    void convertsMixedToUpper() {
        assertEquals("HELLO WORLD", canonicalizer.canonicalize("Hello World", ctx));
    }

    @Test
    @DisplayName("does not apply Turkish dotted-I rule (root locale)")
    void noTurkishDottedI() {
        // In Turkish locale, 'i' → 'İ' (dotted capital I). Root locale gives 'I'.
        assertEquals("I", canonicalizer.canonicalize("i", ctx));
    }

    @Test
    @DisplayName("already-uppercase value is unchanged (idempotent)")
    void idempotentOnUpperCase() {
        assertEquals("HELLO", canonicalizer.canonicalize("HELLO", ctx));
    }

    @Test
    @DisplayName("preserves non-letter characters")
    void preservesNonLetters() {
        assertEquals("ABC-123", canonicalizer.canonicalize("abc-123", ctx));
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
