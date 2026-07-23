// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EffectiveInputPolicies} — verifies empty constant and
 * {@code hasNoRouteChains()} logic.
 */
class EffectiveInputPoliciesTest {

    // --- NONE constant ---

    @Test
    @DisplayName("NONE constant has empty canonicalizer and sanitizer chains")
    void noneConstantIsEmpty() {
        assertTrue(EffectiveInputPolicies.NONE.routeCanonicalizers().isEmpty());
        assertTrue(EffectiveInputPolicies.NONE.routeSanitizers().isEmpty());
    }

    @Test
    @DisplayName("NONE hasNoRouteChains() returns true")
    void noneHasNoRouteChainsReturnsTrue() {
        assertTrue(EffectiveInputPolicies.NONE.hasNoRouteChains());
    }

    // --- hasNoRouteChains() ---

    @Test
    @DisplayName("hasNoRouteChains() returns false when canonicalizer chain is non-empty")
    void hasNoRouteChainsReturnsFalseWhenCanonicalizersPresent() {
        var policies = new EffectiveInputPolicies(List.of(StubCanonicalizer.class), List.of());
        assertFalse(policies.hasNoRouteChains());
    }

    @Test
    @DisplayName("hasNoRouteChains() returns false when sanitizer chain is non-empty")
    void hasNoRouteChainsReturnsFalseWhenSanitizersPresent() {
        var policies = new EffectiveInputPolicies(List.of(), List.of(StubSanitizer.class));
        assertFalse(policies.hasNoRouteChains());
    }

    @Test
    @DisplayName("hasNoRouteChains() returns true when both chains are empty")
    void hasNoRouteChainsReturnsTrueWhenBothChainsEmpty() {
        var policies = new EffectiveInputPolicies(List.of(), List.of());
        assertTrue(policies.hasNoRouteChains());
    }

    // --- NONE is canonical singleton ---

    @Test
    @DisplayName("NONE is a stable singleton reference")
    void noneIsSingleton() {
        assertSame(EffectiveInputPolicies.NONE, EffectiveInputPolicies.NONE);
    }

    // --- Stub implementations ---

    static class StubCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    static class StubSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }
}
