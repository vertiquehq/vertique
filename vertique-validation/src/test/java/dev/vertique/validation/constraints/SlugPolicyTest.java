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
 * Verifies that {@link SlugPolicy} accepts lowercase ASCII letters, digits, and hyphens,
 * and rejects uppercase letters, spaces, and special characters.
 */
class SlugPolicyTest {

    private static final InputValueContext CTX =
            new InputValueContext(InputLocation.BODY, "slug", "slug", Object.class);

    private final SlugPolicy policy = new SlugPolicy();

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
    @DisplayName("lowercase letters pass")
    void shouldPassLowercaseLetters() {
        assertTrue(policy.validate("hello", CTX).valid());
    }

    @Test
    @DisplayName("digits pass")
    void shouldPassDigits() {
        assertTrue(policy.validate("123", CTX).valid());
    }

    @Test
    @DisplayName("hyphen passes")
    void shouldPassHyphen() {
        assertTrue(policy.validate("hello-world", CTX).valid());
    }

    @Test
    @DisplayName("valid slug with letters, digits, and hyphens passes")
    void shouldPassValidSlug() {
        assertTrue(policy.validate("my-slug-2024", CTX).valid());
    }

    @Test
    @DisplayName("uppercase letter fails")
    void shouldFailUppercase() {
        assertFalse(policy.validate("Hello", CTX).valid());
    }

    @Test
    @DisplayName("space fails")
    void shouldFailSpace() {
        assertFalse(policy.validate("hello world", CTX).valid());
    }

    @Test
    @DisplayName("underscore fails")
    void shouldFailUnderscore() {
        assertFalse(policy.validate("hello_world", CTX).valid());
    }

    @Test
    @DisplayName("period fails")
    void shouldFailPeriod() {
        assertFalse(policy.validate("hello.world", CTX).valid());
    }

    @Test
    @DisplayName("Unicode letter fails")
    void shouldFailUnicodeLetter() {
        assertFalse(policy.validate("café", CTX).valid());
    }
}
