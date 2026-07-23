// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.eventbus.DispatchEnvelope;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDispatchContext}.
 *
 * <p>Verifies equals/hashCode correctness, copy-on-write semantics of
 * {@link ServiceDispatchContext#withAttribute}, annotation lookup for present and absent
 * annotations, null-safety in the compact constructor, and that the attributes map is
 * unmodifiable after construction.
 */
class ServiceDispatchContextTest {

    // --- Annotation fixtures ---

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface TestMethod {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface TestClass {}

    // --- Factory helper ---

    /** Shared envelope instance used in equality tests — DispatchEnvelope uses identity equality. */
    private static final DispatchEnvelope<String> SHARED_BODY = DispatchEnvelope.of("payload");

    private static ServiceDispatchContext ctx() {
        return new ServiceDispatchContext(
                "test.svc.op", null, "test", "svc", "op", SHARED_BODY, false, List.of(), List.of(), Map.of());
    }

    // --- Tests ---

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("two contexts sharing the same field values (including DispatchEnvelope instance) are equal")
        void equalForSameValues() {
            ServiceDispatchContext a = ctx();
            ServiceDispatchContext b = ctx();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("contexts differ when address differs")
        void notEqualForDifferentAddress() {
            ServiceDispatchContext a = ctx();
            ServiceDispatchContext b = new ServiceDispatchContext(
                    "different.address", null, "test", "svc", "op", SHARED_BODY, false, List.of(), List.of(), Map.of());
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("withAttribute")
    class WithAttribute {

        @Test
        @DisplayName("returns a new instance containing the added attribute")
        void addsAttribute() {
            ServiceDispatchContext original = ctx();
            ServiceDispatchContext updated = original.withAttribute("key", "value");

            assertEquals("value", updated.attributes().get("key"));
        }

        @Test
        @DisplayName("leaves the original instance unchanged")
        void doesNotMutateOriginal() {
            ServiceDispatchContext original = ctx();
            original.withAttribute("key", "value");

            assertFalse(original.attributes().containsKey("key"), "Original must not be mutated");
        }

        @Test
        @DisplayName("accumulates multiple attributes across calls")
        void accumulatesAttributes() {
            ServiceDispatchContext ctx = ctx().withAttribute("a", 1).withAttribute("b", 2);

            assertEquals(1, ctx.attributes().get("a"));
            assertEquals(2, ctx.attributes().get("b"));
        }
    }

    @Nested
    @DisplayName("methodAnnotation")
    class MethodAnnotationLookup {

        @Test
        @DisplayName("returns the annotation when present in methodAnnotations")
        void findsPresent() throws NoSuchMethodException {
            Annotation anno = ServiceDispatchContextTest.class
                    .getDeclaredMethod("annotatedHelper")
                    .getAnnotation(TestMethod.class);
            assertNotNull(anno, "Fixture annotation must not be null");

            ServiceDispatchContext ctx = new ServiceDispatchContext(
                    "a", null, "t", "n", "op", DispatchEnvelope.of("x"), false, List.of(anno), List.of(), Map.of());

            TestMethod found = ctx.methodAnnotation(TestMethod.class);
            assertNotNull(found);
        }

        @Test
        @DisplayName("returns null when annotation is not in methodAnnotations")
        void returnsNullWhenAbsent() {
            ServiceDispatchContext ctx = ctx();
            assertNull(ctx.methodAnnotation(TestMethod.class));
        }
    }

    @Nested
    @DisplayName("classAnnotation")
    class ClassAnnotationLookup {

        @Test
        @DisplayName("returns the annotation when present in classAnnotations")
        void findsPresent() {
            Annotation anno = AnnotatedClass.class.getAnnotation(TestClass.class);
            assertNotNull(anno, "Fixture annotation must not be null");

            ServiceDispatchContext ctx = new ServiceDispatchContext(
                    "a", null, "t", "n", "op", DispatchEnvelope.of("x"), false, List.of(), List.of(anno), Map.of());

            TestClass found = ctx.classAnnotation(TestClass.class);
            assertNotNull(found);
        }

        @Test
        @DisplayName("returns null when annotation is not in classAnnotations")
        void returnsNullWhenAbsent() {
            ServiceDispatchContext ctx = ctx();
            assertNull(ctx.classAnnotation(TestClass.class));
        }
    }

    @Nested
    @DisplayName("compact constructor null safety")
    class NullSafety {

        @Test
        @DisplayName("null methodAnnotations is coerced to an empty list")
        void nullMethodAnnotationsBecomesEmptyList() {
            ServiceDispatchContext ctx = new ServiceDispatchContext(
                    "a", null, "t", "n", "op", DispatchEnvelope.of("x"), false, null, null, null);

            assertNotNull(ctx.methodAnnotations());
            assertTrue(ctx.methodAnnotations().isEmpty());
        }

        @Test
        @DisplayName("null classAnnotations is coerced to an empty list")
        void nullClassAnnotationsBecomesEmptyList() {
            ServiceDispatchContext ctx = new ServiceDispatchContext(
                    "a", null, "t", "n", "op", DispatchEnvelope.of("x"), false, null, null, null);

            assertNotNull(ctx.classAnnotations());
            assertTrue(ctx.classAnnotations().isEmpty());
        }

        @Test
        @DisplayName("null attributes is coerced to an empty map")
        void nullAttributesBecomesEmptyMap() {
            ServiceDispatchContext ctx = new ServiceDispatchContext(
                    "a", null, "t", "n", "op", DispatchEnvelope.of("x"), false, null, null, null);

            assertNotNull(ctx.attributes());
            assertTrue(ctx.attributes().isEmpty());
        }
    }

    @Nested
    @DisplayName("attributes map is unmodifiable")
    class AttributesUnmodifiable {

        @Test
        @DisplayName("direct mutation of the attributes map throws UnsupportedOperationException")
        void attributesMapIsUnmodifiable() {
            ServiceDispatchContext ctx = ctx();
            assertThrows(
                    UnsupportedOperationException.class, () -> ctx.attributes().put("k", "v"));
        }

        @Test
        @DisplayName("attributes from withAttribute call are also unmodifiable")
        void withAttributeMapIsUnmodifiable() {
            ServiceDispatchContext ctx = ctx().withAttribute("x", 1);
            assertThrows(
                    UnsupportedOperationException.class, () -> ctx.attributes().put("k", "v"));
        }
    }

    // --- Annotation fixture helpers ---

    @TestMethod
    private void annotatedHelper() {}

    @TestClass
    static class AnnotatedClass {}
}
