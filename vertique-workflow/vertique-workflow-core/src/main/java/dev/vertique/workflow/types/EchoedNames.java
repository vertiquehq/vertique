// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

/**
 * INTERNAL framework seam: the workflow modules share this helper to repeat an untrusted name in an
 * error message; it is not an application contract and sits outside the maturity promise.
 *
 * <p>Makes a caller- or document-supplied name safe to echo. At most {@value #MAX_CODE_POINTS} code
 * points are repeated, followed by a cut marker when the name was longer; control characters and
 * unpaired surrogates are replaced by a question mark, so a name can neither forge a log line nor
 * flood a message. Typed accessors on exceptions keep carrying the full identifiers.
 */
public final class EchoedNames {

    /** The most code points of a name that a message repeats. */
    public static final int MAX_CODE_POINTS = 64;

    private static final String CUT_MARKER = "...";
    private static final int REPLACEMENT = '?';

    private EchoedNames() {}

    /**
     * Returns the name in a form that is safe to repeat in a message.
     *
     * @param name the untrusted name; may be {@code null}
     * @return the name cut at {@value #MAX_CODE_POINTS} code points with a cut marker when longer, with
     *     every ISO control character and unpaired surrogate replaced by {@code ?}; the text {@code
     *     null} for a {@code null} name; never {@code null}
     */
    public static String bounded(String name) {
        if (name == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(name.length(), MAX_CODE_POINTS) + CUT_MARKER.length());
        int index = 0;
        int echoed = 0;
        while (index < name.length() && echoed < MAX_CODE_POINTS) {
            int codePoint = name.codePointAt(index);
            index += Character.charCount(codePoint);
            boolean unpairedSurrogate = codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
            out.appendCodePoint(Character.isISOControl(codePoint) || unpairedSurrogate ? REPLACEMENT : codePoint);
            echoed++;
        }
        if (index < name.length()) {
            out.append(CUT_MARKER);
        }
        return out.toString();
    }
}
