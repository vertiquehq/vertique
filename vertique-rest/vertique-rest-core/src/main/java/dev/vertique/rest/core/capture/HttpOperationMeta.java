// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.capture;

import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>Neutral descriptor for an HTTP operation, passed to {@link RestServerRequestEvidenceCapturer}
 * once per request after the request body has been materialized.
 *
 * <p>This record is intentionally free of any dependency on {@code rest-jaxrs}-internal types.
 * A downstream audit adapter can read its own policy annotations off the {@link #method()} and the
 * {@link #resourceClass()} without taking a compile-time dependency on
 * {@code dev.vertique.rest.jaxrs.ResourceMethodMeta}.
 *
 * <p>Read type-level policy from {@link #resourceClass()}, not from
 * {@code method().getDeclaringClass()}: the method's declaring type is not always the resource
 * class. For a route backed by an inherited superclass method it is that superclass, and for a
 * route backed by an inherited interface {@code default} method it is the interface, so a
 * type-level annotation declared on the resource class itself would be missed.
 *
 * <p>Example — reading an {@code @AuditPolicy} with the framework's annotation inheritance
 * ({@link dev.vertique.core.util.AnnotationResolver} walks superclasses, then interfaces):
 * <pre>{@code
 * AuditPolicy methodPolicy = first(AnnotationResolver.resolveMethodAnnotations(meta.method()));
 * AuditPolicy typePolicy = first(AnnotationResolver.resolveClassAnnotations(meta.resourceClass()));
 * }</pre>
 *
 * @param method        the reflected JAX-RS resource method being invoked; never {@code null}
 * @param resourceClass the class of the resource instance serving the route; never {@code null}.
 *                      It differs from {@code method().getDeclaringClass()} whenever the route's
 *                      method is inherited
 * @param operationId   the OpenAPI {@code operationId} declared on the resource method; never
 *                      {@code null}
 * @param routeTemplate the OpenAPI route template (e.g. {@code "/users/{id}"}) captured by
 *                      {@link dev.vertique.rest.core.events.OperationIdCaptureContributor}; may
 *                      be {@code null} when the contributor did not run (e.g. pre-operation
 *                      rejection)
 */
public record HttpOperationMeta(
        Method method,
        Class<?> resourceClass,
        String operationId,
        @Nullable String routeTemplate) {

    /**
     * Rejects a missing method, resource class, or operationId at construction, so a capturer never
     * receives a descriptor it would fail on (and swallow) per request.
     */
    public HttpOperationMeta {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(operationId, "operationId");
    }

    /**
     * Creates a descriptor whose resource class is the method's declaring class.
     *
     * @param method        the reflected resource method; never {@code null}
     * @param operationId   the OpenAPI {@code operationId}; never {@code null}
     * @param routeTemplate the route template; may be {@code null}
     * @deprecated the declaring class is not the resource class for an inherited method; use the
     *     canonical constructor with the resource instance's class. Kept only so sibling framework
     *     modules compile while they migrate.
     */
    @Deprecated(forRemoval = true)
    public HttpOperationMeta(Method method, String operationId, @Nullable String routeTemplate) {
        this(method, method.getDeclaringClass(), operationId, routeTemplate);
    }
}
