// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link UnicodeCommonTextPolicy} accepts text in Unicode letter/number/separator
 * categories plus the documented punctuation allowlist, and rejects control characters and emoji.
 */
class UnicodeCommonTextPolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    private final UnicodeCommonTextPolicy policy = new UnicodeCommonTextPolicy();

    @Test
    @DisplayName("null value passes")
    void shouldPassNull() {
        assertTrue(policy.validate(null, CTX).valid());
    }

    @Test
    @DisplayName("plain ASCII text passes")
    void shouldPassAsciiText() {
        assertTrue(policy.validate("Hello, World!", CTX).valid());
    }

    @Test
    @DisplayName("text with common punctuation passes")
    void shouldPassCommonPunctuation() {
        assertTrue(policy.validate(
                        "Yes. No? Maybe: OK; (test) [value] {key} / \\ @user #tag $price % & * + = _ ~ ^ ` | < >", CTX)
                .valid());
    }

    @Test
    @DisplayName("Unicode text with letters and digits passes")
    void shouldPassUnicodeText() {
        assertTrue(policy.validate("Héllo Wörld 123 — こんにちは", CTX).valid());
    }

    @Test
    @DisplayName("text with quotes passes")
    void shouldPassQuotes() {
        assertTrue(policy.validate("She said \"hello\" and it's fine", CTX).valid());
    }

    @Test
    @DisplayName("control character NUL fails")
    void shouldFailNullByte() {
        assertFalse(policy.validate("bad\u0000char", CTX).valid());
    }

    @Test
    @DisplayName("control character TAB fails")
    void shouldFailTab() {
        assertFalse(policy.validate("bad\tchar", CTX).valid());
    }

    @Test
    @DisplayName("control character newline fails")
    void shouldFailNewline() {
        assertFalse(policy.validate("bad\nchar", CTX).valid());
    }

    @Test
    @DisplayName("emoji fails")
    void shouldFailEmoji() {
        // U+1F600 GRINNING FACE emoji
        assertFalse(policy.validate("Hello \uD83D\uDE00", CTX).valid());
    }

    @Test
    @DisplayName("face emoji fails")
    void shouldFailFaceEmoji() {
        // U+1F64F PERSON WITH FOLDED HANDS
        assertFalse(policy.validate("Thanks \uD83D\uDE4F", CTX).valid());
    }
}
