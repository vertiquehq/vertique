// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

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
 * {@code isEmpty()} logic.
 */
class EffectiveInputPoliciesTest {

    // --- NONE constant ---

    @Test
    @DisplayName("NONE constant has empty canonicalizer and sanitizer chains")
    void noneConstantIsEmpty() {
        assertTrue(EffectiveInputPolicies.NONE.canonicalizers().isEmpty());
        assertTrue(EffectiveInputPolicies.NONE.sanitizers().isEmpty());
    }

    @Test
    @DisplayName("NONE isEmpty() returns true")
    void noneIsEmptyReturnsTrue() {
        assertTrue(EffectiveInputPolicies.NONE.isEmpty());
    }

    // --- isEmpty() ---

    @Test
    @DisplayName("isEmpty() returns false when canonicalizer chain is non-empty")
    void isEmptyReturnsFalseWhenCanonicalizersPresent() {
        var policies = new EffectiveInputPolicies(List.of(StubCanonicalizer.class), List.of());
        assertFalse(policies.isEmpty());
    }

    @Test
    @DisplayName("isEmpty() returns false when sanitizer chain is non-empty")
    void isEmptyReturnsFalseWhenSanitizersPresent() {
        var policies = new EffectiveInputPolicies(List.of(), List.of(StubSanitizer.class));
        assertFalse(policies.isEmpty());
    }

    @Test
    @DisplayName("isEmpty() returns true when both chains are empty")
    void isEmptyReturnsTrueWhenBothChainsEmpty() {
        var policies = new EffectiveInputPolicies(List.of(), List.of());
        assertTrue(policies.isEmpty());
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
