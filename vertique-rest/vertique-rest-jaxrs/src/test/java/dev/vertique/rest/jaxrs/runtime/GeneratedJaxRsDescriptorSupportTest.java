// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeneratedJaxRsDescriptorSupport} — verifies type resolution helpers and
 * effective annotation resolution delegates including the interface-walk gap closure.
 */
class GeneratedJaxRsDescriptorSupportTest {

    private final GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();
    private final ClassLoader cl = getClass().getClassLoader();

    // --- Type resolution ---

    @Nested
    @DisplayName("resolveClass")
    class ResolveClass {

        @Test
        @DisplayName("resolves a known class by FQN")
        void resolvesKnownClass() throws ClassNotFoundException {
            Class<?> resolved = support.resolveClass("java.lang.String", cl);

            assertEquals(String.class, resolved);
        }

        @Test
        @DisplayName("propagates ClassNotFoundException for an unknown FQN")
        void propagatesClassNotFoundException() {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> support.resolveClass("dev.vertique.nonexistent.DoesNotExist", cl));
        }
    }

    @Nested
    @DisplayName("resolveClasses")
    class ResolveClasses {

        @Test
        @DisplayName("resolves an array of FQNs in order")
        void resolvesArray() throws ClassNotFoundException {
            Class<?>[] result = support.resolveClasses(new String[] {"java.lang.String", "java.lang.Integer"}, cl);

            assertArrayEquals(new Class<?>[] {String.class, Integer.class}, result);
        }

        @Test
        @DisplayName("propagates ClassNotFoundException when any FQN is missing")
        void propagatesWhenOneMissing() {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> support.resolveClasses(new String[] {"java.lang.String", "dev.vertique.Missing"}, cl));
        }

        @Test
        @DisplayName("returns empty array for empty input")
        void emptyInput() throws ClassNotFoundException {
            Class<?>[] result = support.resolveClasses(new String[0], cl);

            assertEquals(0, result.length);
        }
    }

    @Nested
    @DisplayName("resolveCanonicalizers")
    class ResolveCanonicalizers {

        @Test
        @DisplayName("resolves a Canonicalizer subclass by FQN")
        void resolvesCanonicalizer() throws ClassNotFoundException {
            List<Class<? extends Canonicalizer>> result =
                    support.resolveCanonicalizers(new String[] {StubCanonicalizer.class.getName()}, cl);

            assertEquals(1, result.size());
            assertEquals(StubCanonicalizer.class, result.get(0));
        }

        @Test
        @DisplayName("returns empty list for empty input")
        void emptyInput() throws ClassNotFoundException {
            List<Class<? extends Canonicalizer>> result = support.resolveCanonicalizers(new String[0], cl);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("resolveSanitizers")
    class ResolveSanitizers {

        @Test
        @DisplayName("resolves a Sanitizer subclass by FQN")
        void resolvesSanitizer() throws ClassNotFoundException {
            List<Class<? extends Sanitizer>> result =
                    support.resolveSanitizers(new String[] {StubSanitizer.class.getName()}, cl);

            assertEquals(1, result.size());
            assertEquals(StubSanitizer.class, result.get(0));
        }

        @Test
        @DisplayName("returns empty list for empty input")
        void emptyInput() throws ClassNotFoundException {
            List<Class<? extends Sanitizer>> result = support.resolveSanitizers(new String[0], cl);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("resolveMethod")
    class ResolveMethodTest {

        @Test
        @DisplayName("resolves a known method by name and parameter FQNs")
        void resolvesKnownMethod() throws ClassNotFoundException, NoSuchMethodException {
            Method resolved = support.resolveMethod(String.class, "substring", "int", "int");

            assertNotNull(resolved);
            assertEquals("substring", resolved.getName());
            assertArrayEquals(new Class<?>[] {int.class, int.class}, resolved.getParameterTypes());
        }

        @Test
        @DisplayName("resolves no-arg method when no paramTypeFqns supplied")
        void resolvesNoArgMethod() throws ClassNotFoundException, NoSuchMethodException {
            Method resolved = support.resolveMethod(String.class, "length");

            assertNotNull(resolved);
            assertEquals("length", resolved.getName());
            assertEquals(0, resolved.getParameterCount());
        }

        @Test
        @DisplayName("propagates ClassNotFoundException for unknown parameter type")
        void propagatesClassNotFoundForParam() {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> support.resolveMethod(String.class, "substring", "dev.vertique.Missing"));
        }

        @Test
        @DisplayName("propagates NoSuchMethodException for a nonexistent method name")
        void propagatesNoSuchMethodException() {
            // Use java.lang.String (a known type) as the param type so ClassNotFoundException
            // is not thrown before NoSuchMethodException — the method simply doesn't exist.
            assertThrows(
                    NoSuchMethodException.class,
                    () -> support.resolveMethod(String.class, "noSuchMethod", "java.lang.String"));
        }
    }

    // --- Effective annotation resolution ---

    @Nested
    @DisplayName("effectiveClassAnnotations")
    class EffectiveClassAnnotations {

        @Test
        @DisplayName("returns direct annotation when present on the concrete class")
        void directAnnotation() {
            List<Annotation> annotations = support.effectiveClassAnnotations(ConcreteResource.class);

            boolean hasPath = annotations.stream().anyMatch(a -> a instanceof Path);
            assertTrue(hasPath, "Expected @Path to be in effective class annotations");
        }

        @Test
        @DisplayName("includes interface-declared annotations when concrete class has none")
        void interfaceDeclaredAnnotation() {
            List<Annotation> annotations = support.effectiveClassAnnotations(NoAnnotationImpl.class);

            boolean hasPath = annotations.stream().anyMatch(a -> a instanceof Path);
            assertTrue(
                    hasPath, "Expected @Path from interface to be in effective class annotations for NoAnnotationImpl");
        }
    }

    @Nested
    @DisplayName("effectiveMethodAnnotations")
    class EffectiveMethodAnnotations {

        @Test
        @DisplayName("includes annotations declared on the interface method")
        void interfaceMethodAnnotations() throws NoSuchMethodException {
            Method concreteMethod = NoAnnotationImpl.class.getMethod("get");
            List<Annotation> annotations = support.effectiveMethodAnnotations(concreteMethod);

            boolean hasGet = annotations.stream().anyMatch(a -> a instanceof GET);
            assertTrue(hasGet, "Expected @GET from interface method to appear in effective annotations");
        }
    }

    // --- Test fixtures ---

    /** Interface that carries JAX-RS annotations on behalf of the concrete class. */
    @Path("/items")
    interface ResourceInterface {

        /** No-arg endpoint. */
        @GET
        String get();

        /** Endpoint with a path parameter annotated on the interface. */
        @GET
        @Path("/{id}")
        String getById(@PathParam("id") String id);
    }

    /** Concrete resource with a direct {@code @Path} annotation (direct annotation case). */
    @Path("/concrete")
    static final class ConcreteResource {}

    /** Concrete resource with no direct annotations — inherits everything from the interface. */
    static final class NoAnnotationImpl implements ResourceInterface {

        @Override
        public String get() {
            return "ok";
        }

        @Override
        public String getById(String id) {
            return id;
        }
    }

    /** Stub {@link Canonicalizer} for {@link ResolveCanonicalizers}. */
    static final class StubCanonicalizer implements Canonicalizer {

        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /** Stub {@link Sanitizer} for {@link ResolveSanitizers}. */
    static final class StubSanitizer implements Sanitizer {

        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }
}
