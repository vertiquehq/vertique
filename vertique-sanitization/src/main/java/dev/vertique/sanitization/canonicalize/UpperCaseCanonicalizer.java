// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import java.util.Locale;

/**
 * Canonicalizer that converts a string to upper case using the root locale.
 *
 * <p>Uses {@link String#toUpperCase(Locale)} with {@link Locale#ROOT} to produce a locale-neutral
 * upper-case form. This avoids locale-specific case folding surprises (e.g., the Turkish dotted-I
 * problem) and produces consistent results across all deployment environments.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class UpperCaseCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code UpperCaseCanonicalizer}.
     */
    public UpperCaseCanonicalizer() {}

    /**
     * Converts {@code value} to upper case using the root locale.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the upper-cased value, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
