// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import java.text.Normalizer;

/**
 * Canonicalizer that normalizes a string to Unicode NFKC form.
 *
 * <p>NFKC (Normalization Form Compatibility Composition) decomposes characters using compatibility
 * decomposition and then recomposes them using canonical composition. This maps compatibility
 * characters (e.g., ligatures, full-width digits, superscripts) to their canonical equivalents
 * and then applies NFC composition.
 *
 * <p>Use NFKC when values should be compared as equivalent regardless of how they were encoded,
 * for example when normalizing user-supplied identifiers or search terms.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class NfkcCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code NfkcCanonicalizer}.
     */
    public NfkcCanonicalizer() {}

    /**
     * Normalizes {@code value} to Unicode NFKC form.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the NFKC-normalized value, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }
}
