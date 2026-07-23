// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import dev.vertique.core.util.AnnotationResolver;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Resolved resilience annotations for a single operation.
 *
 * <p>Wraps the three resilience annotations ({@link Timeout}, {@link CircuitBreaker}, {@link Retry})
 * after applying method-level-overrides-type-level resolution. Use the {@link #resolve} factory
 * methods to create instances from annotated methods.
 *
 * <p>A method-level annotation completely replaces the corresponding type-level annotation — there
 * is no merging of individual fields.
 *
 * @param timeout the resolved timeout annotation, or {@code null} if not configured
 * @param circuitBreaker the resolved circuit breaker annotation, or {@code null} if not configured
 * @param retry the resolved retry annotation, or {@code null} if not configured
 */
public record ResilienceAnnotations(Timeout timeout, CircuitBreaker circuitBreaker, Retry retry) {

    /** No policies configured. */
    public static final ResilienceAnnotations NONE = new ResilienceAnnotations(null, null, null);

    /**
     * Returns {@code true} if any resilience annotation is present.
     *
     * @return {@code true} if at least one of timeout, circuit breaker, or retry is non-null
     */
    public boolean hasAny() {
        return timeout != null || circuitBreaker != null || retry != null;
    }

    /**
     * Resolves resilience annotations from a method and an explicit type (contract interface).
     *
     * <p>Method-level annotations take precedence over type-level annotations. If no resilience
     * annotations are found at either level, returns {@link #NONE}.
     *
     * @param type the type providing type-level defaults (typically the contract interface)
     * @param method the method providing method-level overrides
     * @return the resolved annotations, or {@link #NONE} if none are present
     */
    public static ResilienceAnnotations resolve(Class<?> type, Method method) {
        Timeout timeout = resolveAnnotation(type, method, Timeout.class);
        CircuitBreaker circuitBreaker = resolveAnnotation(type, method, CircuitBreaker.class);
        Retry retry = resolveAnnotation(type, method, Retry.class);

        if (timeout == null && circuitBreaker == null && retry == null) {
            return NONE;
        }
        return new ResilienceAnnotations(timeout, circuitBreaker, retry);
    }

    /**
     * Resolves resilience annotations from a method and its declaring class.
     *
     * <p>Equivalent to {@code resolve(method.getDeclaringClass(), method)}.
     *
     * @param method the method to resolve annotations from
     * @return the resolved annotations, or {@link #NONE} if none are present
     */
    public static ResilienceAnnotations resolve(Method method) {
        return resolve(method.getDeclaringClass(), method);
    }

    /**
     * Resolves a single annotation type, applying method-level-overrides-type-level precedence and
     * walking the full supertype hierarchy on each side.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Method-level annotations from {@code method} itself plus every overridden
     *       same-signature method walked by
     *       {@link AnnotationResolver#resolveMethodAnnotations(Method)} (superclass chain plus
     *       transitive interfaces of {@code method.getDeclaringClass()}).</li>
     *   <li>Class-level annotations from {@code type} plus every superclass and transitively
     *       reachable interface walked by
     *       {@link AnnotationResolver#resolveClassAnnotations(Class)}.</li>
     * </ol>
     *
     * <p>The first match wins. {@code null} is returned only when no annotation of
     * {@code annotationType} is present anywhere in either hierarchy.
     *
     * <p>Walking interface hierarchies is necessary because Java's {@link Class#getAnnotation} and
     * {@link Method#getAnnotation} do not traverse super-interfaces — and {@link
     * java.lang.annotation.Inherited @Inherited} does not apply to interface annotations.
     *
     * @param <A> the annotation type
     * @param type the declaring type providing the type-level defaults
     * @param method the method providing the method-level overrides
     * @param annotationType the annotation class to look up
     * @return the resolved annotation, or {@code null} if none is present in either hierarchy
     */
    @Nullable
    private static <A extends Annotation> A resolveAnnotation(Class<?> type, Method method, Class<A> annotationType) {
        for (Annotation annotation : AnnotationResolver.resolveMethodAnnotations(method)) {
            if (annotationType.isInstance(annotation)) {
                return annotationType.cast(annotation);
            }
        }
        for (Annotation annotation : AnnotationResolver.resolveClassAnnotations(type)) {
            if (annotationType.isInstance(annotation)) {
                return annotationType.cast(annotation);
            }
        }
        return null;
    }
}
