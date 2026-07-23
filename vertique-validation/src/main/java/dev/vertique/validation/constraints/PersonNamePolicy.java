// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;

/**
 * Character policy that permits personal name characters across scripts.
 *
 * <p>Allowed characters:
 * <ul>
 *   <li>Unicode letters ({@link Character#isLetter(int)}) — covers Latin, CJK, Arabic,
 *       Cyrillic, and all other Unicode letter categories</li>
 *   <li>Space {@code ' '}</li>
 *   <li>Apostrophe {@code '}</li>
 *   <li>Hyphen {@code -}</li>
 *   <li>Period {@code .}</li>
 *   <li>Comma {@code ,}</li>
 * </ul>
 *
 * <p>Suitable for human name fields (first name, last name, full name) where Unicode
 * script diversity must be respected.
 */
public class PersonNamePolicy implements CharacterPolicy {

    /**
     * Validates that all characters in the value are permitted name characters.
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
                        "character not allowed in person name: U+"
                                + Integer.toHexString(cp).toUpperCase());
            }
        }
        return CharacterPolicyResult.passed();
    }

    /**
     * Returns {@code true} if the code point is an allowed person-name character.
     *
     * @param cp the Unicode code point to check
     * @return {@code true} if allowed
     */
    private static boolean isAllowed(int cp) {
        return Character.isLetter(cp) || cp == ' ' || cp == '\'' || cp == '-' || cp == '.' || cp == ',';
    }
}
