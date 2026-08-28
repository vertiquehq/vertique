// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import dev.vertique.core.util.AnnotationResolver;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable resilience declaration metadata resolved for one operation.
 *
 * <p>Method-level declarations take precedence over type-level declarations. The record contains
 * snapshots of annotation members rather than live annotation-interface instances, making it safe
 * for generated contributors and reflective registration to share.
 *
 * @param timeout the resolved timeout declaration
 * @param circuitBreaker the resolved circuit-breaker declaration
 * @param retry the resolved retry declaration
 */
public record ResilienceAnnotations(
        Optional<TimeoutDeclaration> timeout,
        Optional<CircuitBreakerDeclaration> circuitBreaker,
        Optional<RetryDeclaration> retry) {

    /** No resilience declarations are configured. */
    public static final ResilienceAnnotations NONE =
            new ResilienceAnnotations(Optional.empty(), Optional.empty(), Optional.empty());

    /** Validates non-null optional containers. */
    public ResilienceAnnotations {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        Objects.requireNonNull(retry, "retry");
    }

    /**
     * Returns whether at least one resilience declaration is present.
     *
     * @return {@code true} when a declaration is configured
     */
    public boolean hasAny() {
        return timeout.isPresent() || circuitBreaker.isPresent() || retry.isPresent();
    }

    /**
     * Resolves declarations from a method and an explicit type.
     *
     * @param type the type providing type-level defaults
     * @param method the method providing method-level overrides
     * @return the resolved declarations, or {@link #NONE}
     */
    public static ResilienceAnnotations resolve(Class<?> type, Method method) {
        Timeout timeout = resolveAnnotation(type, method, Timeout.class);
        CircuitBreaker circuitBreaker = resolveAnnotation(type, method, CircuitBreaker.class);
        Retry retry = resolveAnnotation(type, method, Retry.class);

        if (timeout == null && circuitBreaker == null && retry == null) {
            return NONE;
        }
        return new ResilienceAnnotations(
                Optional.ofNullable(timeout).map(value -> new TimeoutDeclaration(value.value(), value.unit())),
                Optional.ofNullable(circuitBreaker)
                        .map(value -> new CircuitBreakerDeclaration(
                                value.maxFailures(), value.timeoutMs(), value.resetTimeoutMs())),
                Optional.ofNullable(retry)
                        .map(value -> new RetryDeclaration(
                                value.maxRetries(),
                                value.delayMs(),
                                value.backoffMultiplier(),
                                value.maxDelayMs(),
                                value.backoff(),
                                List.of(value.retryOn()),
                                List.of(value.abortOn()))));
    }

    /**
     * Resolves declarations from a method and its declaring class.
     *
     * @param method the method to inspect
     * @return the resolved declarations, or {@link #NONE}
     */
    public static ResilienceAnnotations resolve(Method method) {
        return resolve(method.getDeclaringClass(), method);
    }

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
