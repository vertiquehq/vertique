// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;
import java.util.Set;

/**
 * Character policy that permits common text characters suitable for structured user input.
 *
 * <p>Per FR-REST-178C, allowed characters are:
 * <ul>
 *   <li><b>Unicode category L</b> (letters): {@link Character#LOWERCASE_LETTER},
 *       {@link Character#UPPERCASE_LETTER}, {@link Character#TITLECASE_LETTER},
 *       {@link Character#MODIFIER_LETTER}, {@link Character#OTHER_LETTER}</li>
 *   <li><b>Unicode category N</b> (numbers): {@link Character#DECIMAL_DIGIT_NUMBER},
 *       {@link Character#LETTER_NUMBER}, {@link Character#OTHER_NUMBER}</li>
 *   <li><b>Unicode category Z</b> (separators): {@link Character#SPACE_SEPARATOR},
 *       {@link Character#LINE_SEPARATOR}, {@link Character#PARAGRAPH_SEPARATOR}</li>
 *   <li><b>Unicode category P</b> (punctuation): {@link Character#CONNECTOR_PUNCTUATION},
 *       {@link Character#DASH_PUNCTUATION}, {@link Character#START_PUNCTUATION},
 *       {@link Character#END_PUNCTUATION}, {@link Character#INITIAL_QUOTE_PUNCTUATION},
 *       {@link Character#FINAL_QUOTE_PUNCTUATION}, {@link Character#OTHER_PUNCTUATION};
 *       covers em-dashes, ellipses, guillemets, and other natural-language punctuation</li>
 *   <li><b>Explicit symbol allowlist</b>:
 *       {@code . , ; : ! ? ' " - ( ) [ ] { } / \ @ # $ % & * + = _ ~ ^ ` | < >}</li>
 * </ul>
 *
 * <p>Excluded:
 * <ul>
 *   <li>ISO control characters (U+0000–U+001F, U+007F–U+009F)</li>
 *   <li>Emoji (Unicode {@link Character#OTHER_SYMBOL} characters at U+2600 and above,
 *       plus supplementary plane emoji ranges)</li>
 * </ul>
 *
 * <p>This policy is stricter than {@link UnicodePrintableTextPolicy} and
 * {@link UnicodePrintableNoEmojiTextPolicy}, covering the common set of characters
 * needed for names, descriptions, and text fields without allowing arbitrary symbols.
 *
 * @see UnicodePrintableTextPolicy
 * @see UnicodePrintableNoEmojiTextPolicy
 */
public class UnicodeCommonTextPolicy implements CharacterPolicy {

    // --- Punctuation allowlist ---

    private static final Set<Integer> ALLOWED_PUNCTUATION = Set.of(
            (int) '.',
            (int) ',',
            (int) ';',
            (int) ':',
            (int) '!',
            (int) '?',
            (int) '\'',
            (int) '"',
            (int) '-',
            (int) '(',
            (int) ')',
            (int) '[',
            (int) ']',
            (int) '{',
            (int) '}',
            (int) '/',
            (int) '\\',
            (int) '@',
            (int) '#',
            (int) '$',
            (int) '%',
            (int) '&',
            (int) '*',
            (int) '+',
            (int) '=',
            (int) '_',
            (int) '~',
            (int) '^',
            (int) '`',
            (int) '|',
            (int) '<',
            (int) '>');

    /**
     * Validates that all characters in the value belong to the common text character set.
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
                        "character not allowed in common text: U+"
                                + Integer.toHexString(cp).toUpperCase());
            }
        }
        return CharacterPolicyResult.passed();
    }

    /**
     * Returns {@code true} if the code point is allowed in common text.
     *
     * <p>Accepts Unicode category L (letters), N (numbers), Z (separators), P (punctuation),
     * and the explicit punctuation allowlist. Rejects control characters and emoji.
     *
     * @param cp the Unicode code point to check
     * @return {@code true} if allowed
     */
    private static boolean isAllowed(int cp) {
        if (Character.isISOControl(cp)) {
            return false;
        }
        if (isEmoji(cp)) {
            return false;
        }
        int type = Character.getType(cp);
        return isLetterCategory(type)
                || isNumberCategory(type)
                || isSeparatorCategory(type)
                || isPunctuationCategory(type)
                || ALLOWED_PUNCTUATION.contains(cp);
    }

    /**
     * Returns {@code true} if the Unicode general category is a letter category (L*).
     *
     * @param type the value returned by {@link Character#getType(int)}
     * @return {@code true} for Ll, Lu, Lt, Lm, Lo
     */
    private static boolean isLetterCategory(int type) {
        return type == Character.LOWERCASE_LETTER
                || type == Character.UPPERCASE_LETTER
                || type == Character.TITLECASE_LETTER
                || type == Character.MODIFIER_LETTER
                || type == Character.OTHER_LETTER;
    }

    /**
     * Returns {@code true} if the Unicode general category is a number category (N*).
     *
     * @param type the value returned by {@link Character#getType(int)}
     * @return {@code true} for Nd, Nl, No
     */
    private static boolean isNumberCategory(int type) {
        return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /**
     * Returns {@code true} if the Unicode general category is a separator category (Z*).
     *
     * @param type the value returned by {@link Character#getType(int)}
     * @return {@code true} for Zs, Zl, Zp
     */
    private static boolean isSeparatorCategory(int type) {
        return type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * Returns {@code true} if the Unicode general category is a punctuation category (P*).
     *
     * <p>Covers: Pc (connector), Pd (dash), Ps (open), Pe (close), Pi (initial quote),
     * Pf (final quote), Po (other punctuation). This allows em-dashes, ellipses, guillemets,
     * and other Unicode punctuation used in natural language text.
     *
     * @param type the value returned by {@link Character#getType(int)}
     * @return {@code true} for Pc, Pd, Ps, Pe, Pi, Pf, Po
     */
    private static boolean isPunctuationCategory(int type) {
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    /**
     * Returns {@code true} if the code point is an emoji character.
     *
     * <p>Detects emoji via:
     * <ul>
     *   <li>{@link Character#OTHER_SYMBOL} category at U+2600 and above (arrows and beyond)</li>
     *   <li>Supplementary plane emoji ranges: U+1F000–U+1FFFF</li>
     * </ul>
     *
     * @param cp the Unicode code point to check
     * @return {@code true} if the code point is emoji
     */
    static boolean isEmoji(int cp) {
        if (Character.getType(cp) == Character.OTHER_SYMBOL && cp >= 0x2600) {
            return true;
        }
        // Supplementary plane emoji ranges
        return cp >= 0x1F000 && cp <= 0x1FFFF;
    }
}
