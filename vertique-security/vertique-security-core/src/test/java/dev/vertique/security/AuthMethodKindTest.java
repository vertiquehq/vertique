// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthMethodKind} enum constants and completeness.
 */
class AuthMethodKindTest {

    @Test
    void valueOfHmacReturnsHmacConstant() {
        assertEquals(AuthMethodKind.HMAC, AuthMethodKind.valueOf("HMAC"));
    }

    @Test
    void valuesContainsHmac() {
        assertTrue(Arrays.asList(AuthMethodKind.values()).contains(AuthMethodKind.HMAC));
    }

    @Test
    void valuesHasExpectedCount() {
        assertEquals(8, AuthMethodKind.values().length);
    }
}
