// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import java.util.Objects;

/**
 * INTERNAL framework seam: the workflow modules share this validator so every entry point applies one scope
 * key rule; it is not an application contract and sits outside the maturity promise.
 *
 * <p>A scope key must be nonblank, at most {@value #MAX_CODE_POINTS} code points long, and free of ISO control
 * characters, unpaired surrogates and format characters (zero-width characters, bidirectional marks and
 * overrides). It must not start or end with whitespace, and it must not be blank once its format characters are
 * removed. Messages name the field and never repeat the value.
 */
public final class ScopeKeyRules {

    /** The most code points a scope key may hold. */
    public static final int MAX_CODE_POINTS = 255;

    private ScopeKeyRules() {}

    /**
     * Checks a scope key.
     *
     * @param scopeKey the key to check; must not be {@code null}
     * @param name the field name used in the failure message
     * @throws NullPointerException if the key is {@code null}
     * @throws IllegalArgumentException if the key breaks a rule
     */
    public static void require(String scopeKey, String name) {
        Objects.requireNonNull(scopeKey, name);
        if (scopeKey.codePointCount(0, scopeKey.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and at most " + MAX_CODE_POINTS + " code points");
        }
        boolean visible = false;
        for (int i = 0; i < scopeKey.length(); ) {
            int cp = scopeKey.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                throw new IllegalArgumentException(
                        name + " must not contain control characters or unpaired surrogate characters");
            }
            if (Character.isISOControl(cp)) {
                throw new IllegalArgumentException(
                        name + " must not contain control characters or unpaired surrogate characters");
            }
            if (Character.getType(cp) == Character.FORMAT) {
                throw new IllegalArgumentException(name + " must not contain format characters");
            }
            if (!isWhitespace(cp)) {
                visible = true;
            }
        }
        if (!visible) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and at most " + MAX_CODE_POINTS + " code points");
        }
        if (isWhitespace(scopeKey.codePointAt(0)) || isWhitespace(scopeKey.codePointBefore(scopeKey.length()))) {
            throw new IllegalArgumentException(name + " must not start or end with whitespace");
        }
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
