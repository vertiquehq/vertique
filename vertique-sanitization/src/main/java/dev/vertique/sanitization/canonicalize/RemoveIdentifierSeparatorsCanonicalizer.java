// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.canonicalize;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;

/**
 * Canonicalizer that removes space ({@code U+0020}) and hyphen-minus ({@code U+002D}) characters
 * from a string.
 *
 * <p>This is useful for normalizing user-entered identifiers such as phone numbers, credit card
 * numbers, UUIDs, or national identity numbers where separators may be included for readability
 * but must be stripped before comparison or storage. For example:
 * <ul>
 *   <li>{@code "1234 5678"} → {@code "12345678"}</li>
 *   <li>{@code "550e8400-e29b-41d4-a716-446655440000"} → {@code "550e8400e29b41d4a716446655440000"}</li>
 *   <li>{@code "GB 12 34 56 A"} → {@code "GB123456A"}</li>
 * </ul>
 *
 * <p>The implementation iterates over Unicode code points to correctly handle supplementary
 * characters (code points above {@code U+FFFF}).
 *
 * <p>This implementation is stateless and thread-safe.
 */
public final class RemoveIdentifierSeparatorsCanonicalizer implements Canonicalizer {

    /**
     * Constructs a new {@code RemoveIdentifierSeparatorsCanonicalizer}.
     */
    public RemoveIdentifierSeparatorsCanonicalizer() {}

    /**
     * Removes all space and hyphen-minus characters from {@code value}.
     *
     * @param value   the raw string value to canonicalize; returns {@code null} if {@code null}
     * @param context contextual metadata about the value's origin (unused)
     * @return the value with spaces and hyphens removed, or {@code null} if the input was {@code null}
     */
    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) {
            return null;
        }
        return value.codePoints()
                .filter(cp -> cp != ' ' && cp != '-')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
