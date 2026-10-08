// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies {@link McpQuotedHeaderTokens#split(String, char)}: delimiters outside double-quoted
 * strings separate tokens, while delimiters inside a quoted string (including after an escaped
 * quote) stay part of the token.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpQuotedHeaderTokensTest {

    @Test
    @DisplayName("splits an unquoted value on every delimiter")
    void shouldSplitUnquotedValue() {
        assertThat(McpQuotedHeaderTokens.split("a, b;q=1,c", ',')).containsExactly("a", " b;q=1", "c");
        assertThat(McpQuotedHeaderTokens.split("x;y;z", ';')).containsExactly("x", "y", "z");
    }

    @Test
    @DisplayName("returns the whole value when there is no delimiter, and one empty token for an empty value")
    void shouldReturnSingleToken() {
        assertThat(McpQuotedHeaderTokens.split("application/json", ',')).containsExactly("application/json");
        assertThat(McpQuotedHeaderTokens.split("", ',')).containsExactly("");
    }

    @Test
    @DisplayName("keeps adjacent and trailing delimiters as empty tokens")
    void shouldKeepEmptyTokens() {
        assertThat(McpQuotedHeaderTokens.split("a,,b,", ',')).containsExactly("a", "", "b", "");
    }

    @Test
    @DisplayName("does not split on a delimiter inside a quoted string")
    void shouldNotSplitInsideQuotedString() {
        assertThat(McpQuotedHeaderTokens.split("application/json;profile=\"a,b\";q=0, text/plain", ','))
                .containsExactly("application/json;profile=\"a,b\";q=0", " text/plain");
        assertThat(McpQuotedHeaderTokens.split("profile=\"a;b\";q=0", ';')).containsExactly("profile=\"a;b\"", "q=0");
    }

    @Test
    @DisplayName("treats a backslash-escaped quote inside a quoted string as data, not a closing quote")
    void shouldHonorEscapedQuoteInsideQuotedString() {
        List<String> tokens = McpQuotedHeaderTokens.split("profile=\"a\\\"b,c\";q=0,text/plain", ',');

        assertThat(tokens).containsExactly("profile=\"a\\\"b,c\";q=0", "text/plain");
    }

    @Test
    @DisplayName("treats an escaped backslash before a closing quote as closing the string")
    void shouldCloseStringAfterEscapedBackslash() {
        assertThat(McpQuotedHeaderTokens.split("p=\"a\\\\\",b", ',')).containsExactly("p=\"a\\\\\"", "b");
    }

    @Test
    @DisplayName("treats a backslash outside a quoted string as a literal character")
    void shouldTreatBackslashOutsideQuotesAsLiteral() {
        assertThat(McpQuotedHeaderTokens.split("a\\,b", ',')).containsExactly("a\\", "b");
    }

    @Test
    @DisplayName("keeps the remainder of the value in one token when a quoted string is unterminated")
    void shouldKeepRemainderOfUnterminatedQuotedString() {
        assertThat(McpQuotedHeaderTokens.split("a,p=\"b,c", ',')).containsExactly("a", "p=\"b,c");
    }
}
