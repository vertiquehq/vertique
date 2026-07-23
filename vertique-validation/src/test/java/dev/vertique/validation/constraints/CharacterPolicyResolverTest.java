// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyBinding;
import dev.vertique.core.validation.CharacterPolicyResult;
import jakarta.validation.ValidationException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three-tier resolution logic of {@link CharacterPolicyResolver}: Dagger binding,
 * reflection fallback, and failure when neither can provide an instance.
 */
class CharacterPolicyResolverTest {

    @Test
    @DisplayName("resolves policy from Dagger binding when present")
    void shouldResolvePolicyFromBinding() {
        var policy = new NoOpPolicy();
        var binding = new CharacterPolicyBinding(NoOpPolicy.class, policy);
        var resolver = new CharacterPolicyResolver(Set.of(binding));

        var resolved = resolver.resolve(NoOpPolicy.class);

        assertSame(policy, resolved);
    }

    @Test
    @DisplayName("falls back to reflection when no binding is present")
    void shouldFallBackToReflection() {
        var resolver = new CharacterPolicyResolver(Set.of());

        var resolved = resolver.resolve(NoOpPolicy.class);

        assertNotNull(resolved);
        assertInstanceOf(NoOpPolicy.class, resolved);
    }

    @Test
    @DisplayName("throws ValidationException when policy cannot be instantiated")
    void shouldThrowValidationExceptionForNonInstantiablePolicy() {
        var resolver = new CharacterPolicyResolver(Set.of());

        assertThrows(ValidationException.class, () -> resolver.resolve(PrivateConstructorPolicy.class));
    }

    // --- Test helpers ---

    /** A no-op policy with a public no-arg constructor for reflection fallback testing. */
    public static class NoOpPolicy implements CharacterPolicy {
        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            return CharacterPolicyResult.passed();
        }
    }

    /** A policy with a private constructor to test resolution failure. */
    static class PrivateConstructorPolicy implements CharacterPolicy {
        private PrivateConstructorPolicy(String unused) {}

        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            return CharacterPolicyResult.passed();
        }
    }
}
