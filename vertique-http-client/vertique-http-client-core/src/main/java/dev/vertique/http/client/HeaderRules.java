// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.MultiMap;
import java.util.Map;

/** The request header rules of the governed client, with constant failure messages. */
final class HeaderRules {

    /** Failure message for a header name or value with a character that is not allowed. */
    static final String INVALID_CHARACTER = "A request header contains a character that is not allowed";

    /** Failure message for a header name under the framework-reserved prefix. */
    static final String RESERVED_NAME = "Request header names under the reserved prefix are not allowed";

    private static final String RESERVED_PREFIX = "vertique-";

    private HeaderRules() {}

    /**
     * Checks one header.
     *
     * @param name the header name
     * @param value the header value
     * @return the constant failure message, or {@code null} when the header is acceptable
     */
    static String violation(CharSequence name, CharSequence value) {
        if (hasForbiddenCharacter(name) || hasForbiddenCharacter(value)) {
            return INVALID_CHARACTER;
        }
        if (name.length() >= RESERVED_PREFIX.length()
                && name.subSequence(0, RESERVED_PREFIX.length()).toString().equalsIgnoreCase(RESERVED_PREFIX)) {
            return RESERVED_NAME;
        }
        return null;
    }

    /**
     * Checks every header of a request.
     *
     * @param headers the headers the caller set
     * @return the constant failure message of the first violation, or {@code null} when all are acceptable
     */
    static String violation(MultiMap headers) {
        for (Map.Entry<String, String> header : headers) {
            String violation = violation(header.getKey(), header.getValue() == null ? "" : header.getValue());
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    /**
     * Checks the headers of a request for CR, LF and NUL only, as for headers an extension prepared: the
     * reserved-prefix rule covers the caller's headers, not the framework's or an extension's.
     *
     * @param headers the final headers
     * @return the constant failure message of the first violation, or {@code null} when all are acceptable
     */
    static String forbiddenCharacters(MultiMap headers) {
        for (Map.Entry<String, String> header : headers) {
            if (hasForbiddenCharacter(header.getKey())
                    || header.getValue() != null && hasForbiddenCharacter(header.getValue())) {
                return INVALID_CHARACTER;
            }
        }
        return null;
    }

    private static boolean hasForbiddenCharacter(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }
}
