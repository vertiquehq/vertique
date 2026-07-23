// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

/**
 * Utility methods for HTTP header value encoding.
 */
final class HeaderUtils {

    private HeaderUtils() {}

    /**
     * Escapes a value for inclusion in a quoted-string within a Content-Disposition header.
     * Backslashes and double-quotes are backslash-escaped; bare CR and LF characters are stripped.
     *
     * @param value the raw header value to escape
     * @return the escaped value safe for use in a quoted-string
     */
    static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "");
    }
}
