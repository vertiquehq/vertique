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
 * Verifies that {@link UnicodePrintableTextPolicy} is the broadest policy — it allows all
 * non-control characters including emoji, rejecting only ISO control characters.
 */
class UnicodePrintableTextPolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "text", "text", Object.class);

    private final UnicodePrintableTextPolicy policy = new UnicodePrintableTextPolicy();

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
    @DisplayName("emoji passes (broadest policy)")
    void shouldPassEmoji() {
        assertTrue(policy.validate("Hello \uD83D\uDE00", CTX).valid());
    }

    @Test
    @DisplayName("mixed emoji and text passes")
    void shouldPassMixedEmojiText() {
        assertTrue(policy.validate("Hello \uD83D\uDE00 World \u2764\uFE0F", CTX).valid());
    }

    @Test
    @DisplayName("Unicode text passes")
    void shouldPassUnicodeText() {
        assertTrue(policy.validate("こんにちは 世界 🌍", CTX).valid());
    }

    @Test
    @DisplayName("NUL control character fails")
    void shouldFailNulControl() {
        assertFalse(policy.validate("bad\u0000char", CTX).valid());
    }

    @Test
    @DisplayName("form feed control character fails")
    void shouldFailFormFeedControl() {
        assertFalse(policy.validate("bad\u000Cchar", CTX).valid());
    }

    @Test
    @DisplayName("ESC control character fails")
    void shouldFailEscControl() {
        assertFalse(policy.validate("bad\u001Bchar", CTX).valid());
    }

    @Test
    @DisplayName("DEL control character fails")
    void shouldFailDelControl() {
        assertFalse(policy.validate("bad\u007Fchar", CTX).valid());
    }
}
