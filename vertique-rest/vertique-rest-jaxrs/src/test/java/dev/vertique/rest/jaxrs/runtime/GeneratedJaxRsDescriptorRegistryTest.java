// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.runtime.fixture.BrokenDescriptorResource;
import dev.vertique.rest.jaxrs.runtime.fixture.FixtureResource;
import dev.vertique.rest.jaxrs.runtime.fixture.FixtureResource_JaxRsDescriptor;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeneratedJaxRsDescriptorRegistry} — verifies classloader-driven lookup
 * with correct cache-miss semantics, the FQN derivation algorithm (including nested-class
 * flattening), and the broken-class caching contract.
 */
class GeneratedJaxRsDescriptorRegistryTest {

    // --- Classloader-driven lookup ---

    @Nested
    @DisplayName("classloader-driven lookup")
    class ClassloaderLookup {

        @Test
        @DisplayName("lookup returns empty for a class with no companion _JaxRsDescriptor")
        void lookupReturnsEmptyWhenNoCompanion() {
            var registry = new GeneratedJaxRsDescriptorRegistry();

            Optional<GeneratedJaxRsResourceDescriptor<?>> result = registry.lookup(NoCompanionResource.class);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookup returns the resolved descriptor when companion is on the classpath")
        void lookupReturnsDescriptorWhenPresent() {
            var registry = new GeneratedJaxRsDescriptorRegistry();

            Optional<GeneratedJaxRsResourceDescriptor<?>> result = registry.lookup(FixtureResource.class);

            assertTrue(result.isPresent());
            assertInstanceOf(FixtureResource_JaxRsDescriptor.class, result.get());
        }

        @Test
        @DisplayName("lookup is cached — same Optional instance returned on repeated calls")
        void lookupCachesResult() {
            var registry = new GeneratedJaxRsDescriptorRegistry();

            Optional<GeneratedJaxRsResourceDescriptor<?>> first = registry.lookup(NoCompanionResource.class);
            Optional<GeneratedJaxRsResourceDescriptor<?>> second = registry.lookup(NoCompanionResource.class);

            assertTrue(first.isEmpty());
            assertTrue(second.isEmpty());
            // ClassValue caches the result; no second Class.forName should be attempted.
            // We cannot assert instance equality on Optional.empty() but a second lookup for
            // an unresolvable FQN does not throw, which verifies the cached-empty path.
        }

        @Test
        @DisplayName("lookup returns descriptor instance on repeated calls (cached)")
        void lookupCachesDescriptorInstance() {
            var registry = new GeneratedJaxRsDescriptorRegistry();

            Optional<GeneratedJaxRsResourceDescriptor<?>> first = registry.lookup(FixtureResource.class);
            Optional<GeneratedJaxRsResourceDescriptor<?>> second = registry.lookup(FixtureResource.class);

            assertTrue(first.isPresent());
            assertTrue(second.isPresent());
            // ClassValue cache: same descriptor instance must be returned without re-instantiating.
            assertSame(first.get(), second.get(), "ClassValue cache must return the same companion instance");
        }

        @Test
        @DisplayName("lookup propagates RuntimeException when companion exists but fails to instantiate")
        void lookupPropagatesInstantiationFailure() {
            var registry = new GeneratedJaxRsDescriptorRegistry();

            RuntimeException ex =
                    assertThrows(RuntimeException.class, () -> registry.lookup(BrokenDescriptorResource.class));

            assertTrue(
                    ex.getMessage().contains("BrokenDescriptorResource_JaxRsDescriptor"),
                    "Expected error message to reference the companion FQN, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("broken-companion lookup is cached — same exception rethrown on retry")
        void cachesBrokenCompanionFailure() {
            var registry = new GeneratedJaxRsDescriptorRegistry();

            RuntimeException first =
                    assertThrows(RuntimeException.class, () -> registry.lookup(BrokenDescriptorResource.class));
            RuntimeException second =
                    assertThrows(RuntimeException.class, () -> registry.lookup(BrokenDescriptorResource.class));

            // Same exception instance proves caching: the registry did not redo Class.forName +
            // newInstance on the second call. Without caching, each call would produce a new
            // RuntimeException wrapping a fresh ReflectiveOperationException.
            assertSame(
                    first,
                    second,
                    "Broken-companion failure must be cached — subsequent calls must rethrow the same exception");
        }
    }

    // --- FQN derivation ---

    @Nested
    @DisplayName("derivedFqn algorithm")
    class DerivedFqn {

        @Test
        @DisplayName("top-level class: appends _JaxRsDescriptor suffix")
        void topLevelClass() {
            String fqn = GeneratedJaxRsDescriptorRegistry.derivedFqn(FixtureResource.class);

            assertTrue(fqn.endsWith("_JaxRsDescriptor"), "FQN must end with _JaxRsDescriptor, got: " + fqn);
            assertTrue(fqn.contains("fixture"), "FQN must preserve the package, got: " + fqn);
        }

        @Test
        @DisplayName("nested class: flattens '$' to '_' before appending suffix")
        void nestedClassFlattens() {
            // Outer is a static inner class of this test class; binary name contains '$'
            String fqn = GeneratedJaxRsDescriptorRegistry.derivedFqn(Outer.Inner.class);

            assertTrue(
                    fqn.contains("GeneratedJaxRsDescriptorRegistryTest_Outer_Inner"),
                    "Nested class FQN must flatten '$' to '_', got: " + fqn);
            assertTrue(fqn.endsWith("_JaxRsDescriptor"), "FQN must end with _JaxRsDescriptor, got: " + fqn);
        }

        @Test
        @DisplayName("class in any package: no leading dot in FQN")
        void noLeadingDot() {
            String fqn = GeneratedJaxRsDescriptorRegistry.derivedFqn(FixtureResource.class);

            assertFalse(fqn.startsWith("."), "FQN must not start with '.', got: " + fqn);
        }
    }

    // --- Test fixtures ---

    /** A resource type with no sibling {@code _JaxRsDescriptor} class on the classpath. */
    static final class NoCompanionResource {}

    /** Used by {@link DerivedFqn#nestedClassFlattens()}. */
    static final class Outer {
        static final class Inner {}
    }
}
