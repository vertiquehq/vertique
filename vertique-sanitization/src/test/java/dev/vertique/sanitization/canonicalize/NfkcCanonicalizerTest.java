// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import java.text.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link NfkcCanonicalizer} applies Unicode NFKC normalization correctly,
 * including compatibility decomposition, and satisfies idempotency and null-safety contracts.
 */
class NfkcCanonicalizerTest {

    private final NfkcCanonicalizer canonicalizer = new NfkcCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "name", "name", Object.class);

    @Test
    @DisplayName("normalizes NFD-decomposed accented character to NFC precomposed form")
    void normalizesNfdToNfc() {
        // NFD: e + combining acute accent (U+0301) → NFC: é (U+00E9)
        var nfd = Normalizer.normalize("e\u0301", Normalizer.Form.NFD);
        assertEquals("\u00E9", canonicalizer.canonicalize(nfd, ctx));
    }

    @Test
    @DisplayName("maps compatibility characters to canonical equivalents (fi ligature)")
    void mapsCompatibilityCharacters() {
        // U+FB01 LATIN SMALL LIGATURE FI → "fi"
        assertEquals("fi", canonicalizer.canonicalize("\uFB01", ctx));
    }

    @Test
    @DisplayName("maps full-width digits to ASCII digits")
    void mapsFullWidthDigits() {
        // U+FF10-U+FF19 are full-width digit forms
        assertEquals(
                "0123456789",
                canonicalizer.canonicalize("\uFF10\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19", ctx));
    }

    @Test
    @DisplayName("returns already-NFKC-normalized value unchanged (idempotent)")
    void idempotentOnNfkcInput() {
        var nfkc = "hello café";
        assertEquals(nfkc, canonicalizer.canonicalize(nfkc, ctx));
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
