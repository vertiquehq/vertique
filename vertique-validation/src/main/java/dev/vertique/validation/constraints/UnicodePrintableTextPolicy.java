// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;

/**
 * The broadest character policy — permits all non-control printable characters,
 * including emoji.
 *
 * <p>Allowed characters:
 * <ul>
 *   <li>All characters where {@code !}{@link Character#isISOControl(int)} is {@code true}</li>
 * </ul>
 *
 * <p>Excluded:
 * <ul>
 *   <li>ISO control characters (U+0000–U+001F, U+007F–U+009F)</li>
 * </ul>
 *
 * <p>Emoji and other supplementary plane characters are <em>permitted</em>. Use
 * {@link UnicodePrintableNoEmojiTextPolicy} if emoji should be rejected.
 *
 * @see UnicodePrintableNoEmojiTextPolicy
 * @see UnicodeCommonTextPolicy
 */
public class UnicodePrintableTextPolicy implements CharacterPolicy {

    /**
     * Validates that all characters in the value are printable (non-control) characters.
     *
     * @param value   the string to validate; {@code null} is treated as valid
     * @param context contextual metadata about the value's origin (unused by this policy)
     * @return {@link CharacterPolicyResult#passed()} if valid, or a failed result identifying
     *         the first control character
     */
    @Override
    public CharacterPolicyResult validate(String value, InputValueContext context) {
        if (value == null) {
            return CharacterPolicyResult.passed();
        }
        int[] codePoints = value.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            int cp = codePoints[i];
            if (Character.isISOControl(cp)) {
                return CharacterPolicyResult.failed(
                        i,
                        cp,
                        "control character not allowed: U+"
                                + Integer.toHexString(cp).toUpperCase());
            }
        }
        return CharacterPolicyResult.passed();
    }
}
