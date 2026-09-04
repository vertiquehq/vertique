// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import dev.vertique.core.codegen.MethodMetadata;
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
 * @param bulkhead the resolved bulkhead declaration
 * @param policy the resolved named policy tier
 */
public record ResilienceAnnotations(
        Optional<TimeoutDeclaration> timeout,
        Optional<CircuitBreakerDeclaration> circuitBreaker,
        Optional<RetryDeclaration> retry,
        Optional<BulkheadDeclaration> bulkhead,
        Optional<String> policy) {

    /** No resilience declarations are configured. */
    public static final ResilienceAnnotations NONE = new ResilienceAnnotations(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** Validates non-null optional containers. */
    public ResilienceAnnotations {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(bulkhead, "bulkhead");
        Objects.requireNonNull(policy, "policy");
    }

    /**
     * Returns whether at least one resilience declaration is present.
     *
     * @return {@code true} when a declaration is configured
     */
    public boolean hasAny() {
        return timeout.isPresent()
                || circuitBreaker.isPresent()
                || retry.isPresent()
                || bulkhead.isPresent()
                || policy.isPresent();
    }

    /**
     * Resolves declarations from a method and an explicit type.
     *
     * @param type the type providing type-level defaults
     * @param method the method providing method-level overrides
     * @return the resolved declarations, or {@link #NONE}
     */
    public static ResilienceAnnotations resolve(Class<?> type, Method method) {
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(type);
        Timeout timeout = resolveAnnotation(methodAnnotations, classAnnotations, Timeout.class);
        CircuitBreaker circuitBreaker = resolveAnnotation(methodAnnotations, classAnnotations, CircuitBreaker.class);
        Retry retry = resolveAnnotation(methodAnnotations, classAnnotations, Retry.class);
        Bulkhead bulkhead = resolveAnnotation(methodAnnotations, classAnnotations, Bulkhead.class);
        Resilient resilient = resolveAnnotation(methodAnnotations, classAnnotations, Resilient.class);
        Optional<String> policy = resolvePolicy(resilient);

        return assemble(
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
                                List.of(value.abortOn()))),
                Optional.ofNullable(bulkhead)
                        .map(value -> new BulkheadDeclaration(
                                value.maxConcurrentCalls(),
                                value.mode(),
                                value.maxQueueSize(),
                                value.queueTimeoutMs())),
                policy);
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

    /**
     * Resolves method-level declarations from reflection-free method metadata.
     *
     * <p>This overload does not inspect the declaring type. Generated method metadata has no
     * reflective type walk, so type-level declarations are intentionally invisible here.
     *
     * @param method the method metadata to inspect
     * @return the resolved method-level declarations, or {@link #NONE}
     */
    public static ResilienceAnnotations resolve(MethodMetadata method) {
        Optional<TimeoutDeclaration> timeout =
                method.findAnnotation(Timeout.class).map(value -> new TimeoutDeclaration(value.value(), value.unit()));
        Optional<CircuitBreakerDeclaration> circuitBreaker = method.findAnnotation(CircuitBreaker.class)
                .map(value ->
                        new CircuitBreakerDeclaration(value.maxFailures(), value.timeoutMs(), value.resetTimeoutMs()));
        Optional<RetryDeclaration> retry = method.findAnnotation(Retry.class)
                .map(value -> new RetryDeclaration(
                        value.maxRetries(),
                        value.delayMs(),
                        value.backoffMultiplier(),
                        value.maxDelayMs(),
                        value.backoff(),
                        List.of(value.retryOn()),
                        List.of(value.abortOn())));
        Optional<BulkheadDeclaration> bulkhead = method.findAnnotation(Bulkhead.class)
                .map(value -> new BulkheadDeclaration(
                        value.maxConcurrentCalls(), value.mode(), value.maxQueueSize(), value.queueTimeoutMs()));
        Optional<String> policy =
                method.findAnnotation(Resilient.class).map(Resilient::policy).filter(value -> !value.isBlank());

        return assemble(timeout, circuitBreaker, retry, bulkhead, policy);
    }

    /**
     * Returns shared empty metadata when no declaration is present, otherwise the resolved snapshot.
     *
     * @param timeout the resolved timeout declaration
     * @param circuitBreaker the resolved circuit-breaker declaration
     * @param retry the resolved retry declaration
     * @param bulkhead the resolved bulkhead declaration
     * @param policy the resolved named policy tier
     * @return the shared empty value or a snapshot containing the resolved declarations
     */
    private static ResilienceAnnotations assemble(
            Optional<TimeoutDeclaration> timeout,
            Optional<CircuitBreakerDeclaration> circuitBreaker,
            Optional<RetryDeclaration> retry,
            Optional<BulkheadDeclaration> bulkhead,
            Optional<String> policy) {
        if (timeout.isEmpty()
                && circuitBreaker.isEmpty()
                && retry.isEmpty()
                && bulkhead.isEmpty()
                && policy.isEmpty()) {
            return NONE;
        }
        return new ResilienceAnnotations(timeout, circuitBreaker, retry, bulkhead, policy);
    }

    private static Optional<String> resolvePolicy(Resilient resilient) {
        return Optional.ofNullable(resilient).map(Resilient::policy).filter(value -> !value.isBlank());
    }

    private static <A extends Annotation> A resolveAnnotation(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations, Class<A> annotationType) {
        for (Annotation annotation : methodAnnotations) {
            if (annotationType.isInstance(annotation)) {
                return annotationType.cast(annotation);
            }
        }
        for (Annotation annotation : classAnnotations) {
            if (annotationType.isInstance(annotation)) {
                return annotationType.cast(annotation);
            }
        }
        return null;
    }
}
