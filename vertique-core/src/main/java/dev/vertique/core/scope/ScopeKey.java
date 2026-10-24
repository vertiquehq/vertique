// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.scope;

import java.util.Objects;

/**
 * Immutable partition name for shared data or execution scopes.
 *
 * <p>A scope key identifies an application-defined partition such as a tenant, project, workspace,
 * or organization. Construction validates the text and preserves it exactly — no trimming, case
 * folding, normalization, or parsing. Equality and hash code use standard record semantics on that
 * exact string.
 *
 * <p>A key carries partition identity only. It asserts no trust, authentication, authorization, or
 * OAuth permission scope. Values are not neutralized for logging or other sinks; consumers encode
 * them for the destination. Keys must not hold secrets.
 *
 * <p>Instances are immutable and safe to share across threads. Construction requires no runtime,
 * security, or DI initialization and installs no ambient binding.
 *
 * @param value the exact partition text; must not be {@code null}
 */
public record ScopeKey(String value) {

    /** Maximum accepted Unicode code points in a scope key. */
    private static final int MAX_CODE_POINTS = 255;

    /**
     * UTF-16 unit bound checked before scanning. A valid 255-code-point key uses at most this many
     * units, so the precheck rejects no valid key.
     */
    private static final int MAX_UTF16_UNITS = 510;

    /**
     * Ordinary default partition name equal to {@code new ScopeKey("default")}.
     *
     * <p>It names a partition; it supplies no fallback, privilege, all-scopes meaning, or automatic
     * context binding.
     */
    public static final ScopeKey DEFAULT = new ScopeKey("default");

    /**
     * Validates and stores the supplied partition text without altering it.
     *
     * @param value the partition text
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} violates the accepted character contract
     */
    public ScopeKey {
        Objects.requireNonNull(value, "value");
        validate(value);
    }

    private static void validate(String value) {
        if (value.length() > MAX_UTF16_UNITS) {
            throw new IllegalArgumentException(
                    "scope key must contain at most " + MAX_CODE_POINTS + " Unicode code points");
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > MAX_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "scope key must contain between 1 and " + MAX_CODE_POINTS + " Unicode code points");
        }
        boolean visible = false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                throw new IllegalArgumentException(
                        "scope key must not contain control characters or unpaired surrogate characters");
            }
            if (Character.isISOControl(cp)) {
                throw new IllegalArgumentException(
                        "scope key must not contain control characters or unpaired surrogate characters");
            }
            if (Character.getType(cp) == Character.FORMAT) {
                throw new IllegalArgumentException("scope key must not contain format characters");
            }
            if (!isWhitespace(cp)) {
                visible = true;
            }
        }
        if (!visible) {
            throw new IllegalArgumentException("scope key must contain a non-whitespace code point");
        }
        if (isWhitespace(value.codePointAt(0)) || isWhitespace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException("scope key must not start or end with whitespace");
        }
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
