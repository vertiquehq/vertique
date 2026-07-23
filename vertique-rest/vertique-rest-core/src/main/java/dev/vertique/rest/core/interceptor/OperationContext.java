// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.interceptor;

import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable context passed to {@link OperationInterceptor} callbacks, describing the JAX-RS
 * operation being invoked.
 *
 * <p>Captures the operation identifier, the current Vert.x {@link RoutingContext}, and the
 * annotations resolved at boot time from the resource method and its declaring class. The
 * annotation lists enable interceptors to read method-level metadata (such as
 * {@code @RolesAllowed}, custom audit annotations, or resilience hints) without performing
 * reflection at request time.
 *
 * <p><strong>Mutability note:</strong> {@link RoutingContext} is inherently mutable. The
 * immutability guarantee of this record applies only to the record's own fields
 * ({@code operationId}, {@code methodAnnotations}, {@code classAnnotations}, and
 * {@code attributes}). Use {@link RoutingContext#data()} for sharing cross-lifecycle data with
 * other handlers; the attributes map on this record is intended for interceptor-to-interceptor
 * communication within a single chain.
 *
 * <p>Uses copy-on-write semantics: {@link #withAttribute(String, Object)} returns a new instance
 * with the attribute added while leaving the original unchanged.
 *
 * @param operationId       the {@code operationId} from the OpenAPI spec / {@code @Operation}
 *                          annotation, used to identify the resource method
 * @param routingContext    the Vert.x {@link RoutingContext} for the current request; mutable
 * @param methodAnnotations all annotations resolved from the target resource method and its
 *                          hierarchy, populated at boot time via {@code AnnotationResolver}
 * @param classAnnotations  all annotations resolved from the resource class and its hierarchy,
 *                          populated at boot time via {@code AnnotationResolver}
 * @param attributes        per-operation attribute bag for passing data between interceptors;
 *                          defensive copy is made on construction
 */
public record OperationContext(
        String operationId,
        RoutingContext routingContext,
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations,
        Map<String, Object> attributes) {

    /**
     * Compact constructor that defensively copies all mutable inputs to enforce immutability of
     * the record's own fields. {@code null} annotation lists are coerced to empty lists; a
     * {@code null} attributes map is coerced to an empty unmodifiable map.
     *
     * @param operationId       the operation identifier
     * @param routingContext    the Vert.x routing context (mutable — see class javadoc)
     * @param methodAnnotations method-level annotations; copied to an unmodifiable list
     * @param classAnnotations  class-level annotations; copied to an unmodifiable list
     * @param attributes        attribute bag; copied to an unmodifiable map
     */
    public OperationContext {
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
     * @return a new {@link OperationContext} with the added attribute
     */
    public OperationContext withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(key, value);
        return new OperationContext(operationId, routingContext, methodAnnotations, classAnnotations, newAttrs);
    }
}
