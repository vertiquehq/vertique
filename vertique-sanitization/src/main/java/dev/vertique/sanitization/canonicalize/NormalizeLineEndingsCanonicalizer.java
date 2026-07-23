// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;

/**
 * Canonicalizer that normalizes all line endings to Unix-style LF ({@code \n}).
 *
 * <p>Replaces Windows-style {@code \r\n} sequences and standalone carriage return {@code \r}
 * characters with a single line feed {@code \n}. The replacement is performed in two steps:
 * <ol>
 *   <li>{@code \r\n} → {@code \n} (Windows CRLF)</li>
 *   <li>{@code \r} → {@code \n} (old Mac-style CR; any that survived step 1)</li>
 * </ol>
 *
 * <p>The order of replacement is significant: replacing {@code \r\n} first prevents the
 * {@code \r} pass from creating a double {@code \n} where CRLF pairs were present.
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class NormalizeLineEndingsCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code NormalizeLineEndingsCanonicalizer}.
     */
    public NormalizeLineEndingsCanonicalizer() {}

    /**
     * Replaces all {@code \r\n} and standalone {@code \r} occurrences in {@code value} with {@code \n}.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the value with all line endings normalized to {@code \n}, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        // Replace CRLF first to avoid producing double LF on the \r pass
        return value.replace("\r\n", "\n").replace("\r", "\n");
    }
}
