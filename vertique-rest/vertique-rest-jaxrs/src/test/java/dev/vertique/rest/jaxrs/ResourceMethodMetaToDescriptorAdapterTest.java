// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResourceMethodMetaToDescriptorAdapter}: the mapping from the internal
 * {@link ResourceMethodMeta} to the public {@link JaxRsOperationDescriptor}, with focus on
 * operationId resolution.
 */
class ResourceMethodMetaToDescriptorAdapterTest {

    /** Fixture resource carrying methods annotated for operationId resolution. */
    static class FixtureResource {
        @Operation(operationId = "getUser")
        public String annotatedMethod() {
            return "x";
        }

        public String plainMethod() {
            return "y";
        }

        /**
         * Carries two OR alternatives via {@code @Operation.security}, so the scanner produces a flat
         * two-element requirement list the adapter must wrap into two single-scheme sets in order.
         */
        @Operation(
                operationId = "orSecured",
                security = {
                    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth"),
                    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                            name = "apiKey",
                            scopes = {"read"})
                })
        public String orSecuredMethod() {
            return "z";
        }
    }

    /** Resolves the method annotations declared on a fixture method into an annotation list. */
    private static List<Annotation> annotationsOf(String methodName) throws NoSuchMethodException {
        Method method = FixtureResource.class.getMethod(methodName);
        return Arrays.asList(method.getAnnotations());
    }

    /** Builds a {@link ResourceMethodMeta} for a fixture method with the supplied method annotations. */
    private static ResourceMethodMeta metaFor(
            String methodName, String operationId, String httpMethod, String path, List<Annotation> methodAnnotations)
            throws NoSuchMethodException {
        FixtureResource resource = new FixtureResource();
        Method method = FixtureResource.class.getMethod(methodName);
        return new ResourceMethodMeta(
                resource,
                method,
                operationId,
                httpMethod,
                path,
                List.of(),
                method.getReturnType(),
                false,
                false,
                new SecurityPolicy.None(),
                ResourceMethodMeta.MediaTypes.EMPTY,
                null,
                methodAnnotations,
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    @DisplayName("Adapter maps operationId from @Operation, plus httpMethod and routeTemplate")
    void resourceMethodMetaAdapterMapsOperationId() throws NoSuchMethodException {
        ResourceMethodMeta meta =
                metaFor("annotatedMethod", "getUser", "GET", "/users/{id}", annotationsOf("annotatedMethod"));

        JaxRsOperationDescriptor op = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals("getUser", op.operationId());
        assertEquals("GET", op.httpMethod());
        assertEquals("/users/{id}", op.routeTemplate());
    }

    @Test
    @DisplayName("Adapter falls back to the Java method name when no @Operation is present")
    void resourceMethodMetaAdapterFallsBackToMethodName() throws NoSuchMethodException {
        ResourceMethodMeta meta =
                metaFor("plainMethod", "plainMethod", "POST", "/things", annotationsOf("plainMethod"));

        JaxRsOperationDescriptor op = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals("plainMethod", op.operationId());
    }

    @Test
    @DisplayName("Adapter wraps a flat N-requirement OR list into N single-scheme sets, in order (behavior-preserving)")
    void resourceMethodMetaAdapterWrapsRequirementsIntoSingleSchemeSets() throws NoSuchMethodException {
        ResourceMethodMeta meta =
                metaFor("orSecuredMethod", "orSecured", "GET", "/or-secured", annotationsOf("orSecuredMethod"));

        JaxRsOperationDescriptor op = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        // The scanner yields a flat OR list [bearerAuth, apiKey(read)]; the adapter must wrap each into
        // its own single-scheme set, preserving order — so the OR list of single-scheme sets mirrors the
        // flat OR list exactly.
        assertEquals(
                List.of(
                        new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of()))),
                        new SecurityRequirementSet(List.of(new SecurityRequirement("apiKey", List.of("read"))))),
                op.securityRequirementSets());
    }
}
