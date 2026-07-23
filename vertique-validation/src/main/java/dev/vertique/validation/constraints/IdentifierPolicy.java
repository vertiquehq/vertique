// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;

/**
 * Character policy that permits safe identifier characters.
 *
 * <p>Allowed characters:
 * <ul>
 *   <li>Unicode letters ({@link Character#isLetter(int)})</li>
 *   <li>Unicode digits ({@link Character#isDigit(int)})</li>
 *   <li>Underscore {@code _}</li>
 *   <li>Hyphen {@code -}</li>
 *   <li>Period {@code .}</li>
 * </ul>
 *
 * <p>Suitable for field names, identifiers, and slugs that may contain Unicode letters
 * but must not include whitespace or special symbols.
 */
public class IdentifierPolicy implements CharacterPolicy {

    /**
     * Validates that all characters in the value are permitted identifier characters.
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
                        "character not allowed in identifier: U+"
                                + Integer.toHexString(cp).toUpperCase());
            }
        }
        return CharacterPolicyResult.passed();
    }

    /**
     * Returns {@code true} if the code point is an allowed identifier character.
     *
     * @param cp the Unicode code point to check
     * @return {@code true} if allowed
     */
    private static boolean isAllowed(int cp) {
        return Character.isLetter(cp) || Character.isDigit(cp) || cp == '_' || cp == '-' || cp == '.';
    }
}
