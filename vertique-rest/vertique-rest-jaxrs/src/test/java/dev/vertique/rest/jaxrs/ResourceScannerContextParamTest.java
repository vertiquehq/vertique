// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.context.ContextValue;
import dev.vertique.security.SecurityContext;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ResourceScanner} classifies all {@code @Context}-annotated parameters
 * (and all natively injectable types) as {@link ResourceMethodMeta.ParamSource#CONTEXT}
 * (FR-REST-166/167/179 — single CONTEXT source, runtime reflection-path flip).
 *
 * <p>Specifically:
 * <ul>
 *   <li>{@code @Context RoutingContext} → CONTEXT</li>
 *   <li>{@code @Context jakarta.ws.rs.core.SecurityContext} → CONTEXT</li>
 *   <li>{@code @Context dev.vertique.security.SecurityContext} (framework) → CONTEXT</li>
 *   <li>{@code @Context Tenant} (a {@link ContextValue} record) → CONTEXT</li>
 *   <li>Unannotated {@link RoutingContext} parameter → CONTEXT (auto-injectable type)</li>
 *   <li>Unannotated {@link jakarta.ws.rs.core.SecurityContext} parameter → CONTEXT</li>
 *   <li>Unannotated framework {@link SecurityContext} parameter → CONTEXT</li>
 *   <li>Unannotated {@code Tenant} ({@link ContextValue}) parameter → CONTEXT</li>
 *   <li>Mixed method (path + query + {@code @Context} + body) — ordering preserved</li>
 * </ul>
 */
class ResourceScannerContextParamTest {

    // --- Test-fixture context value type ---

    /**
     * App-defined context value record used to verify that {@code ContextValue} subtypes are
     * classified as {@code CONTEXT} regardless of whether {@code @Context} is present.
     *
     * @param id the tenant identifier
     */
    record Tenant(String id) implements ContextValue {}

    // --- Test resource fixtures ---

    /** Resource with explicit {@code @Context} on all four injectable types. */
    @Path("/context-annotated")
    @PermitAll
    static class ContextAnnotatedResource {

        /**
         * @return greeting
         */
        @GET
        @Path("/routing")
        public String routing(@Context RoutingContext ctx) {
            return "ok";
        }

        /**
         * @return greeting
         */
        @GET
        @Path("/jaxrs-sc")
        public String jaxRsSc(@Context jakarta.ws.rs.core.SecurityContext sc) {
            return "ok";
        }

        /**
         * @return greeting
         */
        @GET
        @Path("/framework-sc")
        public String frameworkSc(@Context SecurityContext sc) {
            return "ok";
        }

        /**
         * @return greeting
         */
        @GET
        @Path("/tenant")
        public String tenant(@Context Tenant t) {
            return "ok";
        }
    }

    /** Resource with UNANNOTATED injectable types (no {@code @Context}). */
    @Path("/context-unannotated")
    @PermitAll
    static class ContextUnannotatedResource {

        /**
         * @return greeting
         */
        @GET
        @Path("/routing")
        public String routing(RoutingContext ctx) {
            return "ok";
        }

        /**
         * @return greeting
         */
        @GET
        @Path("/jaxrs-sc")
        public String jaxRsSc(jakarta.ws.rs.core.SecurityContext sc) {
            return "ok";
        }

        /**
         * @return greeting
         */
        @GET
        @Path("/framework-sc")
        public String frameworkSc(SecurityContext sc) {
            return "ok";
        }

        /**
         * @return greeting
         */
        @GET
        @Path("/tenant")
        public String tenant(Tenant t) {
            return "ok";
        }
    }

    /** Resource with a mixed method: path param + query param + {@code @Context} + body. */
    @Path("/mixed")
    @PermitAll
    static class MixedParamResource {

        /**
         * @param id    path param
         * @param q     query param
         * @param ctx   context
         * @param body  request body
         * @return result
         */
        @GET
        @Path("/{id}")
        public String mixed(
                @PathParam("id") String id, @QueryParam("q") String q, @Context RoutingContext ctx, String body) {
            return id + q;
        }
    }

    // --- Helper ---

    private ResourceScanner scanner() {
        return new ResourceScanner(new SecurityPolicyBuilder());
    }

    private List<ResourceMethodMeta> scan(Object resource) {
        return scanner().scanResource(resource);
    }

    // --- Tests ---

    @Nested
    @DisplayName("@Context-annotated parameters → always CONTEXT")
    class ContextAnnotated {

        @Test
        @DisplayName("@Context RoutingContext → ParamSource.CONTEXT")
        void contextAnnotated_routingContext() {
            List<ResourceMethodMeta> metas = scan(new ContextAnnotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "routing");
            assertNotNull(meta);
            assertEquals(1, meta.params().size());
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
            assertEquals(RoutingContext.class, meta.params().get(0).type());
        }

        @Test
        @DisplayName("@Context jakarta.ws.rs.core.SecurityContext → ParamSource.CONTEXT")
        void contextAnnotated_jaxRsSecurityContext() {
            List<ResourceMethodMeta> metas = scan(new ContextAnnotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "jaxRsSc");
            assertNotNull(meta);
            assertEquals(1, meta.params().size());
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
            assertEquals(
                    jakarta.ws.rs.core.SecurityContext.class,
                    meta.params().get(0).type());
        }

        @Test
        @DisplayName("@Context dev.vertique.security.SecurityContext → ParamSource.CONTEXT")
        void contextAnnotated_frameworkSecurityContext() {
            List<ResourceMethodMeta> metas = scan(new ContextAnnotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "frameworkSc");
            assertNotNull(meta);
            assertEquals(1, meta.params().size());
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
            assertEquals(SecurityContext.class, meta.params().get(0).type());
        }

        @Test
        @DisplayName("@Context Tenant (ContextValue record) → ParamSource.CONTEXT")
        void contextAnnotated_contextValueRecord() {
            List<ResourceMethodMeta> metas = scan(new ContextAnnotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "tenant");
            assertNotNull(meta);
            assertEquals(1, meta.params().size());
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
            assertEquals(Tenant.class, meta.params().get(0).type());
        }
    }

    @Nested
    @DisplayName("Unannotated injectable types → CONTEXT via isInjectable check")
    class ContextUnannotated {

        @Test
        @DisplayName("Unannotated RoutingContext → ParamSource.CONTEXT")
        void unannotated_routingContext() {
            List<ResourceMethodMeta> metas = scan(new ContextUnannotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "routing");
            assertNotNull(meta);
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
        }

        @Test
        @DisplayName("Unannotated jakarta.ws.rs.core.SecurityContext → ParamSource.CONTEXT")
        void unannotated_jaxRsSecurityContext() {
            List<ResourceMethodMeta> metas = scan(new ContextUnannotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "jaxRsSc");
            assertNotNull(meta);
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
        }

        @Test
        @DisplayName("Unannotated framework SecurityContext → ParamSource.CONTEXT")
        void unannotated_frameworkSecurityContext() {
            List<ResourceMethodMeta> metas = scan(new ContextUnannotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "frameworkSc");
            assertNotNull(meta);
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
        }

        @Test
        @DisplayName("Unannotated Tenant (ContextValue) → ParamSource.CONTEXT")
        void unannotated_contextValue() {
            List<ResourceMethodMeta> metas = scan(new ContextUnannotatedResource());
            ResourceMethodMeta meta = findMethod(metas, "tenant");
            assertNotNull(meta);
            assertEquals(
                    ResourceMethodMeta.ParamSource.CONTEXT, meta.params().get(0).source());
        }
    }

    @Nested
    @DisplayName("Mixed method preserves parameter ordering and classifies context correctly")
    class MixedMethod {

        @Test
        @DisplayName("path + query + @Context + body → [PATH, QUERY, CONTEXT, BODY] in order")
        void mixedMethod_paramOrdering() {
            List<ResourceMethodMeta> metas = scan(new MixedParamResource());
            assertEquals(1, metas.size());
            List<ResourceMethodMeta.ParamMeta> params = metas.get(0).params();
            assertEquals(4, params.size());
            assertEquals(ResourceMethodMeta.ParamSource.PATH, params.get(0).source());
            assertEquals(ResourceMethodMeta.ParamSource.QUERY, params.get(1).source());
            assertEquals(ResourceMethodMeta.ParamSource.CONTEXT, params.get(2).source());
            assertEquals(ResourceMethodMeta.ParamSource.BODY, params.get(3).source());
        }

        @Test
        @DisplayName("@Context param in mixed method carries the declared type")
        void mixedMethod_contextParamCarriesType() {
            List<ResourceMethodMeta> metas = scan(new MixedParamResource());
            assertEquals(1, metas.size());
            ResourceMethodMeta.ParamMeta ctxParam = metas.get(0).params().get(2);
            assertEquals(ResourceMethodMeta.ParamSource.CONTEXT, ctxParam.source());
            assertEquals(RoutingContext.class, ctxParam.type());
        }
    }

    // --- Utility ---

    /**
     * Finds the first {@link ResourceMethodMeta} whose underlying method name matches the given
     * name, or returns {@code null} if none found.
     *
     * @param metas      the list to search
     * @param methodName the method name to look for
     * @return matching meta, or {@code null}
     */
    private static ResourceMethodMeta findMethod(List<ResourceMethodMeta> metas, String methodName) {
        for (ResourceMethodMeta meta : metas) {
            if (meta.method().getName().equals(methodName)) {
                return meta;
            }
        }
        return null;
    }
}
