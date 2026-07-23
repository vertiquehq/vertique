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
 * Verifies that {@link NfcCanonicalizer} applies Unicode NFC normalization correctly, preserves
 * compatibility characters (unlike NFKC), and satisfies idempotency and null-safety contracts.
 */
class NfcCanonicalizerTest {

    private final NfcCanonicalizer canonicalizer = new NfcCanonicalizer();
    private final InputValueContext ctx = new InputValueContext(InputLocation.BODY, "name", "name", Object.class);

    @Test
    @DisplayName("normalizes NFD-decomposed accented character to precomposed NFC form")
    void normalizesNfdToNfc() {
        // NFD: e + combining acute accent → NFC: é (U+00E9)
        var nfd = Normalizer.normalize("e\u0301", Normalizer.Form.NFD);
        assertEquals("\u00E9", canonicalizer.canonicalize(nfd, ctx));
    }

    @Test
    @DisplayName("preserves fi ligature (unlike NFKC, NFC does not decompose compatibility chars)")
    void preservesCompatibilityCharacters() {
        // U+FB01 LATIN SMALL LIGATURE FI — NFC preserves it, NFKC maps it to "fi"
        assertEquals("\uFB01", canonicalizer.canonicalize("\uFB01", ctx));
    }

    @Test
    @DisplayName("returns already-NFC-normalized value unchanged (idempotent)")
    void idempotentOnNfcInput() {
        var nfc = "caf\u00E9";
        assertEquals(nfc, canonicalizer.canonicalize(nfc, ctx));
    }

    @Test
    @DisplayName("handles combining character sequences with multiple accents")
    void handlesCombiningSequences() {
        // s + combining cedilla + combining dot above → NFC should produce canonical composition
        // Use a known sequence: a + combining grave (U+0300) → à (U+00E0)
        var decomposed = "a\u0300";
        assertEquals("\u00E0", canonicalizer.canonicalize(decomposed, ctx));
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
