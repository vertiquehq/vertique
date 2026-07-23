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
 * Verifies that {@link AddressLinePolicy} accepts common address characters — Unicode letters,
 * digits, spaces, and address-specific punctuation — and rejects disallowed characters.
 */
class AddressLinePolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "address", "address", Object.class);

    private final AddressLinePolicy policy = new AddressLinePolicy();

    @Test
    @DisplayName("null value passes")
    void shouldPassNull() {
        assertTrue(policy.validate(null, CTX).valid());
    }

    @Test
    @DisplayName("simple street address passes")
    void shouldPassSimpleAddress() {
        assertTrue(policy.validate("123 Main Street", CTX).valid());
    }

    @Test
    @DisplayName("address with apartment number passes")
    void shouldPassAddressWithApt() {
        assertTrue(policy.validate("Apt #4B, 100 Oak Ave", CTX).valid());
    }

    @Test
    @DisplayName("address with suite and slash passes")
    void shouldPassAddressWithSlash() {
        assertTrue(policy.validate("Suite 5/10 East Block", CTX).valid());
    }

    @Test
    @DisplayName("address with parentheses passes")
    void shouldPassAddressWithParens() {
        assertTrue(policy.validate("Building A (East Wing)", CTX).valid());
    }

    @Test
    @DisplayName("address with ampersand passes")
    void shouldPassAddressWithAmpersand() {
        assertTrue(policy.validate("Johnson & Sons Building", CTX).valid());
    }

    @Test
    @DisplayName("address with hyphen passes")
    void shouldPassAddressWithHyphen() {
        assertTrue(policy.validate("44-46 Lower Road", CTX).valid());
    }

    @Test
    @DisplayName("Unicode address passes")
    void shouldPassUnicodeAddress() {
        assertTrue(policy.validate("Straße 42, Berlin", CTX).valid());
    }

    @Test
    @DisplayName("control character fails")
    void shouldFailControlCharacter() {
        assertFalse(policy.validate("123\u0000Main", CTX).valid());
    }

    @Test
    @DisplayName("semicolon fails")
    void shouldFailSemicolon() {
        assertFalse(policy.validate("123 Main; Street", CTX).valid());
    }

    @Test
    @DisplayName("emoji fails")
    void shouldFailEmoji() {
        assertFalse(policy.validate("123 \uD83D\uDE00 Street", CTX).valid());
    }
}
