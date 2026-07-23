// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;

/**
 * Character policy that permits URL slug characters.
 *
 * <p>Allowed characters:
 * <ul>
 *   <li>Lowercase ASCII letters {@code a-z}</li>
 *   <li>ASCII digits {@code 0-9}</li>
 *   <li>Hyphen {@code -}</li>
 * </ul>
 *
 * <p>Suitable for URL path segments, permalink slugs, and machine-readable identifiers
 * that must be lowercase ASCII only.
 */
public class SlugPolicy implements CharacterPolicy {

    /**
     * Validates that all characters in the value are permitted slug characters.
     *
     * @param value   the string to validate; {@code null} is treated as valid
     * @param context contextual metadata about the value's origin (unused by this policy)
     * @return {@link CharacterPolicyResult#passed()} if valid, or a failed result identifying
     *         the first disallowed character
     */
    @Override
    public CharacterPolicyResult validate(String value, InputValueContext context) {
        if (value == null) {
            return CharacterPolicyResult.passed();
        }
        int[] codePoints = value.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            int cp = codePoints[i];
            if (!isAllowed(cp)) {
                return CharacterPolicyResult.failed(
                        i,
                        cp,
                        "character not allowed in slug: U+"
                                + Integer.toHexString(cp).toUpperCase());
            }
        }
        return CharacterPolicyResult.passed();
    }

    /**
     * Returns {@code true} if the code point is an allowed slug character.
     *
     * @param cp the Unicode code point to check
     * @return {@code true} if allowed (lowercase ASCII letter, digit, or hyphen)
     */
    private static boolean isAllowed(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '-';
    }
}
