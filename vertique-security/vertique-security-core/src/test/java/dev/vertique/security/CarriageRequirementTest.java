// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CarriageRequirement}.
 *
 * <p>Verifies: all three enum constants exist; {@code valueOf} round-trips for each constant; the
 * enum has exactly three constants (P2.S0 commit 5b, PRD-ID-002 §14.6 A9).
 */
class CarriageRequirementTest {

    // --- all constants present ---

    @Test
    @DisplayName("REQUIRED constant exists")
    void requiredConstantExists() {
        assertNotNull(CarriageRequirement.REQUIRED);
    }

    @Test
    @DisplayName("OPTIONAL constant exists")
    void optionalConstantExists() {
        assertNotNull(CarriageRequirement.OPTIONAL);
    }

    @Test
    @DisplayName("FORBIDDEN constant exists")
    void forbiddenConstantExists() {
        assertNotNull(CarriageRequirement.FORBIDDEN);
    }

    // --- valueOf round-trips ---

    @Test
    @DisplayName("valueOf(\"REQUIRED\") returns REQUIRED")
    void valueOfRequired() {
        assertEquals(CarriageRequirement.REQUIRED, CarriageRequirement.valueOf("REQUIRED"));
    }

    @Test
    @DisplayName("valueOf(\"OPTIONAL\") returns OPTIONAL")
    void valueOfOptional() {
        assertEquals(CarriageRequirement.OPTIONAL, CarriageRequirement.valueOf("OPTIONAL"));
    }

    @Test
    @DisplayName("valueOf(\"FORBIDDEN\") returns FORBIDDEN")
    void valueOfForbidden() {
        assertEquals(CarriageRequirement.FORBIDDEN, CarriageRequirement.valueOf("FORBIDDEN"));
    }

    // --- exactly three constants ---

    @Test
    @DisplayName("enum has exactly three constants")
    void exactlyThreeConstants() {
        assertEquals(3, CarriageRequirement.values().length);
    }
}
