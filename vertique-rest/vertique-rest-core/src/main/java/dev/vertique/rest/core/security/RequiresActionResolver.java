// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.RequiresAction;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the {@link RequiresAction} annotation declared on a JAX-RS resource method or class into
 * a canonical {@link ActionRef}, applying Jakarta override semantics: a method-level
 * {@code @RequiresAction} completely overrides a class-level one.
 *
 * <p>This is the {@code @RequiresAction} sibling of {@link AnnotationSecurityPolicyResolver}. It is
 * deliberately kept separate from the {@link SecurityPolicy} sealed type — {@code @RequiresAction}
 * is a transport-neutral action gate that AND-composes with the Jakarta role/scope policy rather
 * than being one of its variants.
 *
 * <p>The resolved value is parsed with {@link ActionRef#parse(String)} at resolution time, so an
 * unparseable action value fails fast (at startup, never at first request). Whether the resolved
 * action <em>exists</em> in the {@code ActionRegistry} is validated separately by the caller.
 *
 * <p>This implementation is stateless and transport-agnostic, so it can be shared by JAX-RS route
 * registration and any other surface that enforces {@code @RequiresAction}.
 */
public class RequiresActionResolver {

    /**
     * Resolves the effective {@link RequiresAction} from the security annotations present on the
     * method and class, parsing it into a canonical {@link ActionRef}. Method-level annotations
     * completely override class-level following Jakarta EE semantics.
     *
     * <p>Interface-declared annotations are included via {@link AnnotationResolver}.
     *
     * @param method        the annotated method
     * @param resourceClass the class declaring the method
     * @return the resolved {@link ActionRef}, or {@link Optional#empty()} if no
     *     {@code @RequiresAction} is present at either level
     * @throws IllegalArgumentException if a present {@code @RequiresAction} value does not parse as a
     *     canonical {@link ActionRef}
     */
    public Optional<ActionRef> resolve(Method method, Class<?> resourceClass) {
        return resolve(
                AnnotationResolver.resolveMethodAnnotations(method),
                AnnotationResolver.resolveClassAnnotations(resourceClass));
    }

    /**
     * Resolves the effective {@link RequiresAction} from pre-resolved annotation lists, parsing it
     * into a canonical {@link ActionRef}. This variant is preferred when the caller already holds
     * merged annotation lists to avoid redundant traversal.
     *
     * <p>Method-level annotations completely override class-level following Jakarta EE semantics: if
     * the method list declares {@code @RequiresAction}, the class-level annotation is ignored
     * entirely.
     *
     * @param methodAnnotations merged annotations from the method and its overrides in the
     *                          superclass chain and interfaces
     * @param classAnnotations  merged annotations from the class and its superclass chain and
     *                          interfaces
     * @return the resolved {@link ActionRef}, or {@link Optional#empty()} if no
     *     {@code @RequiresAction} is present at either level
     * @throws IllegalArgumentException if a present {@code @RequiresAction} value does not parse as a
     *     canonical {@link ActionRef}
     */
    public Optional<ActionRef> resolve(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        RequiresAction requiresAction = findAnnotation(methodAnnotations, RequiresAction.class);
        if (requiresAction == null) {
            requiresAction = findAnnotation(classAnnotations, RequiresAction.class);
        }
        if (requiresAction == null) {
            return Optional.empty();
        }
        return Optional.of(ActionRef.parse(requiresAction.value()));
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
