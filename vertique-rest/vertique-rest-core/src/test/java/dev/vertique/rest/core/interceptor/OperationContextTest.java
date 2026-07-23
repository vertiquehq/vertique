// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.interceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.vertx.ext.web.RoutingContext;
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
 * Unit tests for {@link OperationContext}.
 *
 * <p>Verifies equals/hashCode correctness, copy-on-write semantics of
 * {@link OperationContext#withAttribute}, annotation lookup for present and absent annotations,
 * null-safety in the compact constructor, and that the attributes map is unmodifiable after
 * construction.
 */
class OperationContextTest {

    // --- Annotation fixtures ---

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface TestMethodAnno {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface TestClassAnno {}

    // --- Factory helpers ---

    private static RoutingContext mockRc() {
        return mock(RoutingContext.class);
    }

    private static OperationContext ctx(RoutingContext rc) {
        return new OperationContext("op1", rc, List.of(), List.of(), Map.of());
    }

    private static OperationContext ctx() {
        return ctx(mockRc());
    }

    // --- Tests ---

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("two contexts with the same operationId, routingContext, and maps are equal")
        void equalForSameValues() {
            RoutingContext rc = mockRc();
            OperationContext a = ctx(rc);
            OperationContext b = ctx(rc);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("contexts differ when operationId differs")
        void notEqualForDifferentOperationId() {
            RoutingContext rc = mockRc();
            OperationContext a = new OperationContext("op1", rc, List.of(), List.of(), Map.of());
            OperationContext b = new OperationContext("op2", rc, List.of(), List.of(), Map.of());
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("withAttribute")
    class WithAttribute {

        @Test
        @DisplayName("returns a new instance containing the added attribute")
        void addsAttribute() {
            OperationContext original = ctx();
            OperationContext updated = original.withAttribute("key", "value");

            assertEquals("value", updated.attributes().get("key"));
        }

        @Test
        @DisplayName("leaves the original instance unchanged")
        void doesNotMutateOriginal() {
            OperationContext original = ctx();
            original.withAttribute("key", "value");

            assertFalse(original.attributes().containsKey("key"), "Original must not be mutated");
        }

        @Test
        @DisplayName("preserves the operationId and routingContext in the copy")
        void preservesFields() {
            RoutingContext rc = mockRc();
            OperationContext original = new OperationContext("myOp", rc, List.of(), List.of(), Map.of());
            OperationContext updated = original.withAttribute("k", "v");

            assertEquals("myOp", updated.operationId());
            assertSame(rc, updated.routingContext());
        }

        @Test
        @DisplayName("accumulates multiple attributes across calls")
        void accumulatesAttributes() {
            OperationContext ctx = ctx().withAttribute("a", 1).withAttribute("b", 2);

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
            Annotation anno = AnnotationFixtures.class
                    .getDeclaredMethod("annotatedMethod")
                    .getAnnotation(TestMethodAnno.class);
            assertNotNull(anno, "Fixture annotation must not be null");

            OperationContext ctx = new OperationContext("op", mockRc(), List.of(anno), List.of(), Map.of());

            TestMethodAnno found = ctx.methodAnnotation(TestMethodAnno.class);
            assertNotNull(found);
        }

        @Test
        @DisplayName("returns null when annotation is not in methodAnnotations")
        void returnsNullWhenAbsent() {
            assertNull(ctx().methodAnnotation(TestMethodAnno.class));
        }
    }

    @Nested
    @DisplayName("classAnnotation")
    class ClassAnnotationLookup {

        @Test
        @DisplayName("returns the annotation when present in classAnnotations")
        void findsPresent() {
            Annotation anno = AnnotationFixtures.class.getAnnotation(TestClassAnno.class);
            assertNotNull(anno, "Fixture annotation must not be null");

            OperationContext ctx = new OperationContext("op", mockRc(), List.of(), List.of(anno), Map.of());

            TestClassAnno found = ctx.classAnnotation(TestClassAnno.class);
            assertNotNull(found);
        }

        @Test
        @DisplayName("returns null when annotation is not in classAnnotations")
        void returnsNullWhenAbsent() {
            assertNull(ctx().classAnnotation(TestClassAnno.class));
        }
    }

    @Nested
    @DisplayName("compact constructor null safety")
    class NullSafety {

        @Test
        @DisplayName("null methodAnnotations is coerced to an empty list")
        void nullMethodAnnotationsBecomesEmptyList() {
            OperationContext ctx = new OperationContext("op", mockRc(), null, null, null);

            assertNotNull(ctx.methodAnnotations());
            assertTrue(ctx.methodAnnotations().isEmpty());
        }

        @Test
        @DisplayName("null classAnnotations is coerced to an empty list")
        void nullClassAnnotationsBecomesEmptyList() {
            OperationContext ctx = new OperationContext("op", mockRc(), null, null, null);

            assertNotNull(ctx.classAnnotations());
            assertTrue(ctx.classAnnotations().isEmpty());
        }

        @Test
        @DisplayName("null attributes is coerced to an empty map")
        void nullAttributesBecomesEmptyMap() {
            OperationContext ctx = new OperationContext("op", mockRc(), null, null, null);

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
            OperationContext ctx = ctx();
            assertThrows(
                    UnsupportedOperationException.class, () -> ctx.attributes().put("k", "v"));
        }

        @Test
        @DisplayName("attributes from withAttribute call are also unmodifiable")
        void withAttributeMapIsUnmodifiable() {
            OperationContext ctx = ctx().withAttribute("x", 1);
            assertThrows(
                    UnsupportedOperationException.class, () -> ctx.attributes().put("k", "v"));
        }
    }

    // --- Annotation fixture helpers ---

    @TestClassAnno
    static class AnnotationFixtures {

        @TestMethodAnno
        void annotatedMethod() {}
    }
}
