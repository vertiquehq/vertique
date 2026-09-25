// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.capture;

import jakarta.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>Neutral descriptor for an HTTP operation, passed to {@link RestServerRequestEvidenceCapturer}
 * once per request after the request body has been materialized.
 *
 * <p>This record is intentionally free of any dependency on {@code rest-jaxrs}-internal types.
 * A downstream audit adapter can read its own policy annotations off the {@link #method()} or its
 * declaring class without taking a compile-time dependency on
 * {@code dev.vertique.rest.jaxrs.ResourceMethodMeta}.
 *
 * <p>Example — reading an {@code @AuditPolicy} from the resource method:
 * <pre>{@code
 * AuditPolicy policy = meta.method().getAnnotation(AuditPolicy.class);
 * if (policy == null) {
 *     policy = meta.method().getDeclaringClass().getAnnotation(AuditPolicy.class);
 * }
 * }</pre>
 *
 * <p><strong>Caveat:</strong> {@code method().getDeclaringClass()} is the type that declares the
 * method, which is not always the resource class. For a route backed by an inherited superclass
 * method it is that superclass, and for a route backed by an inherited interface {@code default}
 * method it is the interface, so a type-level annotation declared on the resource class itself is
 * not visible through it. This record does not carry the resource class.
 *
 * @param method        the reflected JAX-RS resource method being invoked; never {@code null}
 * @param operationId   the OpenAPI {@code operationId} declared on the resource method; never
 *                      {@code null}
 * @param routeTemplate the OpenAPI route template (e.g. {@code "/users/{id}"}) captured by
 *                      {@link dev.vertique.rest.core.events.OperationIdCaptureContributor}; may
 *                      be {@code null} when the contributor did not run (e.g. pre-operation
 *                      rejection)
 */
public record HttpOperationMeta(
        Method method, String operationId, @Nullable String routeTemplate) {}
