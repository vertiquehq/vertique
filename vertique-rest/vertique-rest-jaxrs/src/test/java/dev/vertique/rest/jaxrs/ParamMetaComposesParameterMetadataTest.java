// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 3.1 RED tests proving that {@link ResourceMethodMeta.ParamMeta} composes the neutral
 * {@link dev.vertique.core.codegen.ParameterMetadata} SPI (compose-not-replace) and exposes the
 * parameter's annotations through that view instead of an eager {@code Annotation[]} field.
 *
 * <p>The intended end-state (per PRD-REST-018 slice 3.1) is:
 * <ul>
 *   <li>{@code ParamMeta} exposes a {@link ParameterMetadata} view via {@code parameterMetadata()};
 *   <li>{@code ParamMeta} delegates the reflection-free accessors
 *       ({@code name()}, {@code index()}, {@code type()}, {@code findAnnotation(Class)},
 *       {@code hasAnnotation(Class)}) and the opt-in {@code annotationsLazy()} bridge to that view;
 *   <li>the eager {@code Annotation[] annotations()} component is removed — the lazy bridge supplies
 *       the annotation array.
 * </ul>
 *
 * <p><strong>Expected RED behavior:</strong> these tests are <em>compile-RED</em> today —
 * {@code ParamMeta} has no {@code parameterMetadata()}, {@code index()}, {@code findAnnotation(...)},
 * {@code hasAnnotation(...)}, or {@code annotationsLazy()} method, and still carries the eager
 * {@code annotations()} component. They compile and pass only after the slice-3.1 migration lands.
 */
class ParamMetaComposesParameterMetadataTest {

    /** Stub canonicalizer referenced by the parameter-level {@code @Canonicalize} marker. */
    static final class CanonA implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * Resource fixture whose method parameter carries a {@code @QueryParam} binding annotation plus a
     * {@code @Canonicalize} policy annotation, so the scanner-built {@link ResourceMethodMeta.ParamMeta}
     * carries a non-empty annotation set.
     */
    static final class PolicyParamResource {
        @SuppressWarnings("unused")
        public String search(@QueryParam("q") @Canonicalize(CanonA.class) String q) {
            return q;
        }
    }

    /**
     * Resolves the single-parameter {@link ResourceMethodMeta.ParamMeta} for the fixture method by
     * scanning its parameter annotations the same way {@code ResourceScanner.resolveParams} does.
     *
     * @return the parameter metadata for {@code q}
     * @throws Exception if reflection fails
     */
    private static ResourceMethodMeta.ParamMeta scanQ() throws Exception {
        Method search = PolicyParamResource.class.getMethod("search", String.class);
        Annotation[] paramAnnotations = search.getParameterAnnotations()[0];
        return new ResourceMethodMeta.ParamMeta(
                "q", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, paramAnnotations);
    }

    @Test
    @DisplayName("ParamMeta exposes a core.codegen.ParameterMetadata view via parameterMetadata()")
    void paramMetaExposesParameterMetadataView() throws Exception {
        ResourceMethodMeta.ParamMeta pm = scanQ();

        ParameterMetadata view = pm.parameterMetadata();
        assertNotNull(view, "ParamMeta must expose a non-null ParameterMetadata view");
        assertEquals("q", view.name(), "Composed view exposes the parameter name");
        assertSame(String.class, view.type(), "Composed view exposes the raw parameter type");
    }

    @Test
    @DisplayName("findAnnotation on the composed view resolves a parameter policy annotation")
    void findAnnotationResolvesPolicyAnnotationThroughView() throws Exception {
        ResourceMethodMeta.ParamMeta pm = scanQ();

        Optional<Canonicalize> canon = pm.parameterMetadata().findAnnotation(Canonicalize.class);
        assertTrue(canon.isPresent(), "findAnnotation(@Canonicalize) must resolve the parameter policy annotation");
        assertEquals(
                List.<Class<? extends Canonicalizer>>of(CanonA.class),
                List.of(canon.get().value()),
                "Resolved @Canonicalize carries the declared canonicalizer chain");
        assertTrue(pm.parameterMetadata().hasAnnotation(Canonicalize.class), "hasAnnotation(@Canonicalize) is true");
        assertFalse(
                pm.parameterMetadata().hasAnnotation(Override.class),
                "hasAnnotation returns false for an absent annotation");
    }

    @Test
    @DisplayName("ParamMeta delegates findAnnotation/hasAnnotation to the composed view")
    void paramMetaDelegatesAnnotationLookups() throws Exception {
        ResourceMethodMeta.ParamMeta pm = scanQ();

        assertTrue(
                pm.findAnnotation(Canonicalize.class).isPresent(),
                "ParamMeta.findAnnotation delegates to the composed ParameterMetadata view");
        assertTrue(pm.hasAnnotation(Canonicalize.class), "ParamMeta.hasAnnotation delegates to the composed view");
    }

    @Test
    @DisplayName("annotationsLazy().get() returns the parameter's annotations through the bridge")
    void annotationsLazyReturnsParameterAnnotations() throws Exception {
        ResourceMethodMeta.ParamMeta pm = scanQ();

        Annotation[] lazy = pm.annotationsLazy().get();
        assertNotNull(lazy, "annotationsLazy() supplies a non-null array");
        boolean hasCanonicalize = false;
        boolean hasQueryParam = false;
        for (Annotation a : lazy) {
            if (a instanceof Canonicalize) hasCanonicalize = true;
            if (a instanceof QueryParam) hasQueryParam = true;
        }
        assertTrue(hasCanonicalize, "annotationsLazy() exposes the @Canonicalize policy annotation");
        assertTrue(hasQueryParam, "annotationsLazy() exposes the @QueryParam binding annotation");
    }
}
