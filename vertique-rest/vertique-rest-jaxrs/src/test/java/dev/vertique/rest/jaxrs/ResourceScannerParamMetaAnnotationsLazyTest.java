// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 3.1 RED test proving the runtime {@link ResourceScanner} path builds a
 * {@link ResourceMethodMeta.ParamMeta} whose composed {@link dev.vertique.core.codegen.ParameterMetadata}
 * view supplies the parameter's annotations through the {@code annotationsLazy()} bridge, backed by the
 * reflective {@code Annotation[]} the scanner captures (the scanner path stays reflective by nature).
 *
 * <p><strong>Expected RED behavior:</strong> <em>compile-RED</em> today — {@code ParamMeta} has no
 * {@code annotationsLazy()} accessor. After the slice-3.1 migration, the scanner-built {@code ParamMeta}
 * exposes the merged parameter annotations via {@code annotationsLazy().get()}.
 */
class ResourceScannerParamMetaAnnotationsLazyTest {

    /** Stub canonicalizer referenced by the parameter-level {@code @Canonicalize} marker. */
    static final class CanonA implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /** Resource fixture whose query parameter carries a {@code @Canonicalize} policy annotation. */
    @Path("/scan-lazy")
    @PermitAll
    static class ScanLazyResource {
        @GET
        public String search(@QueryParam("q") @Canonicalize(CanonA.class) String q) {
            return q;
        }
    }

    @Test
    @DisplayName("scanner-built ParamMeta exposes parameter annotations via annotationsLazy(), backed by reflection")
    void scannerParamMetaAnnotationsLazyBackedByReflection() {
        List<ResourceMethodMeta> metas =
                new ResourceScanner(new SecurityPolicyBuilder()).scanResource(new ScanLazyResource());
        assertEquals(1, metas.size(), "Expected exactly one scanned method");
        ResourceMethodMeta.ParamMeta pm = metas.get(0).params().get(0);

        Annotation[] lazy = pm.annotationsLazy().get();
        assertNotNull(lazy, "Scanner-built ParamMeta must supply a non-null annotation array via annotationsLazy()");

        boolean hasCanonicalize = false;
        boolean hasQueryParam = false;
        for (Annotation a : lazy) {
            if (a instanceof Canonicalize) hasCanonicalize = true;
            if (a instanceof QueryParam) hasQueryParam = true;
        }
        assertTrue(hasCanonicalize, "annotationsLazy() exposes the reflectively-captured @Canonicalize annotation");
        assertTrue(hasQueryParam, "annotationsLazy() exposes the reflectively-captured @QueryParam annotation");
    }

    /**
     * GitHub issue #162 slice 5 RED test: the scanner-built {@code ParamMeta}'s composed
     * {@link dev.vertique.core.codegen.ParameterMetadata} view must be
     * {@code dev.vertique.core.codegen.ReflectiveParameterMetadata} (the shared reflective impl
     * rest-client's {@code ClientInterfaceScanner} already uses), not the jaxrs-local duplicate
     * {@code dev.vertique.rest.jaxrs.ReflectiveParameterMetadata} — which this slice deletes.
     */
    @Test
    @DisplayName(
            "scanner-built ParamMeta composes the core.codegen ReflectiveParameterMetadata, not a jaxrs-local duplicate")
    void scanResource_paramMetaBackedByCoreReflectiveParameterMetadata() {
        List<ResourceMethodMeta> metas =
                new ResourceScanner(new SecurityPolicyBuilder()).scanResource(new ScanLazyResource());
        ResourceMethodMeta.ParamMeta pm = metas.get(0).params().get(0);

        assertInstanceOf(
                dev.vertique.core.codegen.ReflectiveParameterMetadata.class,
                pm.parameterMetadata(),
                "ResourceScanner's ParamMeta construction must compose the core.codegen "
                        + "ReflectiveParameterMetadata");
    }
}
