// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.CanonicalizerBinding;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SanitizerBinding;
import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three-tier resolution strategy of {@link ProcessorResolver}: Dagger binding lookup,
 * reflection fallback, and error on unresolvable class.
 */
class ProcessorResolverTest {

    // --- Canonicalizer resolution ---

    @Nested
    class CanonicalizerResolution {

        @Test
        @DisplayName("resolves canonicalizer from Dagger binding (tier 1)")
        void resolvesFromBinding() {
            var instance = new TrimCanonicalizer();
            var resolver = new ProcessorResolver(
                    Set.of(new CanonicalizerBinding(TrimCanonicalizer.class, instance)), Set.of());

            var result = resolver.resolveCanonicalizer(TrimCanonicalizer.class);

            assertSame(instance, result);
        }

        @Test
        @DisplayName("falls back to reflection when canonicalizer is not in bindings (tier 2)")
        void fallsBackToReflection() {
            var resolver = new ProcessorResolver(Set.of(), Set.of());

            var result = resolver.resolveCanonicalizer(TrimCanonicalizer.class);

            assertNotNull(result);
            assertInstanceOf(TrimCanonicalizer.class, result);
        }

        @Test
        @DisplayName("throws IllegalStateException for canonicalizer without no-arg constructor (tier 3)")
        void throwsForNoArgConstructorAbsent() {
            var resolver = new ProcessorResolver(Set.of(), Set.of());

            assertThrows(
                    IllegalStateException.class, () -> resolver.resolveCanonicalizer(NoArglessCanonicalizerStub.class));
        }
    }

    // --- Sanitizer resolution ---

    @Nested
    class SanitizerResolution {

        @Test
        @DisplayName("resolves sanitizer from Dagger binding (tier 1)")
        void resolvesFromBinding() {
            var instance = new StripControlCharsSanitizer();
            var resolver = new ProcessorResolver(
                    Set.of(), Set.of(new SanitizerBinding(StripControlCharsSanitizer.class, instance)));

            var result = resolver.resolveSanitizer(StripControlCharsSanitizer.class);

            assertSame(instance, result);
        }

        @Test
        @DisplayName("falls back to reflection when sanitizer is not in bindings (tier 2)")
        void fallsBackToReflection() {
            var resolver = new ProcessorResolver(Set.of(), Set.of());

            var result = resolver.resolveSanitizer(StripControlCharsSanitizer.class);

            assertNotNull(result);
            assertInstanceOf(StripControlCharsSanitizer.class, result);
        }

        @Test
        @DisplayName("throws IllegalStateException for sanitizer without no-arg constructor (tier 3)")
        void throwsForNoArgConstructorAbsent() {
            var resolver = new ProcessorResolver(Set.of(), Set.of());

            assertThrows(IllegalStateException.class, () -> resolver.resolveSanitizer(NoArglessSanitizerStub.class));
        }
    }

    // --- Test stubs ---

    /** A canonicalizer that has no public no-arg constructor (requires a String argument). */
    public static class NoArglessCanonicalizerStub implements Canonicalizer {

        @SuppressWarnings("unused")
        public NoArglessCanonicalizerStub(String required) {}

        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /** A sanitizer that has no public no-arg constructor (requires a String argument). */
    public static class NoArglessSanitizerStub implements Sanitizer {

        @SuppressWarnings("unused")
        public NoArglessSanitizerStub(String required) {}

        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }
}
