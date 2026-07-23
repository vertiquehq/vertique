// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.util.AnnotationResolver;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link GeneratedJaxRsReflectiveAnnotations} — the lazy reflective
 * fallback used by codegen-generated {@code ParameterMetadata} implementations.
 *
 * <p>These tests call {@link GeneratedJaxRsReflectiveAnnotations#mergedParameterAnnotations} the
 * same way generated code does (by FQN, not by {@code .class} reference), and assert both the
 * returned annotation contents and parity with a direct
 * {@link AnnotationResolver#resolveParameterAnnotations(Method, int)} call — the contract this
 * fallback exists to preserve.
 */
class GeneratedJaxRsReflectiveAnnotationsTest {

    @Nested
    @DisplayName("mergedParameterAnnotations — happy path")
    class HappyPath {

        @Test
        @DisplayName("resolves the direct parameter annotation and matches AnnotationResolver parity")
        void resolvesDirectAnnotation() throws NoSuchMethodException {
            Annotation[] result = GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                    GeneratedJaxRsReflectiveAnnotationsTest.class,
                    ConcreteResource.class.getName(),
                    "getById",
                    new String[] {"java.lang.String"},
                    0);

            boolean hasPathParam = false;
            for (Annotation a : result) {
                if (a instanceof PathParam pathParam) {
                    hasPathParam = true;
                    assertEquals("id", pathParam.value());
                }
            }
            assertTrue(hasPathParam, "Expected @PathParam(\"id\") in the resolved annotation array");

            Method directMethod = ConcreteResource.class.getMethod("getById", String.class);
            Annotation[] expected = AnnotationResolver.resolveParameterAnnotations(directMethod, 0);
            assertArrayEquals(expected, result, "Must match AnnotationResolver.resolveParameterAnnotations parity");
        }
    }

    @Nested
    @DisplayName("mergedParameterAnnotations — merged inheritance")
    class MergedInheritance {

        @Test
        @DisplayName("merges annotations declared on the interface override with the concrete class")
        void mergesInterfaceAnnotations() throws NoSuchMethodException {
            Annotation[] result = GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                    GeneratedJaxRsReflectiveAnnotationsTest.class,
                    NoAnnotationImpl.class.getName(),
                    "getById",
                    new String[] {"java.lang.String"},
                    0);

            boolean hasPathParam = false;
            boolean hasDefaultValue = false;
            for (Annotation a : result) {
                if (a instanceof PathParam) {
                    hasPathParam = true;
                }
                if (a instanceof DefaultValue defaultValue) {
                    hasDefaultValue = true;
                    assertEquals("0", defaultValue.value());
                }
            }
            assertTrue(hasPathParam, "Expected @PathParam merged in from the interface override");
            assertTrue(hasDefaultValue, "Expected @DefaultValue merged in from the interface override");

            Method concreteMethod = NoAnnotationImpl.class.getMethod("getById", String.class);
            Annotation[] expected = AnnotationResolver.resolveParameterAnnotations(concreteMethod, 0);
            assertArrayEquals(
                    expected, result, "Must match AnnotationResolver.resolveParameterAnnotations merged parity");
        }
    }

    @Nested
    @DisplayName("mergedParameterAnnotations — array parameter types")
    class ArrayParameterTypes {

        @Test
        @DisplayName("resolves a method with String[] and int[] parameters")
        void resolvesObjectAndPrimitiveArrays() {
            Annotation[] result = GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                    GeneratedJaxRsReflectiveAnnotationsTest.class,
                    ConcreteResource.class.getName(),
                    "withArrays",
                    new String[] {"java.lang.String[]", "int[]"},
                    0);

            boolean hasQueryParam = false;
            for (Annotation a : result) {
                if (a instanceof QueryParam queryParam) {
                    hasQueryParam = true;
                    assertEquals("names", queryParam.value());
                }
            }
            assertTrue(hasQueryParam, "Expected @QueryParam(\"names\") on the String[] parameter");
        }

        @Test
        @DisplayName("resolves a method with a multi-dimensional String[][] parameter")
        void resolvesMultiDimensionalArray() {
            Annotation[] result = GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                    GeneratedJaxRsReflectiveAnnotationsTest.class,
                    ConcreteResource.class.getName(),
                    "withMatrix",
                    new String[] {"java.lang.String[][]"},
                    0);

            boolean hasQueryParam = false;
            for (Annotation a : result) {
                if (a instanceof QueryParam queryParam) {
                    hasQueryParam = true;
                    assertEquals("matrix", queryParam.value());
                }
            }
            assertTrue(hasQueryParam, "Expected @QueryParam(\"matrix\") on the String[][] parameter");
        }
    }

    @Nested
    @DisplayName("mergedParameterAnnotations — primitive parameter types")
    class PrimitiveParameterTypes {

        @Test
        @DisplayName("resolves a method with int, long, and boolean primitive parameters")
        void resolvesPrimitiveParameters() {
            Annotation[] result = GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                    GeneratedJaxRsReflectiveAnnotationsTest.class,
                    ConcreteResource.class.getName(),
                    "withPrimitives",
                    new String[] {"int", "long", "boolean"},
                    1);

            boolean hasQueryParam = false;
            for (Annotation a : result) {
                if (a instanceof QueryParam queryParam) {
                    hasQueryParam = true;
                    assertEquals("count", queryParam.value());
                }
            }
            assertTrue(hasQueryParam, "Expected @QueryParam(\"count\") on the long parameter");
        }

        @Test
        @DisplayName("resolves a method with byte, char, short, float, and double primitive parameters")
        void resolvesRemainingPrimitiveTypes() {
            Annotation[] result = GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                    GeneratedJaxRsReflectiveAnnotationsTest.class,
                    ConcreteResource.class.getName(),
                    "withRemainingPrimitives",
                    new String[] {"byte", "char", "short", "float", "double"},
                    3);

            boolean hasQueryParam = false;
            for (Annotation a : result) {
                if (a instanceof QueryParam queryParam) {
                    hasQueryParam = true;
                    assertEquals("ratio", queryParam.value());
                }
            }
            assertTrue(hasQueryParam, "Expected @QueryParam(\"ratio\") on the float parameter");
        }
    }

    @Nested
    @DisplayName("mergedParameterAnnotations — resolution failure")
    class ResolutionFailure {

        @Test
        @DisplayName("throws IllegalStateException naming the method for a non-existent method name")
        void throwsForUnknownMethodName() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                            GeneratedJaxRsReflectiveAnnotationsTest.class,
                            ConcreteResource.class.getName(),
                            "noSuchMethod",
                            new String[] {"java.lang.String"},
                            0));

            assertTrue(
                    ex.getMessage().contains("noSuchMethod"),
                    "Exception message must name the unresolvable method: " + ex.getMessage());
            assertTrue(ex.getCause() instanceof NoSuchMethodException);
        }

        @Test
        @DisplayName("throws IllegalStateException for an unresolvable declaring class FQN")
        void throwsForUnknownDeclaringClass() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                            GeneratedJaxRsReflectiveAnnotationsTest.class,
                            "dev.vertique.nonexistent.DoesNotExist",
                            "getById",
                            new String[] {"java.lang.String"},
                            0));

            assertTrue(
                    ex.getMessage().contains("getById"),
                    "Exception message must name the method even when the declaring class fails to resolve: "
                            + ex.getMessage());
            assertTrue(ex.getCause() instanceof ClassNotFoundException);
        }

        @Test
        @DisplayName("throws IllegalStateException for an unresolvable parameter type FQN")
        void throwsForUnknownParameterType() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(
                            GeneratedJaxRsReflectiveAnnotationsTest.class,
                            ConcreteResource.class.getName(),
                            "getById",
                            new String[] {"dev.vertique.nonexistent.Missing"},
                            0));

            assertTrue(ex.getCause() instanceof ClassNotFoundException);
        }
    }

    // --- Test fixtures ---

    /** Interface declaring the parameter annotations that {@link NoAnnotationImpl} inherits. */
    interface ResourceInterface {

        /**
         * Endpoint with a path parameter annotated on the interface.
         *
         * @param id the resolved id
         * @return the id
         */
        String getById(@PathParam("id") @DefaultValue("0") String id);
    }

    /** Concrete resource carrying direct parameter annotations plus array/primitive overloads. */
    static final class ConcreteResource {

        /**
         * Endpoint with a directly-annotated path parameter.
         *
         * @param id the resolved id
         * @return the id
         */
        public String getById(@PathParam("id") String id) {
            return id;
        }

        /**
         * Endpoint exercising object-array and primitive-array parameter types.
         *
         * @param names query values
         * @param counts count values
         * @return a description
         */
        public String withArrays(@QueryParam("names") String[] names, int[] counts) {
            return String.valueOf(names.length + counts.length);
        }

        /**
         * Endpoint exercising a multi-dimensional array parameter type.
         *
         * @param matrix a 2D matrix parameter
         * @return a description
         */
        public String withMatrix(@QueryParam("matrix") String[][] matrix) {
            return String.valueOf(matrix.length);
        }

        /**
         * Endpoint exercising primitive scalar parameter types.
         *
         * @param flagCount an int flag
         * @param count a long count, annotated
         * @param enabled a boolean flag
         * @return a description
         */
        public String withPrimitives(int flagCount, @QueryParam("count") long count, boolean enabled) {
            return flagCount + ":" + count + ":" + enabled;
        }

        /**
         * Endpoint exercising the remaining primitive scalar parameter types
         * ({@code byte}, {@code char}, {@code short}, {@code float}, {@code double}).
         *
         * @param b a byte value
         * @param c a char value
         * @param s a short value
         * @param ratio a float value, annotated
         * @param d a double value
         * @return a description
         */
        public String withRemainingPrimitives(byte b, char c, short s, @QueryParam("ratio") float ratio, double d) {
            return b + ":" + c + ":" + s + ":" + ratio + ":" + d;
        }
    }

    /** Concrete resource with no direct parameter annotations — inherits from the interface. */
    static final class NoAnnotationImpl implements ResourceInterface {

        @Override
        public String getById(String id) {
            return id;
        }
    }
}
