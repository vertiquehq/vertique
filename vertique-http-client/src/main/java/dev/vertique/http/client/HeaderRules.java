// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.MultiMap;
import java.util.Map;

/** The request header rules of the governed client, with constant failure messages. */
final class HeaderRules {

    /** Failure message for a header name or value with a character that is not allowed. */
    static final String INVALID_CHARACTER = "A request header contains a character that is not allowed";

    private HeaderRules() {}

    /**
     * Checks one header.
     *
     * @param name the header name
     * @param value the header value
     * @return the constant failure message, or {@code null} when the header is acceptable
     */
    static String violation(CharSequence name, CharSequence value) {
        return hasForbiddenCharacter(name) || hasForbiddenCharacter(value) ? INVALID_CHARACTER : null;
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
