// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import jakarta.annotation.Nullable;

/**
 * INTERNAL framework seam: the workflow modules share this text rule so an actor id is logged or
 * recorded the same way everywhere; it is not an application contract and sits outside the maturity
 * promise.
 *
 * <p>The rule removes control characters, the line and paragraph separators U+2028 and U+2029 and
 * Unicode format characters (bidirectional overrides and isolates, zero-width characters), then cuts
 * the text at {@value #MAX_LENGTH} code points.
 */
public final class ActorText {

    /** The most code points of an actor id, reason or party acted for that are kept. */
    public static final int MAX_LENGTH = 255;

    /** What text is replaced by when nothing of it is printable. */
    public static final String UNPRINTABLE = "[unprintable]";

    /** U+2028, the line separator. */
    private static final int LINE_SEPARATOR = 0x2028;

    /** U+2029, the paragraph separator. */
    private static final int PARAGRAPH_SEPARATOR = 0x2029;

    private ActorText() {}

    /**
     * Removes control characters, the line and paragraph separators U+2028 and U+2029, and Unicode
     * format characters, then cuts the text at the bound. Text that is left empty by the removal, such
     * as an id made only of such characters, becomes {@value #UNPRINTABLE}, so that an identity is
     * never recorded as a verified actor without an id.
     *
     * @param text the text to clean, or {@code null}
     * @return the cleaned text, or {@code null} when {@code text} is {@code null}
     */
    @Nullable
    public static String clean(@Nullable String text) {
        if (text == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder(Math.min(text.length(), MAX_LENGTH));
        int count = 0;
        for (int index = 0; index < text.length() && count < MAX_LENGTH; ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || codePoint == LINE_SEPARATOR
                    || codePoint == PARAGRAPH_SEPARATOR
                    || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            kept.appendCodePoint(codePoint);
            count++;
        }
        return kept.isEmpty() ? UNPRINTABLE : kept.toString();
    }
}
