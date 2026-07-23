// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationDecision}.
 *
 * <p>Verifies: {@code permit} factory sets permitted=true; {@code deny} factory sets
 * permitted=false; null/blank {@code reasonCode} rejected; null {@code Optional} references for
 * {@code policyId}/{@code policyVersion} rejected; null {@code safeAttributes} treated as empty
 * map; defensive copy of {@code safeAttributes}.
 */
class AuthorizationDecisionTest {

    // --- permit factory ---

    @Test
    @DisplayName("permit() factory creates decision with permitted=true")
    void permitFactoryPermittedTrue() {
        AuthorizationDecision decision = AuthorizationDecision.permit("PERMITTED");
        assertTrue(decision.permitted());
    }

    @Test
    @DisplayName("permit() factory sets reasonCode correctly")
    void permitFactoryReasonCode() {
        AuthorizationDecision decision = AuthorizationDecision.permit("PERMITTED");
        assertEquals("PERMITTED", decision.reasonCode());
    }

    @Test
    @DisplayName("permit() factory has empty policyId and policyVersion")
    void permitFactoryEmptyPolicyFields() {
        AuthorizationDecision decision = AuthorizationDecision.permit("PERMITTED");
        assertFalse(decision.policyId().isPresent());
        assertFalse(decision.policyVersion().isPresent());
    }

    @Test
    @DisplayName("permit() factory has empty safeAttributes")
    void permitFactoryEmptySafeAttributes() {
        AuthorizationDecision decision = AuthorizationDecision.permit("PERMITTED");
        assertTrue(decision.safeAttributes().isEmpty());
    }

    // --- deny factory ---

    @Test
    @DisplayName("deny() factory creates decision with permitted=false")
    void denyFactoryPermittedFalse() {
        AuthorizationDecision decision = AuthorizationDecision.deny("ROLE_MISSING");
        assertFalse(decision.permitted());
    }

    @Test
    @DisplayName("deny() factory sets reasonCode correctly")
    void denyFactoryReasonCode() {
        AuthorizationDecision decision = AuthorizationDecision.deny("ROLE_MISSING");
        assertEquals("ROLE_MISSING", decision.reasonCode());
    }

    // --- full constructor ---

    @Test
    @DisplayName("full constructor with policyId and policyVersion present")
    void fullConstructorWithPolicyFields() {
        AuthorizationDecision decision = new AuthorizationDecision(
                true, "PERMITTED", Optional.of("policy-v2"), Optional.of("2024-01-15"), Map.of("debug", "ok"));
        assertTrue(decision.permitted());
        assertEquals("policy-v2", decision.policyId().get());
        assertEquals("2024-01-15", decision.policyVersion().get());
    }

    // --- null/blank rejection for reasonCode ---

    @Test
    @DisplayName("null reasonCode throws NullPointerException")
    void nullReasonCodeThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorizationDecision(true, null, Optional.empty(), Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("blank reasonCode throws IllegalArgumentException")
    void blankReasonCodeThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthorizationDecision(true, "   ", Optional.empty(), Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("empty reasonCode throws IllegalArgumentException")
    void emptyReasonCodeThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthorizationDecision(true, "", Optional.empty(), Optional.empty(), Map.of()));
    }

    // --- null Optional ref for policyId/policyVersion rejected ---

    @Test
    @DisplayName("null Optional reference for policyId throws NullPointerException")
    void nullOptionalPolicyIdThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorizationDecision(true, "PERMITTED", null, Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("null Optional reference for policyVersion throws NullPointerException")
    void nullOptionalPolicyVersionThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorizationDecision(true, "PERMITTED", Optional.empty(), null, Map.of()));
    }

    // --- null safeAttributes treated as empty ---

    @Test
    @DisplayName("null safeAttributes treated as empty map")
    void nullSafeAttributesTreatedAsEmpty() {
        AuthorizationDecision decision =
                new AuthorizationDecision(true, "PERMITTED", Optional.empty(), Optional.empty(), null);
        assertTrue(decision.safeAttributes().isEmpty());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source safeAttributes map after construction does not affect record")
    void defensivelyCopiesSafeAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        AuthorizationDecision decision =
                new AuthorizationDecision(true, "PERMITTED", Optional.empty(), Optional.empty(), mutable);
        mutable.put("injected", "evil");
        assertEquals(1, decision.safeAttributes().size(), "safeAttributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("safeAttributes map returned by accessor is unmodifiable")
    void safeAttributesIsUnmodifiable() {
        AuthorizationDecision decision =
                new AuthorizationDecision(true, "PERMITTED", Optional.empty(), Optional.empty(), Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> decision.safeAttributes()
                .put("x", "y"));
    }

    @Test
    @DisplayName("safeAttributes instance is not the original map reference")
    void safeAttributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        AuthorizationDecision decision =
                new AuthorizationDecision(true, "PERMITTED", Optional.empty(), Optional.empty(), original);
        assertNotSame(original, decision.safeAttributes());
    }
}
