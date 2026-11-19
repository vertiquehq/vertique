// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.MultiMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The request header rules of the governed client, with constant failure messages. */
final class HeaderRules {

    /** Failure message for a header name or value with a character that is not allowed. */
    static final String INVALID_CHARACTER = "A request header contains a character that is not allowed";

    /** Header names the transport owns; no default header or credential may set them. */
    private static final Set<String> TRANSPORT_OWNED =
            Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade", "te");

    private HeaderRules() {}

    /**
     * Whether {@code name} is one of the header names the transport owns ({@code Host},
     * {@code Content-Length}, {@code Transfer-Encoding}, {@code Connection}, {@code Upgrade}, {@code TE}).
     *
     * @param name the header name
     * @return true when the transport owns the name
     */
    static boolean isTransportOwned(String name) {
        return TRANSPORT_OWNED.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether {@code name} is an HTTP token: one or more of letters, digits and {@code !#$%&'*+-.^_`|~}.
     *
     * @param name the header name, may be null
     * @return true when the name is a token
     */
    static boolean isToken(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code text} holds a control character other than a horizontal tab.
     *
     * @param text the header value
     * @return true when a control character is present
     */
    static boolean hasControlCharacter(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code text} holds a character no header name or value may carry: CR, LF or NUL.
     *
     * @param text the header name or value
     * @return true when a forbidden character is present
     */
    static boolean hasForbiddenCharacter(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }

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
}
