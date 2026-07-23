// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.core.request.EffectiveInputPolicies;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Runtime-side parity test verifying that {@link EffectiveInputPolicies} derived from
 * {@link ResourceMethodMeta} by the same algorithm as
 * {@code ParameterExtractor.resolveParamPolicies} yields the expected chains for various
 * combinations of {@code @Canonicalize}, {@code @Sanitize}, {@code @SkipCanonicalization}, and
 * {@code @SkipSanitization} on resource classes and methods.
 *
 * <p>This test covers the runtime side of the parity contract. The APT side is covered by
 * {@code InputPolicyParityTest} in the {@code vertique-codegen-jaxrs} module.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>No annotations anywhere — effective policies are {@link EffectiveInputPolicies#NONE}.</li>
 *   <li>Class-level {@code @Canonicalize} only — route chain applied to param.</li>
 *   <li>Method-level {@code @Canonicalize} overrides class-level chain.</li>
 *   <li>Method-level {@code @SkipCanonicalization} — route chain opt-out, empty param chain.</li>
 *   <li>Class-level {@code @Sanitize} only — route sanitizer chain applied to param.</li>
 *   <li>Method-level {@code @SkipSanitization} — route sanitizer chain opt-out.</li>
 * </ul>
 */
class InputPolicyRuntimeParityTest {

    // --- Stub Canonicalizer / Sanitizer implementations ---

    /**
     * Stub {@link Canonicalizer} A.
     */
    static final class CanonA implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * Stub {@link Canonicalizer} B (used to verify method overrides class).
     */
    static final class CanonB implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * Stub {@link Sanitizer} A.
     */
    static final class SanitA implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }

    // --- Reflective resource fixtures ---

    /** Fixture: no sanitization annotations. */
    @Path("/no-policy")
    @PermitAll
    static class NoPolicyResource {
        @GET
        public String search(@QueryParam("q") String q) {
            return q;
        }
    }

    /** Fixture: class-level {@code @Canonicalize(CanonA.class)}. */
    @Path("/class-canon")
    @PermitAll
    @Canonicalize(CanonA.class)
    static class ClassLevelCanonicalizerResource {
        @GET
        public String search(@QueryParam("q") String q) {
            return q;
        }
    }

    /** Fixture: class-level {@code @Canonicalize(CanonA.class)} overridden by method-level
     * {@code @Canonicalize(CanonB.class)}. */
    @Path("/method-overrides")
    @PermitAll
    @Canonicalize(CanonA.class)
    static class MethodOverridesClassResource {
        @GET
        @Canonicalize(CanonB.class)
        public String search(@QueryParam("q") String q) {
            return q;
        }
    }

    /** Fixture: class-level {@code @Canonicalize(CanonA.class)} with method-level
     * {@code @SkipCanonicalization} opt-out. */
    @Path("/route-skip-canon")
    @PermitAll
    @Canonicalize(CanonA.class)
    static class RouteSkipCanonicalizerResource {
        @GET
        @SkipCanonicalization
        public String search(@QueryParam("q") String q) {
            return q;
        }
    }

    /** Fixture: class-level {@code @Sanitize(SanitA.class)}. */
    @Path("/class-sanit")
    @PermitAll
    @Sanitize(SanitA.class)
    static class ClassLevelSanitizerResource {
        @GET
        public String search(@QueryParam("q") String q) {
            return q;
        }
    }

    /** Fixture: class-level {@code @Sanitize(SanitA.class)} with method-level
     * {@code @SkipSanitization} opt-out. */
    @Path("/route-skip-sanit")
    @PermitAll
    @Sanitize(SanitA.class)
    static class RouteSkipSanitizerResource {
        @GET
        @SkipSanitization
        public String search(@QueryParam("q") String q) {
            return q;
        }
    }

    // --- Helpers ---

    /**
     * Scans a resource instance and returns the single discovered {@link ResourceMethodMeta}.
     *
     * @param resource the resource instance
     * @return the single method meta; never {@code null}
     */
    private ResourceMethodMeta scanFirst(Object resource) {
        List<ResourceMethodMeta> metas = new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource);
        assertNotNull(metas, "Scanner result must not be null");
        assertEquals(1, metas.size(), "Expected exactly one method on this fixture");
        return metas.get(0);
    }

    /**
     * Derives the effective {@link EffectiveInputPolicies} for the first parameter of the given
     * method meta using the same algorithm as {@code ParameterExtractor.resolveParamPolicies}.
     *
     * @param meta the method meta
     * @return the effective policies for the first parameter
     */
    private EffectiveInputPolicies deriveParamPolicies(ResourceMethodMeta meta) {
        List<Class<? extends Canonicalizer>> canonChain = meta.routeCanonicalizerChain();
        List<Class<? extends Sanitizer>> sanitChain = meta.routeSanitizerChain();

        ResourceMethodMeta.ParamMeta pm = meta.params().get(0);
        // Mirror production ParameterExtractor.resolveParamPolicies: source the parameter's annotation
        // array from the composed ParameterMetadata view (annotationsLazy()), then resolve each policy
        // meta-annotation-aware via AnnotationResolver.findMetaAnnotation so composed/aliased markers
        // are honored exactly as direct markers.
        List<Annotation> annList = List.of(pm.annotationsLazy().get());
        if (AnnotationResolver.findMetaAnnotation(annList, SkipCanonicalization.class) != null) {
            canonChain = List.of();
        } else {
            Canonicalize canon = AnnotationResolver.findMetaAnnotation(annList, Canonicalize.class);
            if (canon != null) canonChain = List.of(canon.value());
        }
        if (AnnotationResolver.findMetaAnnotation(annList, SkipSanitization.class) != null) {
            sanitChain = List.of();
        } else {
            Sanitize sanit = AnnotationResolver.findMetaAnnotation(annList, Sanitize.class);
            if (sanit != null) sanitChain = List.of(sanit.value());
        }
        return new EffectiveInputPolicies(canonChain, sanitChain);
    }

    // --- Tests ---

    @Nested
    @DisplayName("no annotations — effective policies are NONE")
    class NoPolicyScenario {

        @Test
        @DisplayName("runtime: no annotations → param policies == NONE")
        void noAnnotations_policiesNone() {
            ResourceMethodMeta meta = scanFirst(new NoPolicyResource());
            EffectiveInputPolicies policies = deriveParamPolicies(meta);
            assertEquals(EffectiveInputPolicies.NONE, policies, "No annotations: policies must equal NONE");
        }
    }

    @Nested
    @DisplayName("class-level @Canonicalize — applied as route chain to param")
    class ClassLevelCanonicalizerScenario {

        @Test
        @DisplayName("runtime: class-level @Canonicalize(CanonA) → canonicalizers=[CanonA]")
        void classLevelCanonicalize_canonChain() {
            ResourceMethodMeta meta = scanFirst(new ClassLevelCanonicalizerResource());
            EffectiveInputPolicies policies = deriveParamPolicies(meta);
            assertEquals(
                    List.of(CanonA.class),
                    policies.routeCanonicalizers(),
                    "Class-level @Canonicalize must produce [CanonA] in param chain");
            assertEquals(List.of(), policies.routeSanitizers(), "No @Sanitize: sanitizer chain must be empty");
        }
    }

    @Nested
    @DisplayName("method-level @Canonicalize overrides class-level")
    class MethodOverridesClassScenario {

        @Test
        @DisplayName("runtime: method @Canonicalize(CanonB) overrides class @Canonicalize(CanonA)")
        void methodOverridesClass_canonChain() {
            ResourceMethodMeta meta = scanFirst(new MethodOverridesClassResource());
            EffectiveInputPolicies policies = deriveParamPolicies(meta);
            assertEquals(
                    List.of(CanonB.class),
                    policies.routeCanonicalizers(),
                    "Method-level @Canonicalize must override class-level");
        }
    }

    @Nested
    @DisplayName("method-level @SkipCanonicalization — route chain opt-out")
    class RouteSkipCanonicalizerScenario {

        @Test
        @DisplayName("runtime: method @SkipCanonicalization → canonicalizers=[]")
        void routeSkip_emptyCanonChain() {
            ResourceMethodMeta meta = scanFirst(new RouteSkipCanonicalizerResource());
            EffectiveInputPolicies policies = deriveParamPolicies(meta);
            assertEquals(
                    List.of(),
                    policies.routeCanonicalizers(),
                    "@SkipCanonicalization on route must produce empty param canonicalizer chain");
        }
    }

    @Nested
    @DisplayName("class-level @Sanitize — applied as route chain to param")
    class ClassLevelSanitizerScenario {

        @Test
        @DisplayName("runtime: class-level @Sanitize(SanitA) → sanitizers=[SanitA]")
        void classLevelSanitize_sanitChain() {
            ResourceMethodMeta meta = scanFirst(new ClassLevelSanitizerResource());
            EffectiveInputPolicies policies = deriveParamPolicies(meta);
            assertEquals(
                    List.of(SanitA.class),
                    policies.routeSanitizers(),
                    "Class-level @Sanitize must produce [SanitA] in param sanitizer chain");
            assertEquals(
                    List.of(), policies.routeCanonicalizers(), "No @Canonicalize: canonicalizer chain must be empty");
        }
    }

    @Nested
    @DisplayName("method-level @SkipSanitization — route sanitizer chain opt-out")
    class RouteSkipSanitizerScenario {

        @Test
        @DisplayName("runtime: method @SkipSanitization → sanitizers=[]")
        void routeSkip_emptySanitChain() {
            ResourceMethodMeta meta = scanFirst(new RouteSkipSanitizerResource());
            EffectiveInputPolicies policies = deriveParamPolicies(meta);
            assertEquals(
                    List.of(),
                    policies.routeSanitizers(),
                    "@SkipSanitization on route must produce empty param sanitizer chain");
        }
    }
}
