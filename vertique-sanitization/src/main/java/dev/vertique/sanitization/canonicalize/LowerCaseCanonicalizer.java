// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import java.util.Locale;

/**
 * Canonicalizer that converts a string to lower case using the root locale.
 *
 * <p>Uses {@link String#toLowerCase(Locale)} with {@link Locale#ROOT} to produce a locale-neutral
 * lower-case form. This avoids locale-specific case folding surprises (e.g., the Turkish dotted-I
 * problem) and produces consistent results across all deployment environments.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class LowerCaseCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code LowerCaseCanonicalizer}.
     */
    public LowerCaseCanonicalizer() {}

    /**
     * Converts {@code value} to lower case using the root locale.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the lower-cased value, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
