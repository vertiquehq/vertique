// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;

/**
 * Character policy that permits address line characters.
 *
 * <p>Allowed characters:
 * <ul>
 *   <li>Unicode letters ({@link Character#isLetter(int)})</li>
 *   <li>Unicode digits ({@link Character#isDigit(int)})</li>
 *   <li>Space {@code ' '}</li>
 *   <li>Period {@code .}</li>
 *   <li>Comma {@code ,}</li>
 *   <li>Hyphen {@code -}</li>
 *   <li>Apostrophe {@code '}</li>
 *   <li>Slash {@code /}</li>
 *   <li>Hash {@code #}</li>
 *   <li>Open parenthesis {@code (}</li>
 *   <li>Close parenthesis {@code )}</li>
 *   <li>Ampersand {@code &}</li>
 * </ul>
 *
 * <p>Suitable for postal address line 1 and line 2 fields, including building numbers,
 * street names, suite/apartment references, and company names.
 */
public class AddressLinePolicy implements CharacterPolicy {

    /**
     * Validates that all characters in the value are permitted address-line characters.
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
                        "character not allowed in address line: U+"
                                + Integer.toHexString(cp).toUpperCase());
            }
        }
        return CharacterPolicyResult.passed();
    }

    /**
     * Returns {@code true} if the code point is an allowed address-line character.
     *
     * @param cp the Unicode code point to check
     * @return {@code true} if allowed
     */
    private static boolean isAllowed(int cp) {
        return Character.isLetter(cp)
                || Character.isDigit(cp)
                || cp == ' '
                || cp == '.'
                || cp == ','
                || cp == '-'
                || cp == '\''
                || cp == '/'
                || cp == '#'
                || cp == '('
                || cp == ')'
                || cp == '&';
    }
}
