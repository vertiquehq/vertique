// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

/**
 * Utility methods for HTTP header value encoding.
 */
final class HeaderUtils {

    private HeaderUtils() {}

    /**
     * Escapes a value for inclusion in a quoted-string within a header value. Backslashes and
     * double-quotes are backslash-escaped; CR, LF and the other control characters other than a
     * horizontal tab are each replaced by one {@code _}, so a value can never carry a line break
     * into a header, the length and positions of the other characters are unchanged, and two
     * values that differ by a control character stay different.
     *
     * @param value the raw header value to escape
     * @return the escaped value safe for use in a quoted-string
     */
    static String escapeQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                sb.append('_');
                continue;
            }
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
