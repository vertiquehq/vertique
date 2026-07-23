// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Builds {@link SecurityPolicy} instances from JAX-RS security annotations.
 *
 * <p>Delegates all resolution logic to {@link AnnotationSecurityPolicyResolver} so the
 * annotation semantics are shared with other transports (e.g. WebSocket). Detects annotation
 * conflicts and empty {@code @RolesAllowed} at both method and class level, following Jakarta EE
 * override semantics (method-level annotations completely override class-level).
 *
 * <p>Each reflective overload ({@code Method}/{@code Class}) has a corresponding
 * pre-resolved-list variant that accepts {@code List<Annotation>} from
 * {@link dev.vertique.core.util.AnnotationResolver}. Callers that have already resolved the
 * merged lists should prefer the list variants to avoid redundant hierarchy traversal.
 */
class SecurityPolicyBuilder {

    private final AnnotationSecurityPolicyResolver resolver = new AnnotationSecurityPolicyResolver();

    // --- Reflective overloads (Method / Class) ---

    /**
     * Builds a {@link SecurityPolicy} from the security annotations present on the method and
     * class. Method-level annotations completely override class-level following Jakarta EE
     * semantics: if the method declares any security annotation, class-level security annotations
     * are ignored entirely.
     *
     * <p>Callers must invoke {@link #hasConflictingSecurityAnnotations(Class, Method)} first;
     * this method assumes no conflicting combination is present at the same declaration level.
     *
     * @param method        the resource method
     * @param resourceClass the JAX-RS resource class
     * @return the resolved security policy
     */
    SecurityPolicy buildSecurityPolicy(Method method, Class<?> resourceClass) {
        return resolver.resolve(method, resourceClass);
    }

    /**
     * Returns {@code true} if the effective {@link jakarta.annotation.security.RolesAllowed}
     * annotation on the method or class has an empty value array.
     *
     * @param resourceClass the JAX-RS resource class
     * @param method        the resource method
     * @return {@code true} if {@code @RolesAllowed} is present with an empty value array at the
     *     effective declaration level
     */
    boolean hasEmptyRolesAllowed(Class<?> resourceClass, Method method) {
        return resolver.hasEmptyRolesAllowed(resourceClass, method);
    }

    /**
     * Returns {@code true} if the effective security annotations on the method are mutually
     * exclusive at the same declaration level.
     *
     * @param resourceClass the JAX-RS resource class
     * @param method        the resource method
     * @return {@code true} if conflicting annotations appear at the same declaration level
     */
    boolean hasConflictingSecurityAnnotations(Class<?> resourceClass, Method method) {
        return resolver.hasConflictingAnnotations(resourceClass, method);
    }

    /**
     * Produces a human-readable description of the conflicting security annotations found on the
     * method or class (whichever level has the conflict).
     *
     * @param resourceClass the JAX-RS resource class
     * @param method        the resource method
     * @return a descriptive string listing the conflicting annotations and where they appear
     */
    String describeConflict(Class<?> resourceClass, Method method) {
        return resolver.describeConflict(resourceClass, method);
    }

    // --- Pre-resolved annotation list overloads ---

    /**
     * Builds a {@link SecurityPolicy} from pre-resolved annotation lists. Preferred when the
     * caller has already resolved the merged annotation lists to avoid redundant traversal.
     *
     * <p>Callers must invoke {@link #hasConflictingSecurityAnnotations(List, List)} first; this
     * method assumes no conflicting combination is present at the same declaration level.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return the resolved security policy
     */
    SecurityPolicy buildSecurityPolicy(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        return resolver.resolveFromAnnotations(methodAnnotations, classAnnotations);
    }

    /**
     * Returns {@code true} if the given pre-resolved annotation lists contain mutually exclusive
     * security annotations at the same declaration level.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return {@code true} if conflicting annotations appear at the same declaration level
     */
    boolean hasConflictingSecurityAnnotations(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        return resolver.hasConflictingAnnotationsFrom(methodAnnotations, classAnnotations);
    }

    /**
     * Produces a human-readable description of conflicting security annotations from pre-resolved
     * annotation lists.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return a descriptive string listing the conflicting annotations and where they appear
     */
    String describeConflict(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        return resolver.describeConflictFrom(methodAnnotations, classAnnotations);
    }

    /**
     * Returns {@code true} if the effective {@link jakarta.annotation.security.RolesAllowed}
     * annotation in the pre-resolved annotation lists has an empty value array.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return {@code true} if {@code @RolesAllowed} is present with an empty value array at the
     *     effective declaration level
     */
    boolean hasEmptyRolesAllowed(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        return resolver.hasEmptyRolesAllowedFrom(methodAnnotations, classAnnotations);
    }
}
