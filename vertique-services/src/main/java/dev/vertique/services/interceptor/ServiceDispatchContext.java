// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.core.eventbus.DispatchEnvelope;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable dispatch context passed to {@link ServiceInterceptor} callbacks.
 *
 * <p>Captures the full metadata about an event bus service dispatch: the routing coordinates
 * (address, namespace, name, operation), the stable target id for durable reference, the incoming
 * message body, fire-and-forget semantics, and the annotations resolved at boot time from the
 * target method and class.
 *
 * <p>Uses copy-on-write semantics: {@link #withAttribute(String, Object)} returns a new instance
 * with the attribute added while leaving the original unchanged.
 *
 * <p>Annotations are resolved once at service registration time by
 * {@code AnnotationResolver} and stored here for efficient interceptor use without
 * repeated reflection at dispatch time.
 *
 * @param address           the full event bus address (e.g. {@code "services/integration/user-service/get-user"})
 * @param stableTargetId    the durable dot-delimited target id (e.g. {@code "integration.user-service.get-user"});
 *                          {@code null} for non-service entries such as delayed-job contributors
 * @param namespace         the service namespace (from {@link dev.vertique.services.ServiceContract#namespace()})
 * @param name              the service name (from {@link dev.vertique.services.ServiceContract#value()})
 * @param operation         the durable operation id (from {@link dev.vertique.services.ServiceOperation#value()})
 * @param envelope          the incoming dispatch envelope carrying payload and metadata
 * @param oneWay            {@code true} if this is a fire-and-forget dispatch
 *                          ({@code @OneWay}); {@code false} for request/reply
 * @param methodAnnotations all annotations resolved from the target method and its hierarchy,
 *                          populated at boot time
 * @param classAnnotations  all annotations resolved from the target class and its hierarchy,
 *                          populated at boot time
 * @param attributes        per-dispatch attribute bag for passing data between interceptors;
 *                          defensive copy is made on construction
 */
public record ServiceDispatchContext(
        String address,
        @Nullable String stableTargetId,
        String namespace,
        String name,
        String operation,
        DispatchEnvelope<?> envelope,
        boolean oneWay,
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations,
        Map<String, Object> attributes) {

    /**
     * Compact constructor that defensively copies all mutable inputs to enforce immutability.
     * {@code null} annotation lists are coerced to empty lists; a {@code null} attributes map
     * is coerced to an empty unmodifiable map.
     *
     * @param address           the full event bus address
     * @param stableTargetId    the durable stable target id; may be {@code null}
     * @param namespace         the service namespace
     * @param name              the service name
     * @param operation         the durable operation id
     * @param envelope          the incoming dispatch envelope
     * @param oneWay            fire-and-forget flag
     * @param methodAnnotations method-level annotations; copied to an unmodifiable list
     * @param classAnnotations  class-level annotations; copied to an unmodifiable list
     * @param attributes        attribute bag; copied to an unmodifiable map
     */
    public ServiceDispatchContext {
        methodAnnotations = methodAnnotations != null ? List.copyOf(methodAnnotations) : List.of();
        classAnnotations = classAnnotations != null ? List.copyOf(classAnnotations) : List.of();
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    // --- Annotation lookup ---

    /**
     * Returns the first annotation of the given type from {@link #methodAnnotations()}, or
     * {@code null} if no annotation of that type is present.
     *
     * @param <A>  the annotation type
     * @param type the annotation class to look up
     * @return the annotation instance, or {@code null} if absent
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A methodAnnotation(Class<A> type) {
        for (Annotation a : methodAnnotations) {
            if (type.isInstance(a)) {
                return (A) a;
            }
        }
        return null;
    }

    /**
     * Returns the first annotation of the given type from {@link #classAnnotations()}, or
     * {@code null} if no annotation of that type is present.
     *
     * @param <A>  the annotation type
     * @param type the annotation class to look up
     * @return the annotation instance, or {@code null} if absent
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A classAnnotation(Class<A> type) {
        for (Annotation a : classAnnotations) {
            if (type.isInstance(a)) {
                return (A) a;
            }
        }
        return null;
    }

    // --- Copy-on-write mutators ---

    /**
     * Returns a copy of this context with an additional attribute, leaving the original unchanged.
     *
     * @param key   the attribute key
     * @param value the attribute value
     * @return a new {@link ServiceDispatchContext} with the added attribute
     */
    public ServiceDispatchContext withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(key, value);
        return new ServiceDispatchContext(
                address,
                stableTargetId,
                namespace,
                name,
                operation,
                envelope,
                oneWay,
                methodAnnotations,
                classAnnotations,
                newAttrs);
    }
}
