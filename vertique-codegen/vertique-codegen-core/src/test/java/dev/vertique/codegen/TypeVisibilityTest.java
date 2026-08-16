// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TypeVisibility#isReferenceableFrom} against the access rules generated aggregate
 * modules depend on.
 *
 * <p>Each case corresponds to source javac either accepts or rejects. The two that were originally
 * wrong — a {@code public} type in the unnamed package, and a {@code private} nested type in the
 * module's own package — both emitted bindings that failed to compile, so they are pinned here
 * rather than only at the processor level.
 */
class TypeVisibilityTest {

    // --- Fixtures ---

    /** Builds a top-level type element with the given modifiers. */
    private static TypeElement topLevel(Modifier... modifiers) {
        TypeElement type = mock(TypeElement.class);
        when(type.getModifiers()).thenReturn(Set.of(modifiers));
        when(type.getEnclosingElement()).thenReturn(mock(PackageElement.class));
        return type;
    }

    /** Builds a type element nested directly inside {@code outer}. */
    private static TypeElement nestedIn(TypeElement outer, Modifier... modifiers) {
        TypeElement type = mock(TypeElement.class);
        when(type.getModifiers()).thenReturn(Set.of(modifiers));
        when(type.getEnclosingElement()).thenReturn((Element) outer);
        return type;
    }

    @Nested
    @DisplayName("within the module's own package")
    class SamePackage {

        @Test
        @DisplayName("any access level except private is referenceable")
        void nonPrivateAccessLevelsAreReferenceable() {
            assertTrue(TypeVisibility.isReferenceableFrom(topLevel(Modifier.PUBLIC), "com.foo", "com.foo"));
            assertTrue(TypeVisibility.isReferenceableFrom(topLevel(), "com.foo", "com.foo"));
            assertTrue(TypeVisibility.isReferenceableFrom(
                    nestedIn(topLevel(Modifier.PUBLIC), Modifier.PROTECTED), "com.foo", "com.foo"));
        }

        @Test
        @DisplayName("a private nested type is not referenceable — generated source is a separate unit")
        void privateNestedTypeIsNotReferenceable() {
            TypeElement outer = topLevel(Modifier.PUBLIC);
            assertFalse(TypeVisibility.isReferenceableFrom(nestedIn(outer, Modifier.PRIVATE), "com.foo", "com.foo"));
        }

        @Test
        @DisplayName("a type enclosed by a private type is not referenceable")
        void typeInsidePrivateEnclosureIsNotReferenceable() {
            TypeElement outer = topLevel(Modifier.PUBLIC);
            TypeElement middle = nestedIn(outer, Modifier.PRIVATE);
            assertFalse(TypeVisibility.isReferenceableFrom(nestedIn(middle, Modifier.PUBLIC), "com.foo", "com.foo"));
        }

        @Test
        @DisplayName("the unnamed package can reference its own types")
        void unnamedPackageCanReferenceItself() {
            assertTrue(TypeVisibility.isReferenceableFrom(topLevel(Modifier.PUBLIC), "", ""));
        }
    }

    @Nested
    @DisplayName("across packages")
    class DifferentPackage {

        @Test
        @DisplayName("a public top-level type is referenceable")
        void publicTopLevelIsReferenceable() {
            assertTrue(TypeVisibility.isReferenceableFrom(topLevel(Modifier.PUBLIC), "com.foo", "com.bar"));
        }

        @Test
        @DisplayName("a package-private type is not referenceable")
        void packagePrivateIsNotReferenceable() {
            assertFalse(TypeVisibility.isReferenceableFrom(topLevel(), "com.foo", "com.bar"));
        }

        @Test
        @DisplayName("a public type nested in a non-public type is not referenceable")
        void publicNestedInNonPublicIsNotReferenceable() {
            assertFalse(
                    TypeVisibility.isReferenceableFrom(nestedIn(topLevel(), Modifier.PUBLIC), "com.foo", "com.bar"));
        }

        @Test
        @DisplayName("a public type nested in a public type is referenceable")
        void publicNestedInPublicIsReferenceable() {
            assertTrue(TypeVisibility.isReferenceableFrom(
                    nestedIn(topLevel(Modifier.PUBLIC), Modifier.PUBLIC), "com.foo", "com.bar"));
        }

        @Test
        @DisplayName("a public type in the unnamed package is not referenceable from a named package")
        void publicTypeInUnnamedPackageIsNotReferenceable() {
            // The original bug: this fell through to the modifier walk, which a public type passes,
            // so a binding was emitted that could not resolve the simple name.
            assertFalse(
                    TypeVisibility.isReferenceableFrom(topLevel(Modifier.PUBLIC), "", "vertique.generated.delayedjob"));
        }

        @Test
        @DisplayName("a named-package type is not referenceable from the unnamed package")
        void namedPackageTypeIsNotReferenceableFromUnnamed() {
            assertFalse(TypeVisibility.isReferenceableFrom(topLevel(Modifier.PUBLIC), "com.foo", ""));
        }
    }
}
