// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;

/**
 * Canonicalizer that collapses consecutive runs of whitespace into a single ASCII space character.
 *
 * <p>Uses the regex {@code \s+} which matches any Unicode whitespace character including space,
 * tab, line feed, carriage return, form feed, and vertical tab. All such runs are replaced with
 * a single space ({@code U+0020}). This means newlines and tabs are also collapsed into spaces.
 *
 * <p>This canonicalizer is most appropriate for single-line text fields where whitespace normalization
 * is desired and the preservation of newlines or tabs is not important.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class CollapseWhitespaceCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code CollapseWhitespaceCanonicalizer}.
     */
    public CollapseWhitespaceCanonicalizer() {}

    /**
     * Collapses all runs of whitespace characters in {@code value} to a single space.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the value with whitespace runs collapsed, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ");
    }
}
