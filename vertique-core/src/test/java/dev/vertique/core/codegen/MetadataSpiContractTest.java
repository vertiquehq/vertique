// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the metadata SPI ({@link MethodMetadata} / {@link ParameterMetadata}) in
 * {@code dev.vertique.core.codegen}.
 *
 * <p>This proves the SPI surface compiles to the frozen shape declared in PRD-CODEGEN-013 Appendix
 * A.2: hand-written in-test stubs implement both interfaces, and the test asserts that every
 * frozen accessor is callable and returns the stubbed value. The accessors fall into two groups:
 * the constant-only <em>reflection-free core</em> ({@code name}, {@code declaringType},
 * {@code returnType}, {@code parameterTypes}, {@code parameters}, {@code findAnnotation},
 * {@code hasAnnotation}; and on {@link ParameterMetadata} {@code index}, {@code name}, {@code type})
 * and the opt-in <em>reflective-accessor group</em> ({@code genericReturnType} / {@code asMethod}
 * on {@link MethodMetadata}; {@code genericType} on {@link ParameterMetadata}). The reflective group
 * must be present on the interface — its presence is part of the frozen surface (OQ-4 resolved).
 */
class MetadataSpiContractTest {

    @Nested
    @DisplayName("MethodMetadata")
    class MethodMetadataContract {

        @Test
        @DisplayName("exposes the frozen constant-core and reflective-accessor groups")
        void methodMetadataExposesConstantCoreAccessors() throws Exception {
            ParameterMetadata param = new StubParameterMetadata();
            Method sampleMethod = StubMethodMetadata.class.getDeclaredMethod("greet", String.class);
            MethodMetadata metadata = new StubMethodMetadata(param, sampleMethod);

            // --- constant-only reflection-free core ---
            assertEquals("greet", metadata.name());
            assertSame(StubMethodMetadata.class, metadata.declaringType());
            assertSame(String.class, metadata.returnType());
            assertEquals(1, metadata.parameterTypes().length);
            assertSame(String.class, metadata.parameterTypes()[0]);
            assertEquals(List.of(param), metadata.parameters());

            Optional<Marker> found = metadata.findAnnotation(Marker.class);
            assertTrue(found.isPresent());
            assertTrue(metadata.hasAnnotation(Marker.class));
            assertFalse(metadata.hasAnnotation(Override.class));

            // --- reflective-accessor group (opt-in; must be present on the interface) ---
            assertSame(String.class, metadata.genericReturnType());
            assertSame(sampleMethod, metadata.asMethod());
        }
    }

    @Nested
    @DisplayName("ParameterMetadata")
    class ParameterMetadataContract {

        @Test
        @DisplayName("exposes the frozen constant-core and reflective-accessor groups")
        void parameterMetadataExposesConstantCoreAccessors() {
            ParameterMetadata metadata = new StubParameterMetadata();

            // --- constant-only reflection-free core ---
            assertEquals(0, metadata.index());
            assertEquals("name", metadata.name());
            assertSame(String.class, metadata.type());

            Optional<Marker> found = metadata.findAnnotation(Marker.class);
            assertTrue(found.isPresent());
            assertTrue(metadata.hasAnnotation(Marker.class));
            assertFalse(metadata.hasAnnotation(Override.class));

            // --- reflective-accessor group (opt-in; must be present on the interface) ---
            assertSame(String.class, metadata.genericType());
        }
    }

    /** Marker annotation used to exercise {@code findAnnotation}/{@code hasAnnotation}. */
    @interface Marker {}

    /** Stub {@link Marker} instance returned by the metadata stubs. */
    private static final Marker MARKER = new Marker() {
        @Override
        public Class<? extends Annotation> annotationType() {
            return Marker.class;
        }
    };

    /**
     * Hand-written stub implementing the full frozen {@link MethodMetadata} surface so the test
     * proves the interface compiles to that shape.
     */
    private static final class StubMethodMetadata implements MethodMetadata {

        private final ParameterMetadata parameter;
        private final Method method;

        StubMethodMetadata(ParameterMetadata parameter, Method method) {
            this.parameter = parameter;
            this.method = method;
        }

        /** Sample method whose reflective {@link Method} token backs {@link #asMethod()}. */
        @SuppressWarnings("unused")
        String greet(String name) {
            return name;
        }

        @Override
        public String name() {
            return "greet";
        }

        @Override
        public Class<?> declaringType() {
            return StubMethodMetadata.class;
        }

        @Override
        public Class<?> returnType() {
            return String.class;
        }

        @Override
        public Class<?>[] parameterTypes() {
            return new Class<?>[] {String.class};
        }

        @Override
        public List<ParameterMetadata> parameters() {
            return List.of(parameter);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return type == Marker.class ? Optional.of((A) MARKER) : Optional.empty();
        }

        @Override
        public boolean hasAnnotation(Class<? extends Annotation> type) {
            return type == Marker.class;
        }

        @Override
        public Type genericReturnType() {
            return String.class;
        }

        @Override
        public Method asMethod() {
            return method;
        }
    }

    /**
     * Hand-written stub implementing the full frozen {@link ParameterMetadata} surface so the test
     * proves the interface compiles to that shape.
     */
    private static final class StubParameterMetadata implements ParameterMetadata {

        @Override
        public int index() {
            return 0;
        }

        @Override
        public String name() {
            return "name";
        }

        @Override
        public Class<?> type() {
            return String.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return type == Marker.class ? Optional.of((A) MARKER) : Optional.empty();
        }

        @Override
        public boolean hasAnnotation(Class<? extends Annotation> type) {
            return type == Marker.class;
        }

        @Override
        public Type genericType() {
            return String.class;
        }
    }
}
