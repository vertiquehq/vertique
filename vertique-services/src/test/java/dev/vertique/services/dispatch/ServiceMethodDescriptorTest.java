// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceMethodDescriptor}.
 *
 * <p>Verifies pre-cached resolution via {@link ServiceMethodDescriptor#of(Method)}, lazy resolution
 * via {@link ServiceMethodDescriptor#of(String, List, Class)}, equality semantics, and
 * {@link Object#toString()} formatting.
 */
class ServiceMethodDescriptorTest {

    // --- of(Method) ---

    @Nested
    @DisplayName("of(Method) — pre-cached factory")
    class OfMethod {

        @Test
        @DisplayName("resolve() should return the same Method instance passed to of(Method)")
        void precachesResolvedMethod() throws Exception {
            Method method = Object.class.getMethod("toString");
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of(method);

            assertSame(method, descriptor.resolve(), "should return the pre-cached method instance");
        }

        @Test
        @DisplayName("Second call to resolve() should return the same cached instance")
        void resolveCachesResult() throws Exception {
            Method method = Object.class.getMethod("hashCode");
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of(method);

            Method first = descriptor.resolve();
            Method second = descriptor.resolve();
            assertSame(first, second, "repeated resolve() calls should return the same instance");
        }

        @Test
        @DisplayName("name(), parameterTypes(), and declaringClass() are populated from Method")
        void fieldsPopulatedFromMethod() throws Exception {
            Method method = String.class.getMethod("indexOf", int.class, int.class);
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of(method);

            assertEquals("indexOf", descriptor.name());
            assertEquals(List.of(int.class, int.class), descriptor.parameterTypes());
            assertSame(String.class, descriptor.declaringClass());
        }
    }

    // --- of(String, List, Class) ---

    @Nested
    @DisplayName("of(String, List, Class) — lazy factory")
    class OfStrings {

        @Test
        @DisplayName("resolve() should find and cache the method on first call")
        void lazyResolvesOnFirstCall() throws Exception {
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of("toString", List.of(), Object.class);

            Method resolved = descriptor.resolve();

            assertEquals("toString", resolved.getName());
            assertSame(Object.class, resolved.getDeclaringClass());
        }

        @Test
        @DisplayName("Second call to resolve() should return the same cached instance")
        void lazyResolveCachesResult() {
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of("hashCode", List.of(), Object.class);

            Method first = descriptor.resolve();
            Method second = descriptor.resolve();
            assertSame(first, second, "lazy resolution should be cached after first call");
        }

        @Test
        @DisplayName("resolve() should throw IllegalStateException when method is not found")
        void nonExistentMethodThrowsIllegalState() {
            ServiceMethodDescriptor descriptor =
                    ServiceMethodDescriptor.of("nonExistentMethod", List.of(), Object.class);

            assertThrows(IllegalStateException.class, descriptor::resolve);
        }

        @Test
        @DisplayName("resolve() should find methods with parameters")
        void resolvesMethodWithParameters() throws Exception {
            ServiceMethodDescriptor descriptor =
                    ServiceMethodDescriptor.of("equals", List.of(Object.class), Object.class);

            Method resolved = descriptor.resolve();
            assertEquals("equals", resolved.getName());
        }
    }

    // --- Equality ---

    @Nested
    @DisplayName("equals() and hashCode()")
    class Equality {

        @Test
        @DisplayName("Two descriptors with same name, params, and declaring class should be equal")
        void sameTripleIsEqual() {
            ServiceMethodDescriptor a = ServiceMethodDescriptor.of("toString", List.of(), Object.class);
            ServiceMethodDescriptor b = ServiceMethodDescriptor.of("toString", List.of(), Object.class);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Descriptors with different names should not be equal")
        void differentNameNotEqual() {
            ServiceMethodDescriptor a = ServiceMethodDescriptor.of("toString", List.of(), Object.class);
            ServiceMethodDescriptor b = ServiceMethodDescriptor.of("hashCode", List.of(), Object.class);

            assertNotSame(a, b);
            assertEquals(false, a.equals(b));
        }

        @Test
        @DisplayName("Descriptors with different declaring classes should not be equal")
        void differentDeclaringClassNotEqual() {
            ServiceMethodDescriptor a = ServiceMethodDescriptor.of("toString", List.of(), Object.class);
            ServiceMethodDescriptor b = ServiceMethodDescriptor.of("toString", List.of(), String.class);

            assertEquals(false, a.equals(b));
        }

        @Test
        @DisplayName("Descriptors with different parameter types should not be equal")
        void differentParamsNotEqual() {
            ServiceMethodDescriptor a = ServiceMethodDescriptor.of("indexOf", List.of(int.class), String.class);
            ServiceMethodDescriptor b = ServiceMethodDescriptor.of("indexOf", List.of(String.class), String.class);

            assertEquals(false, a.equals(b));
        }

        @Test
        @DisplayName("of(Method) and of(String,...) descriptors for the same method should be equal")
        void ofMethodAndOfStringsAreEqual() throws Exception {
            Method method = Object.class.getMethod("toString");
            ServiceMethodDescriptor fromMethod = ServiceMethodDescriptor.of(method);
            ServiceMethodDescriptor fromStrings = ServiceMethodDescriptor.of("toString", List.of(), Object.class);

            assertEquals(fromMethod, fromStrings);
        }
    }

    // --- toString ---

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("toString() should contain the declaring class simple name and method name")
        void toStringContainsSimpleNameAndMethodName() {
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of("toString", List.of(), Object.class);

            String str = descriptor.toString();
            assertTrue(str.contains("Object"), "should contain declaring class simple name");
            assertTrue(str.contains("toString"), "should contain method name");
        }

        @Test
        @DisplayName("toString() uses simple name (not fully qualified) for declaring class")
        void toStringUsesSimpleName() throws Exception {
            Method method = String.class.getMethod("length");
            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of(method);

            String str = descriptor.toString();
            assertTrue(str.contains("String"), "should contain 'String'");
            assertEquals(false, str.contains("java.lang.String"), "should not contain fully qualified name");
        }
    }
}
