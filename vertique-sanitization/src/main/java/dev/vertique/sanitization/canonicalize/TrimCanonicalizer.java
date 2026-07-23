// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;

/**
 * Canonicalizer that removes leading and trailing Unicode whitespace from a string value.
 *
 * <p>Uses {@link String#strip()} which removes code points for which
 * {@link Character#isWhitespace(int)} returns {@code true}. This is more comprehensive than
 * {@link String#trim()} which only handles ASCII space and C0 controls. Note that
 * {@code U+00A0} NO-BREAK SPACE is intentionally not stripped by {@code strip()} because
 * {@code Character.isWhitespace(0xA0)} returns {@code false}. Applying this canonicalizer to
 * an already-stripped value returns the same value unchanged, satisfying the idempotency
 * requirement.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class TrimCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code TrimCanonicalizer}.
     */
    public TrimCanonicalizer() {}

    /**
     * Strips leading and trailing Unicode whitespace from {@code value}.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the stripped value, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return value.strip();
    }
}
