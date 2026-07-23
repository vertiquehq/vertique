// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * Builds a JAX-RS {@link Response} from a result value.
 * Used by the internal response pipeline for type-based response dispatch.
 *
 * <p>Implementations construct and return the {@link Response} without sending it.
 * Serialization and wire-writing are delegated to {@link ResponseSerializer},
 * allowing {@link dev.vertique.rest.core.interceptor.RequestInterceptor#transformResponse} and
 * {@link dev.vertique.rest.core.interceptor.RequestInterceptor#onSerialize} hooks to observe and transform
 * the response before it reaches the client.
 *
 * @param <T> the result type this producer handles
 */
@FunctionalInterface
public interface ResponseProducer<T> {

    /**
     * Builds a JAX-RS {@link Response} for the given result.
     * Must not write to the HTTP response directly — return the {@link Response}
     * and let the response pipeline delegate to {@link ResponseSerializer} for serialization.
     *
     * @param ctx    the current routing context
     * @param result the method result to convert into a Response
     * @return the Response to serialize and send to the client
     */
    Response produce(RoutingContext ctx, T result);
}
