// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the loggability contract of {@link Diagnostics#truncate(String, int)}: a bounded fragment is
 * safe to write verbatim into a log line, and the bound it advertises is genuinely hard.
 *
 * <p>Three properties are proven. The result never exceeds the requested bound — including for the
 * degenerate {@code null} input and for a bound too small to hold the elision marker. Every BMP
 * Unicode {@code Cc} code point (which includes the C1 block and {@code NEL}), {@code Cf} format
 * character (which includes the Trojan-Source bidirectional overrides and isolates), {@code Zl},
 * and {@code Zp} is replaced one-for-one, so neither a forged log line nor a misleadingly reordered
 * identity can be smuggled through a type, member, or profile name; a {@code \p{Cntrl}}-style
 * ASCII-only filter would miss everything from {@code DEL} onwards. A supplementary-plane {@code
 * Cf} code point is a stated, deliberate exception — see {@link Diagnostics#truncate(String, int)}
 * — and is not covered here. And no result ever carries an unpaired surrogate, whether the input
 * already contained one or the cut point would have split a pair.
 */
class DiagnosticsTest {

    /** Bounds exercised for the hard-bound property, spanning both sides of the marker length. */
    private static final List<Integer> BOUNDS = List.of(0, 1, 2, 3, 4, 5, 8, 16, 512);

    /** A supplementary-plane code point, so its UTF-16 form is a surrogate pair. */
    private static final String ASTRAL = new String(Character.toChars(0x1F600));

    /** The replacement written in place of a code unit that must not reach a log line. */
    private static final char REPLACEMENT_CHAR = '?';

    @Test
    @DisplayName("truncate never returns more code units than the requested bound")
    void truncateNeverExceedsItsBound() {
        // Given: values on both sides of every bound, including the degenerate null input whose
        // "null" placeholder is itself four code units long.
        List<String> values = Arrays.asList(null, "", "a", "abcdef", "abcdefghijklmnopqrstuvwxyz");

        for (String value : values) {
            for (int max : BOUNDS) {
                // When: the value is bounded.
                String bounded = Diagnostics.truncate(value, max);

                // Then: the result honours the bound exactly, with no exception for null or for a
                // bound smaller than the elision marker.
                assertTrue(
                        bounded.length() <= max,
                        "truncate(" + (value == null ? "null" : "\"" + value + "\"") + ", " + max
                                + ") must not exceed its bound; was \"" + bounded + "\" (" + bounded.length()
                                + " code units)");
            }
        }

        // Then: a value already within the bound is returned unchanged.
        assertEquals("abcdef", Diagnostics.truncate("abcdef", 6), "a value within the bound must survive verbatim");
        assertEquals("null", Diagnostics.truncate(null, 4), "the null placeholder must survive a bound that fits it");
    }

    @Test
    @DisplayName("truncate replaces every Cc control, C1 control, NEL, Zl, and Zp code point")
    void truncateReplacesControlCharactersAndLineSeparators() {
        // Given: one sample from each category a log line can be forged with — two ASCII controls,
        // LF, CR, DEL, a C1 control, NEL, Zl, and Zp.
        String hostile = "a" + (char) 0x01 + "b" + (char) 0x0A + "c" + (char) 0x0D + "d" + (char) 0x7F + "e"
                + (char) 0x90 + "f" + (char) 0x85 + "g" + (char) 0x2028 + "h" + (char) 0x2029 + "i" + (char) 0x1F + "j";

        // When: the value is bounded generously enough that nothing is elided.
        String bounded = Diagnostics.truncate(hostile, Diagnostics.MAX_MESSAGE_LENGTH);

        // Then: every offending code point is replaced one-for-one, so surrounding identity survives.
        assertEquals("a?b?c?d?e?f?g?h?i?j", bounded, "each control or separator must be replaced 1:1 with '?'");
        assertEquals(hostile.length(), bounded.length(), "the replacement must be one-for-one, never widening");

        assertNoSanitizedCategorySurvives(bounded);
    }

    @Test
    @DisplayName("truncate replaces every Cf format character, including the Trojan-Source bidi controls")
    void truncateReplacesFormatCharacters() {
        // Given: one sample from each Cf family a diagnostic identity can be misread through — the
        // bidirectional overrides and embeddings (U+202A-U+202E), the directional isolates
        // (U+2066-U+2069), the implicit marks (U+200E/U+200F), SOFT HYPHEN, and a BOM. A Java field,
        // method, or class name may legally contain every one of them: Character.isJavaIdentifierPart
        // accepts an ignorable code point, so `amount<RLO>rebmun` compiles and reaches a diagnostic.
        String hostile = "a" + (char) 0x202E + "b" + (char) 0x202A + "c" + (char) 0x202B + "d" + (char) 0x202C + "e"
                + (char) 0x202D + "f" + (char) 0x2066 + "g" + (char) 0x2067 + "h" + (char) 0x2068 + "i" + (char) 0x2069
                + "j" + (char) 0x200E + "k" + (char) 0x200F + "l" + (char) 0x00AD + "m" + (char) 0xFEFF + "n";

        // When: the value is bounded generously enough that nothing is elided.
        String bounded = Diagnostics.truncate(hostile, Diagnostics.MAX_MESSAGE_LENGTH);

        // Then: every format character is replaced one-for-one, so a bidi override cannot make one
        // diagnostic read as naming a different type or profile than the one that actually failed.
        assertEquals("a?b?c?d?e?f?g?h?i?j?k?l?m?n", bounded, "each format character must be replaced 1:1 with '?'");
        assertEquals(hostile.length(), bounded.length(), "the replacement must be one-for-one, never widening");

        assertNoSanitizedCategorySurvives(bounded);
    }

    @Test
    @DisplayName("truncate never emits an unpaired surrogate")
    void truncateNeverEmitsAnUnpairedSurrogate() {
        // Given: a value whose only surrogate pair straddles the cut point the marker reserves —
        // bound 10 reserves three units for the marker, leaving a cut at index 7, which is the low
        // half of the pair occupying indices 6 and 7.
        String straddling = "aaaaaa" + ASTRAL + "bbbb";
        assertTrue(straddling.length() > 10, "the fixture must exceed the bound so a cut actually happens");

        // When: the value is bounded.
        String cut = Diagnostics.truncate(straddling, 10);

        // Then: the cut backs off past the pair rather than splitting it, and stays within bound.
        assertTrue(cut.length() <= 10, "the backed-off cut must still honour the bound; was \"" + cut + "\"");
        assertNoUnpairedSurrogate(cut, "a cut must never split a surrogate pair");

        // Given: values already carrying an unpaired surrogate — a lone high, a lone low, and a
        // trailing lone high with no successor at all.
        List<String> unpairedValues =
                List.of("a" + (char) 0xD800 + "b", "a" + (char) 0xDC00 + "b", "ab" + (char) 0xD800);
        for (String unpaired : unpairedValues) {
            // When: the value is bounded with room to spare.
            String bounded = Diagnostics.truncate(unpaired, Diagnostics.MAX_MESSAGE_LENGTH);

            // Then: the lone surrogate is replaced, so the result is well-formed UTF-16.
            assertNoUnpairedSurrogate(bounded, "an unpaired surrogate already in the input must be replaced");
            assertEquals(unpaired.length(), bounded.length(), "the replacement must be one-for-one, never widening");
        }

        // Then: a well-formed pair is never disturbed.
        String paired = "a" + ASTRAL + "b";
        assertEquals(
                paired,
                Diagnostics.truncate(paired, Diagnostics.MAX_MESSAGE_LENGTH),
                "a well-formed surrogate pair must survive verbatim");

        // Given: the two adversarial sequences where an off-by-one in the pair skip would hide — a
        // lone high immediately followed by a well-formed pair, and a lone low followed by one.
        assertEquals(
                "a" + REPLACEMENT_CHAR + ASTRAL + "b",
                Diagnostics.truncate("a" + (char) 0xD800 + ASTRAL + "b", Diagnostics.MAX_MESSAGE_LENGTH),
                "in high-high-low the first high is unpaired and the following pair must survive intact");
        assertEquals(
                "a" + REPLACEMENT_CHAR + ASTRAL + "b",
                Diagnostics.truncate("a" + (char) 0xDC00 + ASTRAL + "b", Diagnostics.MAX_MESSAGE_LENGTH),
                "in low-high-low the lone low is replaced and the following pair must survive intact");
    }

    // --- Helpers ---

    /**
     * Asserts that no code unit of a bounded value belongs to a category sanitization must remove.
     *
     * @param bounded the bounded value to inspect
     */
    private static void assertNoSanitizedCategorySurvives(String bounded) {
        for (int index = 0; index < bounded.length(); index++) {
            int category = Character.getType(bounded.charAt(index));
            assertTrue(
                    category != Character.CONTROL
                            && category != Character.FORMAT
                            && category != Character.LINE_SEPARATOR
                            && category != Character.PARAGRAPH_SEPARATOR,
                    "no Cc, Cf, Zl, or Zp code point may survive; index " + index + " of \"" + bounded + "\"");
        }
    }

    /**
     * Asserts that every surrogate code unit in a value belongs to a well-formed pair.
     *
     * @param value  the bounded value to inspect
     * @param reason the assertion message prefix explaining what the value proves
     */
    private static void assertNoUnpairedSurrogate(String value, String reason) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                assertTrue(
                        index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)),
                        reason + "; a high surrogate at index " + index + " has no low half");
                index++;
            } else {
                assertTrue(
                        !Character.isLowSurrogate(unit),
                        reason + "; an unpaired low surrogate survived at index " + index);
            }
        }
    }
}
