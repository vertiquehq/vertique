// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.security.authz.Authorized;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>Default {@link SecurityPolicyResolver} that interprets Jakarta security annotations
 * ({@code @DenyAll}, {@code @PermitAll}, {@code @RolesAllowed}) and the framework's
 * {@link Authorized} annotation to produce a {@link SecurityPolicy}.
 *
 * <p>Method-level annotations completely override class-level following Jakarta EE semantics.
 * Conflicting combinations at the same level (e.g. {@code @DenyAll + @PermitAll}) are
 * detected and reported.
 *
 * <p>This implementation is transport-agnostic and shared by JAX-RS route registration and
 * WebSocket endpoint scanning to ensure consistent security annotation semantics across all
 * transport boundaries.
 *
 * <p>All methods that accept a {@link Method} and {@link Class} delegate to the
 * {@code FromAnnotations} variants after resolving merged annotation lists via
 * {@link AnnotationResolver}, so interface-declared annotations are honoured automatically.
 */
public class AnnotationSecurityPolicyResolver implements SecurityPolicyResolver {

    /**
     * Resolves a {@link SecurityPolicy} from the security annotations present on the method and
     * class. Method-level annotations completely override class-level following Jakarta EE
     * semantics: if the method declares any security annotation, class-level security annotations
     * are ignored entirely.
     *
     * <p>Callers must invoke {@link #hasConflictingAnnotations} first; this method assumes
     * no conflicting combination is present at the same declaration level.
     *
     * <p>Interface-declared annotations are included via {@link AnnotationResolver}.
     *
     * @param method        the annotated method
     * @param resourceClass the class declaring the method
     * @return the resolved security policy; never {@code null}
     */
    @Override
    public SecurityPolicy resolve(Method method, Class<?> resourceClass) {
        return resolveFromAnnotations(
                AnnotationResolver.resolveMethodAnnotations(method),
                AnnotationResolver.resolveClassAnnotations(resourceClass));
    }

    /**
     * Resolves a {@link SecurityPolicy} from pre-resolved annotation lists. This variant is
     * preferred when the caller already holds merged annotation lists to avoid redundant traversal.
     *
     * <p>Method-level annotations completely override class-level following Jakarta EE semantics:
     * if the method annotation list declares any security annotation, class-level annotations
     * are ignored entirely.
     *
     * @param methodAnnotations merged annotations from the method and its overrides in the
     *                          superclass chain and interfaces
     * @param classAnnotations  merged annotations from the class and its superclass chain and
     *                          interfaces
     * @return the resolved security policy; never {@code null}
     */
    public SecurityPolicy resolveFromAnnotations(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        // Jakarta EE override: if the method declares any security annotation, class-level is ignored
        DenyAll denyAll = findAnnotation(methodAnnotations, DenyAll.class);
        PermitAll permitAll = findAnnotation(methodAnnotations, PermitAll.class);
        RolesAllowed rolesAllowed = findAnnotation(methodAnnotations, RolesAllowed.class);
        Authorized authorized = findAnnotation(methodAnnotations, Authorized.class);

        boolean methodHasSecurityAnnotation =
                denyAll != null || permitAll != null || rolesAllowed != null || authorized != null;

        if (!methodHasSecurityAnnotation) {
            // Fall back to class-level annotations only when method has none
            denyAll = findAnnotation(classAnnotations, DenyAll.class);
            permitAll = findAnnotation(classAnnotations, PermitAll.class);
            rolesAllowed = findAnnotation(classAnnotations, RolesAllowed.class);
            authorized = findAnnotation(classAnnotations, Authorized.class);
        }

        if (denyAll != null) return new SecurityPolicy.DenyAll();
        if (permitAll != null) return new SecurityPolicy.PermitAll();
        if (authorized != null && authorized.scopes().length == 0 && rolesAllowed == null) {
            return new SecurityPolicy.AuthenticatedOnly();
        }
        if (rolesAllowed != null || authorized != null) {
            List<String> roles = rolesAllowed != null ? List.of(rolesAllowed.value()) : List.of();
            List<String> scopes =
                    (authorized != null && authorized.scopes().length > 0) ? List.of(authorized.scopes()) : List.of();
            boolean matchAll = authorized != null && authorized.scopes().length > 0 && authorized.matchAll();
            return new SecurityPolicy.Constrained(roles, scopes, matchAll);
        }
        return new SecurityPolicy.None();
    }

    /**
     * Returns {@code true} if the effective security annotations on the method are mutually
     * exclusive at the same declaration level (method-level or class-level). Jakarta EE semantics
     * say method-level annotations completely override class-level ones, so a conflict only exists
     * when the same level carries incompatible annotations simultaneously.
     *
     * <p>Conflicting combinations (checked at each level independently):
     *
     * <ul>
     *   <li>{@code @DenyAll} + {@code @PermitAll}
     *   <li>{@code @DenyAll} + {@code @RolesAllowed}
     *   <li>{@code @DenyAll} + {@code @Authorized}
     *   <li>{@code @PermitAll} + {@code @RolesAllowed}
     *   <li>{@code @PermitAll} + {@code @Authorized}
     *   <li>two {@code @RolesAllowed} annotations with different role arrays (merged across
     *       declarations of the same method or class)
     *   <li>two {@code @Authorized} annotations with different scopes or {@code matchAll}
     * </ul>
     *
     * <p>Interface-declared annotations are included via {@link AnnotationResolver}. Because that
     * merge keeps every declaration's security annotations, a {@code @DenyAll} on a super-interface
     * and a {@code @PermitAll} on an overriding default (or class override) is a conflict — nearer
     * declarations do not win.
     *
     * @param resourceClass the class declaring the method
     * @param method        the annotated method
     * @return {@code true} if conflicting annotations appear at the same declaration level
     */
    @Override
    public boolean hasConflictingAnnotations(Class<?> resourceClass, Method method) {
        return hasConflictingAnnotationsFrom(
                AnnotationResolver.resolveMethodAnnotations(method),
                AnnotationResolver.resolveClassAnnotations(resourceClass));
    }

    /**
     * Returns {@code true} if the given pre-resolved annotation lists contain mutually exclusive
     * security annotations at the same declaration level.
     *
     * <p>Method-level conflicts are checked within {@code methodAnnotations} independently of
     * class-level conflicts within {@code classAnnotations}, following Jakarta EE override semantics.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @return {@code true} if conflicting annotations appear at the same declaration level
     */
    public boolean hasConflictingAnnotationsFrom(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        // Check method-level annotations for conflicts
        if (isConflictingCombination(
                        findAnnotation(methodAnnotations, DenyAll.class) != null,
                        findAnnotation(methodAnnotations, PermitAll.class) != null,
                        findAnnotation(methodAnnotations, RolesAllowed.class) != null,
                        findAnnotation(methodAnnotations, Authorized.class) != null)
                || hasConflictingMemberValues(methodAnnotations)) {
            return true;
        }
        // Check class-level annotations for conflicts
        return isConflictingCombination(
                        findAnnotation(classAnnotations, DenyAll.class) != null,
                        findAnnotation(classAnnotations, PermitAll.class) != null,
                        findAnnotation(classAnnotations, RolesAllowed.class) != null,
                        findAnnotation(classAnnotations, Authorized.class) != null)
                || hasConflictingMemberValues(classAnnotations);
    }

    /**
     * Produces a human-readable description of the conflicting security annotations found on the
     * method or class (whichever level has the conflict).
     *
     * <p>Interface-declared annotations are included via {@link AnnotationResolver}.
     *
     * @param resourceClass the class declaring the method
     * @param method        the annotated method
     * @return a descriptive string listing the conflicting annotations and where they appear
     */
    @Override
    public String describeConflict(Class<?> resourceClass, Method method) {
        return describeConflictFrom(
                AnnotationResolver.resolveMethodAnnotations(method),
                AnnotationResolver.resolveClassAnnotations(resourceClass));
    }

    /**
     * Produces a human-readable description of conflicting security annotations from pre-resolved
     * annotation lists.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @return a descriptive string listing the conflicting annotations and where they appear
     */
    public String describeConflictFrom(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        // Identify which level has the conflict
        boolean methodConflict = isConflictingCombination(
                        findAnnotation(methodAnnotations, DenyAll.class) != null,
                        findAnnotation(methodAnnotations, PermitAll.class) != null,
                        findAnnotation(methodAnnotations, RolesAllowed.class) != null,
                        findAnnotation(methodAnnotations, Authorized.class) != null)
                || hasConflictingMemberValues(methodAnnotations);

        List<Annotation> conflicting = methodConflict ? methodAnnotations : classAnnotations;
        String level = methodConflict ? "method-level" : "class-level";
        return level + ": " + describePresentSecurity(conflicting);
    }

    /**
     * Returns {@code true} if the effective {@link RolesAllowed} annotation on the method or class
     * has an empty value array. An empty array is ambiguous — it could mean "deny all" or be a
     * copy-paste mistake. Callers should use {@link jakarta.annotation.security.DenyAll} to
     * explicitly deny all access, or provide at least one role name.
     *
     * <p>Method-level annotations take precedence over class-level ones: the class-level annotation
     * is only checked when the method carries no security annotation of its own.
     *
     * <p>Interface-declared annotations are included via {@link AnnotationResolver}.
     *
     * @param resourceClass the class declaring the method
     * @param method        the annotated method
     * @return {@code true} if {@code @RolesAllowed} is present with an empty value array at the
     *     effective declaration level
     */
    @Override
    public boolean hasEmptyRolesAllowed(Class<?> resourceClass, Method method) {
        return hasEmptyRolesAllowedFrom(
                AnnotationResolver.resolveMethodAnnotations(method),
                AnnotationResolver.resolveClassAnnotations(resourceClass));
    }

    /**
     * Returns {@code true} if the effective {@link RolesAllowed} annotation in the pre-resolved
     * annotation lists has an empty value array.
     *
     * <p>Method-level annotations take precedence over class-level ones: the class-level annotation
     * is only checked when the method list carries no security annotation of its own.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @return {@code true} if {@code @RolesAllowed} is present with an empty value array at the
     *     effective declaration level
     */
    public boolean hasEmptyRolesAllowedFrom(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        RolesAllowed methodRa = findAnnotation(methodAnnotations, RolesAllowed.class);
        if (methodRa != null) return methodRa.value().length == 0;
        boolean methodHasAny = findAnnotation(methodAnnotations, DenyAll.class) != null
                || findAnnotation(methodAnnotations, PermitAll.class) != null
                || findAnnotation(methodAnnotations, Authorized.class) != null;
        if (!methodHasAny) {
            RolesAllowed classRa = findAnnotation(classAnnotations, RolesAllowed.class);
            if (classRa != null) return classRa.value().length == 0;
        }
        return false;
    }

    /**
     * Returns {@code true} if the given combination of security annotation flags represents a
     * conflict at a single declaration level.
     *
     * @param hasDenyAll      whether {@code @DenyAll} is present
     * @param hasPermitAll    whether {@code @PermitAll} is present
     * @param hasRolesAllowed whether {@code @RolesAllowed} is present
     * @param hasAuthorized   whether {@code @Authorized} is present
     * @return {@code true} if the combination is mutually exclusive
     */
    private boolean isConflictingCombination(
            boolean hasDenyAll, boolean hasPermitAll, boolean hasRolesAllowed, boolean hasAuthorized) {
        if (hasDenyAll && (hasPermitAll || hasRolesAllowed || hasAuthorized)) return true;
        return hasPermitAll && (hasRolesAllowed || hasAuthorized);
    }

    /**
     * Returns {@code true} when the merged annotation list carries two {@code @RolesAllowed} or
     * two {@code @Authorized} instances with disagreeing member values — the fail-closed half of
     * cross-declaration security parity with codegen (issue #634).
     *
     * @param annotations merged annotations from one declaration level
     * @return {@code true} if member values conflict
     */
    private boolean hasConflictingMemberValues(List<Annotation> annotations) {
        RolesAllowed firstRoles = null;
        Authorized firstAuthorized = null;
        for (Annotation ann : annotations) {
            if (ann instanceof RolesAllowed roles) {
                if (firstRoles == null) {
                    firstRoles = roles;
                } else if (!Arrays.equals(firstRoles.value(), roles.value())) {
                    return true;
                }
            } else if (ann instanceof Authorized authorized) {
                if (firstAuthorized == null) {
                    firstAuthorized = authorized;
                } else if (!Arrays.equals(firstAuthorized.scopes(), authorized.scopes())
                        || firstAuthorized.matchAll() != authorized.matchAll()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lists the security annotations present in {@code annotations} for conflict descriptions,
     * including distinct {@code @RolesAllowed}/{@code @Authorized} member-value variants.
     *
     * @param annotations the annotation list to describe
     * @return a {@code " + "}-joined description
     */
    private String describePresentSecurity(List<Annotation> annotations) {
        List<String> present = new ArrayList<>();
        if (findAnnotation(annotations, DenyAll.class) != null) {
            present.add("@DenyAll");
        }
        if (findAnnotation(annotations, PermitAll.class) != null) {
            present.add("@PermitAll");
        }
        for (Annotation ann : annotations) {
            if (ann instanceof RolesAllowed roles) {
                String desc = "@RolesAllowed" + Arrays.toString(roles.value());
                if (!present.contains(desc)) {
                    present.add(desc);
                }
            } else if (ann instanceof Authorized authorized) {
                String desc = "@Authorized[scopes="
                        + Arrays.toString(authorized.scopes())
                        + ",matchAll="
                        + authorized.matchAll()
                        + "]";
                if (!present.contains(desc)) {
                    present.add(desc);
                }
            }
        }
        return String.join(" + ", present);
    }

    /**
     * Finds the first annotation of the given type in a pre-resolved annotation list.
     *
     * @param annotations    the annotation list to search
     * @param annotationType the annotation class to find
     * @param <A>            the annotation type
     * @return the first matching annotation, or {@code null} if absent
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private <A extends Annotation> A findAnnotation(List<Annotation> annotations, Class<A> annotationType) {
        for (Annotation ann : annotations) {
            if (annotationType.isInstance(ann)) {
                return (A) ann;
            }
        }
        return null;
    }
}
