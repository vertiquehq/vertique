// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrincipalType}.
 *
 * <p>Verifies: all four enum constants exist; {@code valueOf} round-trips for each constant.
 */
class PrincipalTypeTest {

    // --- all constants present ---

    @Test
    @DisplayName("USER constant exists")
    void userConstantExists() {
        assertNotNull(PrincipalType.USER);
    }

    @Test
    @DisplayName("SERVICE constant exists")
    void serviceConstantExists() {
        assertNotNull(PrincipalType.SERVICE);
    }

    @Test
    @DisplayName("SYSTEM constant exists")
    void systemConstantExists() {
        assertNotNull(PrincipalType.SYSTEM);
    }

    @Test
    @DisplayName("ANONYMOUS constant exists")
    void anonymousConstantExists() {
        assertNotNull(PrincipalType.ANONYMOUS);
    }

    // --- valueOf round-trips ---

    @Test
    @DisplayName("valueOf(\"USER\") returns USER")
    void valueOfUser() {
        assertEquals(PrincipalType.USER, PrincipalType.valueOf("USER"));
    }

    @Test
    @DisplayName("valueOf(\"SERVICE\") returns SERVICE")
    void valueOfService() {
        assertEquals(PrincipalType.SERVICE, PrincipalType.valueOf("SERVICE"));
    }

    @Test
    @DisplayName("valueOf(\"SYSTEM\") returns SYSTEM")
    void valueOfSystem() {
        assertEquals(PrincipalType.SYSTEM, PrincipalType.valueOf("SYSTEM"));
    }

    @Test
    @DisplayName("valueOf(\"ANONYMOUS\") returns ANONYMOUS")
    void valueOfAnonymous() {
        assertEquals(PrincipalType.ANONYMOUS, PrincipalType.valueOf("ANONYMOUS"));
    }

    // --- exactly four constants ---

    @Test
    @DisplayName("enum has exactly four constants")
    void exactlyFourConstants() {
        assertEquals(4, PrincipalType.values().length);
    }
}
