// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.core.util.AnnotationResolver;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for NFR-015-06: parameter-level input-policy resolution must be
 * <strong>meta-annotation-aware</strong>. {@code ParameterExtractor.resolveParamPolicies} sources the
 * parameter's annotation array from the composed
 * {@link dev.vertique.core.codegen.ParameterMetadata#annotationsLazy()} view, then resolves
 * {@code @Canonicalize}/{@code @Sanitize}/{@code @SkipCanonicalization}/{@code @SkipSanitization}
 * through {@link AnnotationResolver#findMetaAnnotation(List, Class)} — so a custom annotation that is
 * itself meta-annotated with {@code @Canonicalize} (annotation aliasing on a parameter) resolves the
 * policy exactly as a direct marker would.
 *
 * <p>These tests replicate that resolution algorithm against the scanner-built
 * {@link ResourceMethodMeta.ParamMeta} (the same {@code List.of(pm.annotationsLazy().get())} →
 * {@code findMetaAnnotation(...)} path the runtime uses) to prove the migration to the composed view
 * preserved meta-aware semantics rather than silently degrading to a direct/literal-only lookup.
 */
class ParamMetaPolicyViaFindAnnotationTest {

    /** Stub canonicalizer A. */
    static final class CanonA implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /** Stub sanitizer A. */
    static final class SanitA implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }

    // --- Composed (aliased) policy annotations: meta-annotated with the real policy markers ---

    /**
     * Composed annotation aliasing {@code @Canonicalize(CanonA.class)}. Placing this on a parameter must
     * resolve {@code @Canonicalize} through meta-annotation walking, exactly as a direct marker would.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @Canonicalize(CanonA.class)
    @interface CanonicalizedInput {}

    /**
     * Composed annotation aliasing {@code @Sanitize(SanitA.class)}. Placing this on a parameter must
     * resolve {@code @Sanitize} through meta-annotation walking, exactly as a direct marker would.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @Sanitize(SanitA.class)
    @interface SanitizedInput {}

    // --- Resource fixtures ---

    /** Parameter carrying direct {@code @Canonicalize} + {@code @Sanitize} markers. */
    static final class DirectPolicyResource {
        @SuppressWarnings("unused")
        public String search(@QueryParam("q") @Canonicalize(CanonA.class) @Sanitize(SanitA.class) String q) {
            return q;
        }
    }

    /** Parameter carrying composed/aliased policy annotations meta-annotated with the real markers. */
    static final class ComposedPolicyResource {
        @SuppressWarnings("unused")
        public String search(@QueryParam("q") @CanonicalizedInput @SanitizedInput String q) {
            return q;
        }
    }

    /** Parameter opting out of canonicalization via direct {@code @SkipCanonicalization}. */
    static final class SkipCanonResource {
        @SuppressWarnings("unused")
        public String search(@QueryParam("q") @SkipCanonicalization String q) {
            return q;
        }
    }

    /** Parameter opting out of sanitization via direct {@code @SkipSanitization}. */
    static final class SkipSanitResource {
        @SuppressWarnings("unused")
        public String search(@QueryParam("q") @SkipSanitization String q) {
            return q;
        }
    }

    /**
     * Builds the single-parameter {@link ResourceMethodMeta.ParamMeta} for {@code resourceType}'s
     * {@code search(String)} method, carrying that parameter's reflective annotation array.
     *
     * @param resourceType the resource fixture class
     * @return the parameter metadata for {@code q}
     * @throws Exception if reflection fails
     */
    private static ResourceMethodMeta.ParamMeta scanQ(Class<?> resourceType) throws Exception {
        Method search = resourceType.getMethod("search", String.class);
        Annotation[] paramAnnotations = search.getParameterAnnotations()[0];
        return new ResourceMethodMeta.ParamMeta(
                "q", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, paramAnnotations);
    }

    /**
     * Resolves the parameter's annotation array exactly as {@code resolveParamPolicies} does: source it
     * from the composed view via {@code annotationsLazy()}, then look up the policy annotation
     * meta-annotation-aware.
     *
     * @param pm             the parameter metadata under test
     * @param annotationType the policy annotation type to resolve
     * @param <A>            the policy annotation type
     * @return the resolved (possibly composed) annotation, or {@code null} when absent
     */
    private static <A extends Annotation> A resolve(ResourceMethodMeta.ParamMeta pm, Class<A> annotationType) {
        List<Annotation> annList = List.of(pm.annotationsLazy().get());
        return AnnotationResolver.findMetaAnnotation(annList, annotationType);
    }

    @Nested
    @DisplayName("policy annotations resolve meta-annotation-aware from the composed view")
    class PolicyResolution {

        @Test
        @DisplayName("direct @Canonicalize resolves, supplying its canonicalizer chain")
        void directCanonicalizeResolves() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(DirectPolicyResource.class);

            Canonicalize canon = resolve(pm, Canonicalize.class);
            assertNotNull(canon, "direct @Canonicalize must resolve through the meta-aware lookup");
            assertEquals(
                    List.<Class<? extends Canonicalizer>>of(CanonA.class),
                    List.of(canon.value()),
                    "Resolved @Canonicalize supplies the declared canonicalizer chain");
        }

        @Test
        @DisplayName("direct @Sanitize resolves, supplying its sanitizer chain")
        void directSanitizeResolves() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(DirectPolicyResource.class);

            Sanitize sanit = resolve(pm, Sanitize.class);
            assertNotNull(sanit, "direct @Sanitize must resolve through the meta-aware lookup");
            assertEquals(
                    List.<Class<? extends Sanitizer>>of(SanitA.class),
                    List.of(sanit.value()),
                    "Resolved @Sanitize supplies the declared sanitizer chain");
        }

        @Test
        @DisplayName("composed @Canonicalize (annotation aliasing on a parameter) resolves the policy")
        void composedCanonicalizeResolvesViaMetaAnnotation() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(ComposedPolicyResource.class);

            // The parameter carries @CanonicalizedInput, NOT @Canonicalize directly. A direct/literal-only
            // lookup would miss it; the meta-aware lookup walks the composed annotation and finds it.
            Canonicalize canon = resolve(pm, Canonicalize.class);
            assertNotNull(
                    canon, "@Canonicalize composed via a custom @CanonicalizedInput alias must resolve (NFR-015-06)");
            assertEquals(
                    List.<Class<? extends Canonicalizer>>of(CanonA.class),
                    List.of(canon.value()),
                    "Composed @Canonicalize supplies the meta-annotated canonicalizer chain");
        }

        @Test
        @DisplayName("composed @Sanitize (annotation aliasing on a parameter) resolves the policy")
        void composedSanitizeResolvesViaMetaAnnotation() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(ComposedPolicyResource.class);

            Sanitize sanit = resolve(pm, Sanitize.class);
            assertNotNull(sanit, "@Sanitize composed via a custom @SanitizedInput alias must resolve (NFR-015-06)");
            assertEquals(
                    List.<Class<? extends Sanitizer>>of(SanitA.class),
                    List.of(sanit.value()),
                    "Composed @Sanitize supplies the meta-annotated sanitizer chain");
        }

        @Test
        @DisplayName("@SkipCanonicalization clears the canonicalization chain")
        void skipCanonicalizationResolves() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(SkipCanonResource.class);

            assertNotNull(
                    resolve(pm, SkipCanonicalization.class),
                    "@SkipCanonicalization must resolve so the canonicalization chain is cleared");
        }

        @Test
        @DisplayName("@SkipSanitization clears the sanitization chain")
        void skipSanitizationResolves() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(SkipSanitResource.class);

            assertNotNull(
                    resolve(pm, SkipSanitization.class),
                    "@SkipSanitization must resolve so the sanitization chain is cleared");
        }

        @Test
        @DisplayName("absent policy annotations resolve to null (chain stays at route baseline)")
        void absentPoliciesResolveToNull() throws Exception {
            ResourceMethodMeta.ParamMeta pm = scanQ(SkipCanonResource.class);

            assertNull(resolve(pm, Canonicalize.class), "no @Canonicalize present → null");
            assertNull(resolve(pm, Sanitize.class), "no @Sanitize present → null");
            assertNull(resolve(pm, SkipSanitization.class), "no @SkipSanitization present → null");
        }
    }
}
