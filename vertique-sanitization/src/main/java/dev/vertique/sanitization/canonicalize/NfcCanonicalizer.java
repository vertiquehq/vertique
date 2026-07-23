// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import java.text.Normalizer;

/**
 * Canonicalizer that normalizes a string to Unicode NFC form.
 *
 * <p>NFC (Normalization Form Canonical Composition) decomposes characters using canonical
 * decomposition and then recomposes them using canonical composition. This ensures that accented
 * characters, for example, are stored in their precomposed form (e.g., {@code é} as a single
 * code point rather than {@code e} + combining accent).
 *
 * <p>NFC is the most common Unicode normalization form and is typically the right choice when you
 * want to preserve visual fidelity while ensuring consistent internal representation. Unlike
 * {@link NfkcCanonicalizer}, NFC does <em>not</em> map compatibility characters to their
 * canonical equivalents.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class NfcCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code NfcCanonicalizer}.
     */
    public NfcCanonicalizer() {}

    /**
     * Normalizes {@code value} to Unicode NFC form.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the NFC-normalized value, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
