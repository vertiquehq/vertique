// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.core.util.AnnotationResolver;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Resolves a {@link SecurityPolicy} from security annotations on a method and its declaring class.
 *
 * <p>Implementations inspect standard Jakarta security annotations ({@code @DenyAll},
 * {@code @PermitAll}, {@code @RolesAllowed}) and framework annotations ({@link Authorized})
 * to produce a typed {@link SecurityPolicy} value. Method-level annotations override class-level
 * following Jakarta EE semantics.
 *
 * <p>Used by both JAX-RS route registration and WebSocket endpoint scanning to apply
 * consistent security annotation interpretation across transport boundaries.
 *
 * <p>Each reflective overload ({@code Method}/{@code Class}) has a corresponding
 * {@code FromAnnotations} variant that accepts pre-resolved annotation lists. Callers that have
 * already resolved the merged lists via {@link AnnotationResolver} should prefer the
 * {@code FromAnnotations} variants to avoid redundant traversal. Default implementations delegate
 * to the reflective overloads so existing external implementations remain binary-compatible.
 */
public interface SecurityPolicyResolver {

    /**
     * Resolves a {@link SecurityPolicy} from the annotations present on {@code method} and
     * {@code resourceClass}. Method-level annotations completely override class-level following
     * Jakarta EE semantics.
     *
     * @param method        the annotated method
     * @param resourceClass the class declaring the method
     * @return the resolved security policy; never {@code null}
     */
    SecurityPolicy resolve(Method method, Class<?> resourceClass);

    /**
     * Resolves a {@link SecurityPolicy} from pre-resolved annotation lists. This variant is
     * preferred when the caller already holds merged annotation lists to avoid redundant traversal.
     *
     * <p>The default implementation resolves a fresh {@link Method} reference from
     * {@code resourceClass} using parameter-type matching and delegates to
     * {@link #resolve(Method, Class)}, which may be less efficient than a direct implementation.
     * Implementations that can resolve from annotation lists without reflection should override
     * this method.
     *
     * @param methodAnnotations merged annotations from the method and its overrides in the
     *                          superclass chain and interfaces
     * @param classAnnotations  merged annotations from the class and its superclass chain and
     *                          interfaces
     * @return the resolved security policy; never {@code null}
     */
    default SecurityPolicy resolveFromAnnotations(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        // Default: delegate to resolve() by calling the AnnotationSecurityPolicyResolver directly.
        // Concrete impls should override for efficiency.
        return new AnnotationSecurityPolicyResolver().resolveFromAnnotations(methodAnnotations, classAnnotations);
    }

    /**
     * Returns {@code true} if the method or its declaring class carries mutually exclusive security
     * annotations at the same declaration level.
     *
     * @param resourceClass the class declaring the method
     * @param method        the annotated method
     * @return {@code true} if conflicting annotations appear at the same declaration level
     */
    boolean hasConflictingAnnotations(Class<?> resourceClass, Method method);

    /**
     * Returns {@code true} if the given pre-resolved annotation lists contain mutually exclusive
     * security annotations at the same declaration level.
     *
     * <p>The default implementation delegates to a fresh {@link AnnotationSecurityPolicyResolver}.
     * Implementations should override this method for efficiency.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @return {@code true} if conflicting annotations appear at the same declaration level
     */
    default boolean hasConflictingAnnotationsFrom(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        return new AnnotationSecurityPolicyResolver()
                .hasConflictingAnnotationsFrom(methodAnnotations, classAnnotations);
    }

    /**
     * Produces a human-readable description of the conflicting security annotations found on the
     * method or class (whichever level has the conflict).
     *
     * @param resourceClass the class declaring the method
     * @param method        the annotated method
     * @return a descriptive string listing the conflicting annotations and where they appear
     */
    String describeConflict(Class<?> resourceClass, Method method);

    /**
     * Produces a human-readable description of conflicting security annotations from pre-resolved
     * annotation lists.
     *
     * <p>The default implementation delegates to a fresh {@link AnnotationSecurityPolicyResolver}.
     * Implementations should override this method for efficiency.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @return a descriptive string listing the conflicting annotations and where they appear
     */
    default String describeConflictFrom(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        return new AnnotationSecurityPolicyResolver().describeConflictFrom(methodAnnotations, classAnnotations);
    }

    /**
     * Returns {@code true} if the effective {@link jakarta.annotation.security.RolesAllowed}
     * annotation on the method or class has an empty value array.
     *
     * @param resourceClass the class declaring the method
     * @param method        the annotated method
     * @return {@code true} if {@code @RolesAllowed} is present with an empty value array at the
     *     effective declaration level
     */
    boolean hasEmptyRolesAllowed(Class<?> resourceClass, Method method);

    /**
     * Returns {@code true} if the effective {@link jakarta.annotation.security.RolesAllowed}
     * annotation in the pre-resolved annotation lists has an empty value array.
     *
     * <p>The default implementation delegates to a fresh {@link AnnotationSecurityPolicyResolver}.
     * Implementations should override this method for efficiency.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @return {@code true} if {@code @RolesAllowed} is present with an empty value array at the
     *     effective declaration level
     */
    default boolean hasEmptyRolesAllowedFrom(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        return new AnnotationSecurityPolicyResolver().hasEmptyRolesAllowedFrom(methodAnnotations, classAnnotations);
    }
}
