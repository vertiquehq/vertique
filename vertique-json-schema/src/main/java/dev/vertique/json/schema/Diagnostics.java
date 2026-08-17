// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.lang.reflect.Type;

/**
 * Builds the bounded, value-free diagnostics every {@link JsonSchemaGenerationException} carries.
 *
 * <p>Three bounds are enforced: a whole message is at most {@value #MAX_MESSAGE_LENGTH} UTF-16 code
 * units, any single resolved-type identity inside it is at most {@value #MAX_TYPE_IDENTITY_LENGTH},
 * and any shorter identity fragment — a profile id, a member name, an unknown {@code Type}
 * implementation's class name — is at most {@value #MAX_SHORT_IDENTITY_LENGTH}. A deeply nested
 * generic type can otherwise produce an unbounded type name, and a failure message is frequently
 * logged verbatim.
 *
 * <p>Because a message reaches a log verbatim, every bounded fragment is also made loggable: any
 * code point that could terminate a log record, forge a second one, misrepresent the identity it
 * renders, or leave the text ill-formed is replaced before the fragment is bounded. See {@link
 * #truncate(String, int)}, which also states the one limitation of that guarantee.
 *
 * <p>Only <em>identity</em> ever reaches a message — a type name, a property name, a profile id.
 * Application values never do.
 */
final class Diagnostics {

    /** Maximum length, in UTF-16 code units, of a complete failure message. */
    static final int MAX_MESSAGE_LENGTH = 512;

    /** Maximum length, in UTF-16 code units, of one resolved-type identity inside a message. */
    static final int MAX_TYPE_IDENTITY_LENGTH = 256;

    /**
     * Maximum length, in UTF-16 code units, of a short identity fragment inside a message: a profile
     * id, a member name, or the class name of an unknown {@code Type} implementation. These name a
     * single declaration rather than a whole resolved type graph, so they are bounded more tightly
     * than {@link #MAX_TYPE_IDENTITY_LENGTH}.
     */
    static final int MAX_SHORT_IDENTITY_LENGTH = 128;

    /** Marker appended in place of the elided tail of a truncated fragment. */
    private static final String ELLIPSIS = "...";

    /** Replacement written in place of a code point that must not reach a log line. */
    private static final char REPLACEMENT = '?';

    private Diagnostics() {}

    /**
     * Renders a bounded identity for a resolved {@link Type}.
     *
     * <p>A known reflection form is rendered through {@link Type#getTypeName()}. An unknown custom
     * {@code Type} implementation is rendered by its <em>class</em> name instead, because its
     * {@code getTypeName()} is caller-supplied and could carry arbitrary text.
     *
     * @param type the type to identify, possibly {@code null}
     * @return the bounded identity
     */
    static String typeIdentity(Type type) {
        if (type == null) {
            return "null";
        }
        String name;
        if (TypeGrammar.isKnownForm(type)) {
            try {
                name = type.getTypeName();
            } catch (RuntimeException unavailable) {
                name = type.getClass().getName();
            }
        } else {
            name = type.getClass().getName();
        }
        return truncate(name, MAX_TYPE_IDENTITY_LENGTH);
    }

    /**
     * Makes a message fragment loggable and bounds it to a maximum length, marking the elision.
     *
     * <p>Sanitization happens <em>first</em>, so the bound applies to what is actually emitted.
     * Every code point that could corrupt a log record is replaced one-for-one by {@value
     * #REPLACEMENT}, which keeps the surrounding identity readable and the length arithmetic exact:
     *
     * <ul>
     *   <li>Unicode general category {@code Cc} — the C0 controls, {@code DEL}, and the whole C1
     *       block including {@code NEL} (U+0085). A regular-expression class such as {@code
     *       \p{Cntrl}} covers ASCII only and would let every C1 control through, so the category is
     *       read from {@link Character#getType(char)} instead.
     *   <li>Unicode general category {@code Cf} — the format characters, which includes the
     *       Trojan-Source family (CVE-2021-42574): the bidirectional overrides and embeddings
     *       (U+202A–U+202E), the directional isolates (U+2066–U+2069), the implicit marks
     *       (U+200E/U+200F), {@code SOFT HYPHEN} (U+00AD), and the byte-order mark (U+FEFF). These
     *       are reachable, not theoretical: {@link Character#isJavaIdentifierPart(char)} accepts
     *       every ignorable code point, so a field, method, or class name may legally contain one
     *       and reach a diagnostic through a member or type identity — and a caller-supplied
     *       {@code JsonProfileId} is arbitrary text with no constraint at all. A bidi override
     *       cannot forge a record ({@code Cc} covers that), but it can make one read as naming a
     *       different type or profile than the one that actually failed, defeating the identity
     *       guarantee the diagnostic exists to provide; U+FEFF can additionally desynchronize a
     *       log-shipping parser.
     *   <li>Unicode {@code Zl} (U+2028) and {@code Zp} (U+2029), which several log and JSON readers
     *       treat as line terminators.
     *   <li>Every unpaired surrogate, whether already present in the input or not, so the result is
     *       always well-formed UTF-16 and cannot become a replacement character or an encoder error
     *       downstream.
     * </ul>
     *
     * <p>One limitation is stated rather than claimed away: classification is per code unit, so a
     * <em>supplementary-plane</em> {@code Cf} code point — U+110BD {@code KAITHI NUMBER SIGN} and
     * U+1D173–U+1D17A, the musical formatting controls — survives inside its well-formed surrogate
     * pair. None of them reorders or terminates rendered log text, and covering them would require
     * a code-point walk that no longer preserves the one-for-one length arithmetic below. Every
     * code point that can reorder or forge log text is BMP and is replaced.
     *
     * <p>The bound is hard: the returned value never exceeds {@code max} code units under any input.
     * That includes the two cases the previous implementation overran — a {@code null} value, whose
     * {@code "null"} placeholder is four code units, and a {@code max} smaller than the elision
     * marker. Below the marker's length there is no room to signal an elision at all, so the
     * fragment is simply cut; the marker stays inside the bound rather than extending past it,
     * because callers such as {@link #MAX_MESSAGE_LENGTH} treat their bound as a hard invariant.
     *
     * <p>A cut never splits a surrogate pair: sanitization guarantees every remaining surrogate is
     * paired, so a cut point landing on a high surrogate is moved back one code unit.
     *
     * @param value the fragment, possibly {@code null}
     * @param max   the maximum retained length in UTF-16 code units
     * @return the sanitized, bounded fragment; never {@code null}, never longer than {@code max}
     */
    static String truncate(String value, int max) {
        if (max <= 0) {
            return "";
        }
        String sanitized = sanitize(value == null ? "null" : value);
        if (sanitized.length() <= max) {
            return sanitized;
        }
        if (max < ELLIPSIS.length()) {
            return sanitized.substring(0, cutPoint(sanitized, max));
        }
        return sanitized.substring(0, cutPoint(sanitized, max - ELLIPSIS.length())) + ELLIPSIS;
    }

    /**
     * Replaces every code unit that must not reach a log line, one-for-one, preserving length.
     *
     * @param value the fragment to sanitize; never {@code null}
     * @return the sanitized fragment, which is {@code value} itself when nothing needed replacing
     */
    private static String sanitize(String value) {
        StringBuilder sanitized = null;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            boolean paired = Character.isHighSurrogate(unit)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1));
            if (paired) {
                // Skip the low half: a well-formed pair is never inspected further. Cc, Zl, and Zp
                // are BMP-only, so no pair can carry one; the supplementary Cf code points that a
                // pair can carry are the stated limitation in truncate's javadoc — they neither
                // reorder nor terminate rendered log text.
                index++;
                continue;
            }
            if (!mustBeReplaced(unit)) {
                continue;
            }
            if (sanitized == null) {
                sanitized = new StringBuilder(value);
            }
            sanitized.setCharAt(index, REPLACEMENT);
        }
        return sanitized == null ? value : sanitized.toString();
    }

    /**
     * Reports whether a single code unit must be replaced before the fragment can be logged.
     *
     * <p>Only ever consulted for a code unit that is not part of a well-formed surrogate pair, so
     * any surrogate reaching it is unpaired by construction.
     *
     * @param unit the code unit to classify
     * @return {@code true} for a {@code Cc}, {@code Cf}, {@code Zl}, or {@code Zp} code point, or an
     *     unpaired surrogate
     */
    private static boolean mustBeReplaced(char unit) {
        if (Character.isSurrogate(unit)) {
            return true;
        }
        int category = Character.getType(unit);
        return category == Character.CONTROL
                || category == Character.FORMAT
                || category == Character.LINE_SEPARATOR
                || category == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * Resolves the index a sanitized fragment may be cut at without splitting a surrogate pair.
     *
     * @param sanitized the sanitized fragment, in which every surrogate is paired
     * @param limit     the desired cut index, already known to be within the fragment
     * @return {@code limit}, or {@code limit - 1} when cutting there would split a pair
     */
    private static int cutPoint(String sanitized, int limit) {
        if (limit > 0 && Character.isHighSurrogate(sanitized.charAt(limit - 1))) {
            return limit - 1;
        }
        return limit;
    }

    /**
     * Builds a bounded generation failure.
     *
     * <p>The bounding and sanitization apply to the message only. The cause is attached exactly as
     * received — neither sanitized nor bounded — because its raw text is what makes the failure
     * diagnosable; a consumer logging the whole exception renders that text too.
     *
     * @param message the value-free message, bounded to {@value #MAX_MESSAGE_LENGTH} code units
     * @param cause   the underlying cause, or {@code null} when none exists
     * @return the exception to throw
     */
    static JsonSchemaGenerationException failure(String message, Throwable cause) {
        return new JsonSchemaGenerationException(truncate(message, MAX_MESSAGE_LENGTH), cause);
    }
}
