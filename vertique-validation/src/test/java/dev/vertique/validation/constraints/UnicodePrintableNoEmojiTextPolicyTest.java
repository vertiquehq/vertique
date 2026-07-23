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
 * Verifies that {@link UnicodePrintableNoEmojiTextPolicy} allows all non-control characters except
 * emoji — the broadest policy minus emoji characters.
 */
class UnicodePrintableNoEmojiTextPolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    private final UnicodePrintableNoEmojiTextPolicy policy = new UnicodePrintableNoEmojiTextPolicy();

    @Test
    @DisplayName("null value passes")
    void shouldPassNull() {
        assertTrue(policy.validate(null, CTX).valid());
    }

    @Test
    @DisplayName("plain text passes")
    void shouldPassPlainText() {
        assertTrue(policy.validate("Hello World", CTX).valid());
    }

    @Test
    @DisplayName("Unicode text without emoji passes")
    void shouldPassUnicodeTextWithoutEmoji() {
        assertTrue(policy.validate("こんにちは 世界 — Café", CTX).valid());
    }

    @Test
    @DisplayName("text with punctuation passes")
    void shouldPassPunctuation() {
        assertTrue(policy.validate("Hello, World! How are you?", CTX).valid());
    }

    @Test
    @DisplayName("grinning face emoji fails")
    void shouldFailGrinningFaceEmoji() {
        // U+1F600 GRINNING FACE
        assertFalse(policy.validate("Hello \uD83D\uDE00", CTX).valid());
    }

    @Test
    @DisplayName("emoticon emoji in range U+1F600-U+1F64F fails")
    void shouldFailEmoticonEmoji() {
        // U+1F64F PERSON WITH FOLDED HANDS
        assertFalse(policy.validate("Thanks \uD83D\uDE4F", CTX).valid());
    }

    @Test
    @DisplayName("misc symbol emoji in range U+1F300-U+1F5FF fails")
    void shouldFailMiscSymbolEmoji() {
        // U+1F300 CYCLONE
        assertFalse(policy.validate("Storm \uD83C\uDF00", CTX).valid());
    }

    @Test
    @DisplayName("supplemental symbols emoji fails")
    void shouldFailSupplementalSymbols() {
        // U+1F900 CIRCLED CROSS FORMEE WITH FOUR DOTS
        assertFalse(policy.validate("Symbol \uD83E\uDD00", CTX).valid());
    }

    @Test
    @DisplayName("NUL control character fails")
    void shouldFailNulControl() {
        assertFalse(policy.validate("bad\u0000char", CTX).valid());
    }

    @Test
    @DisplayName("ESC control character fails")
    void shouldFailEscControl() {
        assertFalse(policy.validate("bad\u001Bchar", CTX).valid());
    }
}
