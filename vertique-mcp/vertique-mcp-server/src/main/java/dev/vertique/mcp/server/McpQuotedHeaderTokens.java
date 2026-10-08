// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits HTTP header values on a delimiter while honoring RFC 9110 quoted strings, so a comma or
 * semicolon inside a quoted parameter value is data rather than a separator.
 */
final class McpQuotedHeaderTokens {
    private McpQuotedHeaderTokens() {}

    /**
     * Splits {@code value} on every {@code delimiter} that is outside a double-quoted string.
     *
     * <p>Inside a quoted string a backslash escapes the next character, so an escaped quote does not
     * close the string. A backslash outside a quoted string is literal. A quoted string left
     * unterminated runs to the end of the value, so the remainder stays in its token. Tokens are
     * returned untrimmed and include empty tokens, so the result always has at least one element and
     * adjacent delimiters yield an empty token between them.
     *
     * @param value the header value to split
     * @param delimiter the separator character, such as {@code ','} or {@code ';'}
     * @return the tokens in order, never empty
     */
    static List<String> split(String value, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted && character == '\\' && index + 1 < value.length()) {
                token.append(character).append(value.charAt(++index));
                continue;
            }
            if (character == '"') {
                quoted = !quoted;
            } else if (character == delimiter && !quoted) {
                tokens.add(token.toString());
                token.setLength(0);
                continue;
            }
            token.append(character);
        }
        tokens.add(token.toString());
        return tokens;
    }
}
