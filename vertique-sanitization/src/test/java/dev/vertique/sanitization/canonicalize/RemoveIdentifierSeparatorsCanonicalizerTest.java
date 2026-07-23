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
 * Verifies that {@link RemoveIdentifierSeparatorsCanonicalizer} removes spaces and hyphens
 * (common identifier separators) while preserving all other characters, and satisfies idempotency
 * and null-safety contracts.
 */
class RemoveIdentifierSeparatorsCanonicalizerTest {

    private final RemoveIdentifierSeparatorsCanonicalizer canonicalizer = new RemoveIdentifierSeparatorsCanonicalizer();
    private final InputValueContext ctx =
            new InputValueContext(InputLocation.BODY, "identifier", "identifier", Object.class);

    @Test
    @DisplayName("removes spaces from phone number")
    void removesSpaces() {
        assertEquals("12345678", canonicalizer.canonicalize("1234 5678", ctx));
    }

    @Test
    @DisplayName("removes hyphens from UUID")
    void removesHyphens() {
        assertEquals(
                "550e8400e29b41d4a716446655440000",
                canonicalizer.canonicalize("550e8400-e29b-41d4-a716-446655440000", ctx));
    }

    @Test
    @DisplayName("removes both spaces and hyphens from national ID")
    void removesSpacesAndHyphens() {
        assertEquals("GB123456A", canonicalizer.canonicalize("GB 12-34-56 A", ctx));
    }

    @Test
    @DisplayName("preserves letters and digits that are not separators")
    void preservesOtherCharacters() {
        assertEquals("ABC123", canonicalizer.canonicalize("ABC123", ctx));
    }

    @Test
    @DisplayName("input with no separators is unchanged (idempotent)")
    void idempotentOnInputWithNoSeparators() {
        assertEquals("12345678", canonicalizer.canonicalize("12345678", ctx));
    }

    @Test
    @DisplayName("handles supplementary code points (emoji) without corruption")
    void handlesSupplementaryCodePoints() {
        // U+1F600 GRINNING FACE — should pass through intact (it is not space or hyphen)
        assertEquals("\uD83D\uDE00test", canonicalizer.canonicalize("\uD83D\uDE00 test", ctx));
    }

    @Test
    @DisplayName("returns empty string for empty input")
    void emptyForEmptyInput() {
        assertEquals("", canonicalizer.canonicalize("", ctx));
    }

    @Test
    @DisplayName("returns empty string for input consisting only of separators")
    void emptyForOnlySeparators() {
        assertEquals("", canonicalizer.canonicalize("- - -", ctx));
    }

    @Test
    @DisplayName("returns null for null input")
    void nullForNullInput() {
        assertNull(canonicalizer.canonicalize(null, ctx));
    }
}
