// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED tests for GitHub issue #162 slice 5: {@link ParamMeta}'s {@code Annotation[]}-based
 * convenience constructor must wrap {@code dev.vertique.core.codegen.ReflectiveParameterMetadata}
 * (an {@link java.lang.reflect.AnnotatedElement}-based reflective view already proven by
 * {@code rest-client}'s {@code ClientInterfaceScanner}), not the jaxrs-local
 * {@code ReflectiveParameterMetadata} (a pre-captured {@code Annotation[]}-based view), so the
 * jaxrs-local duplicate class can be deleted.
 *
 * <p>The public constructor signature is unchanged; only the composed
 * {@link dev.vertique.core.codegen.ParameterMetadata} implementation's concrete type changes. This
 * test proves {@code findAnnotation}/{@code hasAnnotation}/{@code annotationsLazy()} all still agree
 * with the original array, and that {@code null} is treated as "no annotations" rather than throwing.
 */
class ResourceMethodMetaParamMetaAnnotationArrayAdapterTest {

    /** Fixture method carrying two distinct annotations, used to source a real {@code Annotation[]}. */
    @QueryParam("q")
    @PathParam("p")
    private static void fixtureMethod() {}

    private static Annotation[] fixtureAnnotations() throws NoSuchMethodException {
        return ResourceMethodMetaParamMetaAnnotationArrayAdapterTest.class
                .getDeclaredMethod("fixtureMethod")
                .getAnnotations();
    }

    @Test
    @DisplayName(
            "Annotation[] convenience constructor — findAnnotation/hasAnnotation/annotationsLazy() all agree with the original array")
    void annotationArrayConstructor_findAnnotationAndAnnotationsLazyAgree() throws NoSuchMethodException {
        Annotation[] annotations = fixtureAnnotations();
        assertEquals(2, annotations.length, "Fixture method must carry exactly two annotations");

        ParamMeta pm =
                new ParamMeta("q", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, annotations);

        // findAnnotation resolves each annotation present on the original array.
        assertTrue(pm.findAnnotation(QueryParam.class).isPresent(), "findAnnotation must resolve @QueryParam");
        assertEquals("q", pm.findAnnotation(QueryParam.class).get().value());
        assertTrue(pm.findAnnotation(PathParam.class).isPresent(), "findAnnotation must resolve @PathParam");
        assertEquals("p", pm.findAnnotation(PathParam.class).get().value());

        // hasAnnotation agrees with findAnnotation.
        assertTrue(pm.hasAnnotation(QueryParam.class));
        assertTrue(pm.hasAnnotation(PathParam.class));

        // annotationsLazy() returns an array equal (unordered-safe via length + contains check) to
        // the original — both views are backed by the same underlying data.
        Annotation[] lazy = pm.annotationsLazy().get();
        assertEquals(annotations.length, lazy.length, "annotationsLazy() must report the same annotation count");
        for (Annotation a : annotations) {
            assertTrue(containsAnnotation(lazy, a), "annotationsLazy() must contain " + a);
        }
    }

    @Test
    @DisplayName(
            "Annotation[] convenience constructor — composed ParameterMetadata is core.codegen.ReflectiveParameterMetadata")
    void annotationArrayConstructor_composesCoreReflectiveParameterMetadata() throws NoSuchMethodException {
        ParamMeta pm = new ParamMeta(
                "q", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, fixtureAnnotations());

        assertInstanceOf(
                dev.vertique.core.codegen.ReflectiveParameterMetadata.class,
                pm.parameterMetadata(),
                "ParamMeta's Annotation[] convenience constructor must compose the core.codegen "
                        + "ReflectiveParameterMetadata, not a jaxrs-local duplicate");
    }

    @Test
    @DisplayName("Annotation[] convenience constructor — null array is treated as no annotations, not an error")
    void nullArray_isTreatedAsNoAnnotations() {
        ParamMeta pm = new ParamMeta(
                "q", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, (Annotation[]) null);

        assertFalse(pm.hasAnnotation(QueryParam.class), "null annotation array must report no annotations present");
        assertTrue(
                pm.findAnnotation(QueryParam.class).isEmpty(),
                "null annotation array must resolve no annotation via findAnnotation");
        Annotation[] lazy = pm.annotationsLazy().get();
        assertArrayEquals(new Annotation[0], lazy, "null annotation array must yield an empty annotationsLazy() array");
    }

    @Test
    @DisplayName("Annotation[] convenience constructor — AnnotatedElement adapter's getDeclaredAnnotations() "
            + "returns a defensive clone equal to the original array")
    void annotationArrayElementAdapter_getDeclaredAnnotationsReturnsClone() throws Exception {
        Annotation[] annotations = fixtureAnnotations();
        ParamMeta pm =
                new ParamMeta("q", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, annotations);

        AnnotatedElement adapter = annotationSourceOf(pm);

        Annotation[] declared = adapter.getDeclaredAnnotations();
        assertEquals(annotations.length, declared.length, "getDeclaredAnnotations() must report the same count");
        for (Annotation a : annotations) {
            assertTrue(containsAnnotation(declared, a), "getDeclaredAnnotations() must contain " + a);
        }
        // Defensive clone: mutating the returned array must not affect subsequent calls.
        declared[0] = null;
        Annotation[] declaredAgain = adapter.getDeclaredAnnotations();
        assertTrue(containsAnnotation(declaredAgain, annotations[0]), "clone must not leak external mutation");
    }

    @Test
    @DisplayName("ParamMeta 5-arg convenience constructor (no default value/annotations) yields the given "
            + "name/source/type/componentType/genericType with no annotations present")
    void fiveArgConvenienceConstructor_noDefaultValueOrAnnotations() {
        Type genericType = String.class;
        ParamMeta pm = new ParamMeta(
                "items", ResourceMethodMeta.ParamSource.QUERY, java.util.List.class, String.class, genericType);

        assertEquals("items", pm.name());
        assertEquals(ResourceMethodMeta.ParamSource.QUERY, pm.source());
        assertEquals(java.util.List.class, pm.type());
        assertEquals(String.class, pm.componentType());
        assertEquals(genericType, pm.genericType());
        assertEquals(null, pm.defaultValue());
        assertFalse(pm.hasAnnotation(QueryParam.class), "5-arg constructor carries no annotations");
    }

    /**
     * Reaches into {@code pm}'s composed {@link dev.vertique.core.codegen.ReflectiveParameterMetadata}
     * via reflection to retrieve the actual {@link AnnotatedElement} adapter instance created by
     * {@code ParamMeta}'s private {@code annotationArrayElement} factory — the same adapter
     * {@code findAnnotation}/{@code hasAnnotation}/{@code annotationsLazy()} delegate through, but whose
     * {@code getDeclaredAnnotations()} member is never invoked by any production call path (only
     * {@code getAnnotation}/{@code getAnnotations} are). Retrieving the real instance (rather than a
     * hand-built stand-in) proves the actual adapter's {@code getDeclaredAnnotations()} contract.
     *
     * @param pm the {@code ParamMeta} whose composed view wraps the adapter to retrieve
     * @return the underlying {@code AnnotatedElement} adapter instance
     * @throws Exception if the backing field cannot be reached
     */
    private static AnnotatedElement annotationSourceOf(ParamMeta pm) throws Exception {
        java.lang.reflect.Field field =
                dev.vertique.core.codegen.ReflectiveParameterMetadata.class.getDeclaredField("annotationSource");
        field.setAccessible(true);
        return (AnnotatedElement) field.get(pm.parameterMetadata());
    }

    private static boolean containsAnnotation(Annotation[] array, Annotation target) {
        for (Annotation a : array) {
            if (a.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
