// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Decides the {@code uri} and {@code uri-reference} formats {@link PatternInputGuard} routes here: glue
 * over {@link URI} only, with no new parser and no regular expression.
 *
 * <p>{@code uri} accepts an ASCII-only value that {@link URI} parses and that {@link URI#isAbsolute()}
 * reports absolute. {@code uri-reference} accepts an ASCII-only value that {@link URI} parses, absolute
 * or relative.
 */
final class UriFormatChecks {

    private UriFormatChecks() {}

    /**
     * Decides the {@code uri} format: an ASCII-only value that {@link URI} parses as absolute.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code uri}
     */
    static boolean isUri(String value) {
        URI uri = parseAscii(value);
        return uri != null && uri.isAbsolute();
    }

    /**
     * Decides the {@code uri-reference} format: an ASCII-only value that {@link URI} parses, absolute or
     * relative.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code uri-reference}
     */
    static boolean isUriReference(String value) {
        return parseAscii(value) != null;
    }

    /**
     * Parses {@code value} as a {@link URI}, the ASCII check and the parse both {@code isUri} and
     * {@code isUriReference} require.
     *
     * @param value the instance
     * @return the parsed {@link URI}, or {@code null} when {@code value} is not ASCII-only or {@link URI}
     *     rejects it
     */
    private static URI parseAscii(String value) {
        if (!isAscii(value)) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    /**
     * Reports whether every character of {@code value} is ASCII, at most code point 127 — the rule
     * {@code uri} and {@code uri-reference} apply before parsing.
     *
     * @param value the instance
     * @return {@code true} when every character of {@code value} is ASCII
     */
    static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
