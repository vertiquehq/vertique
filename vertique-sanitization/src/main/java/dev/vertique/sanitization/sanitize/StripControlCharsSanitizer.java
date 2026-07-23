// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.sanitize;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;

/**
 * Sanitizer that removes C0 and C1 control characters from a string, preserving tab, line feed,
 * and carriage return.
 *
 * <p>The following control characters are retained as they are widely used in legitimate text:
 * <ul>
 *   <li>{@code U+0009} HT (horizontal tab)</li>
 *   <li>{@code U+000A} LF (line feed / newline)</li>
 *   <li>{@code U+000D} CR (carriage return)</li>
 * </ul>
 *
 * <p>All other characters with {@link Character#isISOControl(int)} == {@code true} are removed.
 * This covers the C0 control range ({@code U+0000}–{@code U+001F}), DEL ({@code U+007F}),
 * and the C1 control range ({@code U+0080}–{@code U+009F}).
 *
 * <p>The implementation iterates over Unicode code points to correctly handle supplementary
 * characters (code points above {@code U+FFFF}).
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class StripControlCharsSanitizer implements Sanitizer {

    /**
     * Constructs a new {@code StripControlCharsSanitizer}.
     */
    public StripControlCharsSanitizer() {}

    /**
     * Removes control characters from {@code value}, preserving tab, line feed, and carriage return.
     *
     * @param value   the raw string value to sanitize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the value with control characters removed, or {@code null} if the input was {@code null}
     */
    @Override
    public String sanitize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return value.codePoints()
                .filter(cp -> !Character.isISOControl(cp) || cp == '\t' || cp == '\n' || cp == '\r')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
