// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.runtime.fixture.BrokenBeanResource;
import dev.vertique.rest.jaxrs.runtime.fixture.FixtureBean;
import dev.vertique.rest.jaxrs.runtime.fixture.FixtureBean_BeanParamModel;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeneratedJaxRsBeanParamRegistry} — mirrors the coverage of
 * {@link GeneratedJaxRsDescriptorRegistryTest} for the bean-param companion path: classloader
 * lookup, FQN derivation, broken-companion caching.
 */
class GeneratedJaxRsBeanParamRegistryTest {

    // --- Classloader-driven lookup ---

    @Nested
    @DisplayName("classloader-driven lookup")
    class ClassloaderLookup {

        @Test
        @DisplayName("lookup returns empty for a class with no companion _BeanParamModel")
        void lookupReturnsEmptyWhenNoCompanion() {
            var registry = new GeneratedJaxRsBeanParamRegistry();

            Optional<GeneratedJaxRsBeanParamModel<?>> result = registry.lookup(NoCompanionBean.class);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("lookup returns the resolved model when companion is on the classpath")
        void lookupReturnsModelWhenPresent() {
            var registry = new GeneratedJaxRsBeanParamRegistry();

            Optional<GeneratedJaxRsBeanParamModel<?>> result = registry.lookup(FixtureBean.class);

            assertTrue(result.isPresent());
            assertInstanceOf(FixtureBean_BeanParamModel.class, result.get());
        }

        @Test
        @DisplayName("lookup returns same model instance on repeated calls (cached)")
        void lookupCachesModelInstance() {
            var registry = new GeneratedJaxRsBeanParamRegistry();

            Optional<GeneratedJaxRsBeanParamModel<?>> first = registry.lookup(FixtureBean.class);
            Optional<GeneratedJaxRsBeanParamModel<?>> second = registry.lookup(FixtureBean.class);

            assertTrue(first.isPresent());
            assertTrue(second.isPresent());
            assertSame(first.get(), second.get(), "ClassValue cache must return the same companion instance");
        }

        @Test
        @DisplayName("lookup propagates RuntimeException when companion exists but fails to instantiate")
        void lookupPropagatesInstantiationFailure() {
            var registry = new GeneratedJaxRsBeanParamRegistry();

            RuntimeException ex = assertThrows(RuntimeException.class, () -> registry.lookup(BrokenBeanResource.class));

            assertTrue(
                    ex.getMessage().contains("BrokenBeanResource_BeanParamModel"),
                    "Expected error message to reference the companion FQN, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("broken-companion lookup is cached — same exception rethrown on retry")
        void cachesBrokenCompanionFailure() {
            var registry = new GeneratedJaxRsBeanParamRegistry();

            RuntimeException first =
                    assertThrows(RuntimeException.class, () -> registry.lookup(BrokenBeanResource.class));
            RuntimeException second =
                    assertThrows(RuntimeException.class, () -> registry.lookup(BrokenBeanResource.class));

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
        @DisplayName("top-level class: appends _BeanParamModel suffix")
        void topLevelClass() {
            String fqn = GeneratedJaxRsBeanParamRegistry.derivedFqn(FixtureBean.class);

            assertTrue(fqn.endsWith("_BeanParamModel"), "FQN must end with _BeanParamModel, got: " + fqn);
            assertTrue(fqn.contains("fixture"), "FQN must preserve the package, got: " + fqn);
        }

        @Test
        @DisplayName("nested class: flattens '$' to '_' before appending suffix")
        void nestedClassFlattens() {
            String fqn = GeneratedJaxRsBeanParamRegistry.derivedFqn(Outer.Inner.class);

            assertTrue(
                    fqn.contains("GeneratedJaxRsBeanParamRegistryTest_Outer_Inner"),
                    "Nested class FQN must flatten '$' to '_', got: " + fqn);
            assertTrue(fqn.endsWith("_BeanParamModel"), "FQN must end with _BeanParamModel, got: " + fqn);
        }

        @Test
        @DisplayName("class in any package: no leading dot in FQN")
        void noLeadingDot() {
            String fqn = GeneratedJaxRsBeanParamRegistry.derivedFqn(FixtureBean.class);

            assertFalse(fqn.startsWith("."), "FQN must not start with '.', got: " + fqn);
        }
    }

    // --- Test fixtures ---

    /** A bean type with no sibling {@code _BeanParamModel} class on the classpath. */
    static final class NoCompanionBean {}

    /** Used by {@link DerivedFqn#nestedClassFlattens()}. */
    static final class Outer {
        static final class Inner {}
    }
}
