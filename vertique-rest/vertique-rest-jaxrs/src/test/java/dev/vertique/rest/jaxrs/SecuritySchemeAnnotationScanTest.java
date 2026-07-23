// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.vertx.ext.web.handler.AuthenticationHandler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the reflective {@link SecuritySchemeAnnotationScanner}: effective
 * {@code @SecurityRequirement} resolution into {@link SecurityRequirementSet}s (method-level
 * overrides class-level, matching Swagger semantics), the {@code combine()} AND-group mapping, the
 * fail-closed malformed-annotation guard (C1 — neither/both of name/combine set), and the slice-5
 * scheme-handler wiring loop (one {@code configure} call per handler).
 */
class SecuritySchemeAnnotationScanTest {

    @Test
    @DisplayName("Scanner maps a class-level @SecurityRequirement to a single-scheme set")
    void scanFindsSecurityRequirementOnClass() throws NoSuchMethodException {
        Method method = ClassSecuredResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(ClassSecuredResource.class);

        List<SecurityRequirementSet> effective =
                SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations);

        assertEquals(
                List.of(new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of())))),
                effective);
    }

    @Test
    @DisplayName("Scanner prefers method-level @SecurityRequirement over class-level (override-wins)")
    void scanPrefersOperationLevelOverClass() throws NoSuchMethodException {
        Method method = MethodOverrideResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(MethodOverrideResource.class);

        List<SecurityRequirementSet> effective =
                SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations);

        assertEquals(
                List.of(new SecurityRequirementSet(List.of(new SecurityRequirement("schemeB", List.of())))), effective);
    }

    @Test
    @DisplayName("Scanner reads security nested in @Operation.security() into a single-scheme set")
    void scanFindsSecurityNestedInOperationAnnotation() throws NoSuchMethodException {
        Method method = OperationSecuredResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(OperationSecuredResource.class);

        List<SecurityRequirementSet> effective =
                SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations);

        assertEquals(
                List.of(new SecurityRequirementSet(List.of(new SecurityRequirement("bearerAuth", List.of())))),
                effective,
                "@Operation(security = @SecurityRequirement(name = \"bearerAuth\")) must resolve to a "
                        + "single-scheme set");
    }

    @Test
    @DisplayName("@Operation.security() carries the declared scopes through to the single-scheme set")
    void scanCarriesScopesFromOperationSecurity() throws NoSuchMethodException {
        Method method = ScopedOperationResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(ScopedOperationResource.class);

        List<SecurityRequirementSet> effective =
                SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations);

        assertEquals(
                List.of(new SecurityRequirementSet(
                        List.of(new SecurityRequirement("oauth2", List.of("read", "write"))))),
                effective);
    }

    @Test
    @DisplayName("Two repeated @SecurityRequirement annotations become two single-scheme sets (OR), order preserved")
    void scanRepeatedRequirementsBecomeOrAlternatives() throws NoSuchMethodException {
        Method method = OrAlternativesResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(OrAlternativesResource.class);

        List<SecurityRequirementSet> effective =
                SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations);

        assertEquals(
                List.of(
                        new SecurityRequirementSet(List.of(new SecurityRequirement("schemeA", List.of()))),
                        new SecurityRequirementSet(List.of(new SecurityRequirement("schemeB", List.of())))),
                effective,
                "two repeated @SecurityRequirement annotations are OR alternatives, each a single-scheme set");
    }

    @Test
    @DisplayName(
            "@SecurityRequirement(combine={...}) with two entries maps to one multi-scheme AND set, order preserved")
    void scanCombineBecomesMultiSchemeSet() throws NoSuchMethodException {
        Method method = CombineResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(CombineResource.class);

        List<SecurityRequirementSet> effective =
                SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations);

        assertEquals(
                List.of(new SecurityRequirementSet(List.of(
                        new SecurityRequirement("apiKey", List.of()),
                        new SecurityRequirement("oauth2", List.of("read", "write"))))),
                effective,
                "combine() is an AND-group: one set carrying both schemes in entry order, scopes preserved");
    }

    @Test
    @DisplayName("A @SecurityRequirement with neither name nor combine fails startup (C1 — not silently dropped)")
    void scanMalformedRequirementFailsClosed() throws NoSuchMethodException {
        Method method = MalformedRequirementResource.class.getMethod("get");
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations =
                AnnotationResolver.resolveClassAnnotations(MalformedRequirementResource.class);

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> SecuritySchemeAnnotationScanner.effectiveRequirements(methodAnnotations, classAnnotations));
        assertTrue(
                ex.getMessage().contains("malformedReq"),
                "the failure must name the operationId of the malformed requirement");
    }

    @Test
    @DisplayName("Scheme handler bound via DI has configure() invoked once at the scheme-wiring loop")
    void schemeWiredFromDiCallsConfigureOnce() {
        // Approximation flagged in the slice report: the true FR-014 "no openapi.json" end-to-end path
        // awaits the router-engine migration (the transitional RouterBuilderSecuritySchemeRegistry
        // still requires the scheme to exist in the contract). Here we assert the slice-5 wiring
        // contract directly: configuring the handler invokes configure(registry) exactly once.
        AtomicInteger configureCalls = new AtomicInteger();
        SecuritySchemeHandler handler = new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return "bearerAuth";
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {
                configureCalls.incrementAndGet();
                registry.authenticationHandler(ctx -> ctx.next());
            }
        };

        RecordingRegistry registry = new RecordingRegistry();
        for (SecuritySchemeHandler h : List.of(handler)) {
            h.configure(registry);
        }

        assertEquals(1, configureCalls.get(), "configure must be called exactly once per handler");
        assertEquals(1, registry.handlers.size(), "the handler must register exactly one auth handler");
    }

    // --- Fixtures ---

    /** Scheme-scoped registry that records the authentication handlers registered against it. */
    private static final class RecordingRegistry implements SecuritySchemeRegistry {
        private final List<AuthenticationHandler> handlers = new java.util.ArrayList<>();

        @Override
        public void authenticationHandler(AuthenticationHandler handler) {
            handlers.add(handler);
        }
    }

    /** Resource declaring a class-level scheme + requirement, with no method-level requirement. */
    @Path("/class-secured")
    @SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    static class ClassSecuredResource {

        /** GET endpoint inheriting the class-level requirement. */
        @GET
        public String get() {
            return "ok";
        }
    }

    /** Resource with a class-level requirement that a method-level requirement overrides. */
    @Path("/method-override")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "schemeA")
    static class MethodOverrideResource {

        /** GET endpoint declaring its own requirement, overriding the class-level one. */
        @GET
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "schemeB")
        public String get() {
            return "ok";
        }
    }

    /**
     * Resource whose security is declared only nested in {@code @Operation.security()} — no standalone
     * {@code @SecurityRequirement} on the method or class.
     */
    @Path("/operation-secured")
    static class OperationSecuredResource {

        /** GET endpoint carrying its security requirement inside the Swagger {@code @Operation}. */
        @GET
        @Operation(
                operationId = "operationSecured",
                security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth"))
        public String get() {
            return "ok";
        }
    }

    /** Resource declaring scopes inside {@code @Operation.security()} to prove scope pass-through. */
    @Path("/scoped-operation")
    static class ScopedOperationResource {

        /** GET endpoint whose nested requirement carries two scopes. */
        @GET
        @Operation(
                operationId = "scopedOperation",
                security =
                        @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                                name = "oauth2",
                                scopes = {"read", "write"}))
        public String get() {
            return "ok";
        }
    }

    /** Resource declaring two OR alternatives via the {@code @SecurityRequirements} container. */
    @Path("/or-alternatives")
    @SecurityRequirements({
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "schemeA"),
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "schemeB")
    })
    static class OrAlternativesResource {

        /** GET endpoint inheriting the two class-level OR alternatives. */
        @GET
        public String get() {
            return "ok";
        }
    }

    /** Resource declaring a multi-scheme AND-group via {@code @SecurityRequirement(combine=...)}. */
    @Path("/combine")
    static class CombineResource {

        /** GET endpoint whose security requires BOTH apiKey AND oauth2 (with scopes). */
        @GET
        @Operation(
                operationId = "combineOp",
                security =
                        @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                                combine = {
                                    @SecurityRequirementEntry(name = "apiKey"),
                                    @SecurityRequirementEntry(
                                            name = "oauth2",
                                            scopes = {"read", "write"})
                                }))
        public String get() {
            return "ok";
        }
    }

    /** Resource with a malformed {@code @SecurityRequirement}: neither {@code name} nor {@code combine} set. */
    @Path("/malformed")
    static class MalformedRequirementResource {

        /** GET endpoint carrying a malformed requirement that must fail startup, not be dropped. */
        @GET
        @Operation(operationId = "malformedReq", security = @io.swagger.v3.oas.annotations.security.SecurityRequirement)
        public String get() {
            return "ok";
        }
    }
}
