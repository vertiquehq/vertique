// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicyResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link IdentifierPolicy} accepts valid identifier characters (Unicode letters,
 * digits, underscore, hyphen, period) and rejects disallowed characters with correct position
 * and code point reporting.
 */
class IdentifierPolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "field", "field", Object.class);

    private final IdentifierPolicy policy = new IdentifierPolicy();

    @Test
    @DisplayName("null value passes")
    void shouldPassNull() {
        assertTrue(policy.validate(null, CTX).valid());
    }

    @Test
    @DisplayName("empty string passes")
    void shouldPassEmpty() {
        assertTrue(policy.validate("", CTX).valid());
    }

    @Test
    @DisplayName("ASCII letters pass")
    void shouldPassAsciiLetters() {
        assertTrue(policy.validate("abcXYZ", CTX).valid());
    }

    @Test
    @DisplayName("digits pass")
    void shouldPassDigits() {
        assertTrue(policy.validate("0123456789", CTX).valid());
    }

    @Test
    @DisplayName("underscore passes")
    void shouldPassUnderscore() {
        assertTrue(policy.validate("my_field", CTX).valid());
    }

    @Test
    @DisplayName("hyphen passes")
    void shouldPassHyphen() {
        assertTrue(policy.validate("my-field", CTX).valid());
    }

    @Test
    @DisplayName("period passes")
    void shouldPassPeriod() {
        assertTrue(policy.validate("my.field", CTX).valid());
    }

    @Test
    @DisplayName("Unicode letter passes")
    void shouldPassUnicodeLetter() {
        assertTrue(policy.validate("café_123", CTX).valid());
    }

    @Test
    @DisplayName("space fails with correct index")
    void shouldFailSpace() {
        CharacterPolicyResult result = policy.validate("bad id", CTX);
        assertFalse(result.valid());
        assertEquals(3, result.invalidIndex());
        assertEquals(' ', (char) result.invalidCodePoint().intValue());
    }

    @Test
    @DisplayName("at-sign fails")
    void shouldFailAtSign() {
        CharacterPolicyResult result = policy.validate("user@domain", CTX);
        assertFalse(result.valid());
        assertEquals(4, result.invalidIndex());
        assertEquals('@', (char) result.invalidCodePoint().intValue());
    }

    @Test
    @DisplayName("control character fails")
    void shouldFailControlCharacter() {
        CharacterPolicyResult result = policy.validate("bad\u0000char", CTX);
        assertFalse(result.valid());
        assertEquals(3, result.invalidIndex());
    }

    @Test
    @DisplayName("first invalid char position reported when multiple invalid chars present")
    void shouldReportFirstInvalidChar() {
        CharacterPolicyResult result = policy.validate("ok@bad!", CTX);
        assertFalse(result.valid());
        assertEquals(2, result.invalidIndex());
        assertEquals('@', (char) result.invalidCodePoint().intValue());
    }
}
