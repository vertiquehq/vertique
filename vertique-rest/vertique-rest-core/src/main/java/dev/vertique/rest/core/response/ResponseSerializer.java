// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * Serializes a {@link jakarta.ws.rs.core.Response} to the HTTP wire.
 *
 * <p>The default implementation delegates body encoding to {@link ResponseBodyEncoder}
 * instances, selecting the first matching encoder from a priority-sorted list. Encoders
 * are contributed via Dagger {@code Set<ResponseBodyEncoder>} multibinding.
 *
 * <p>After the encoder encodes the entity, all {@link dev.vertique.rest.core.interceptor.RequestInterceptor#onSerialize}
 * hooks are invoked with both the original {@link jakarta.ws.rs.core.Response} and the
 * encoded {@link SerializedBody} for observability.
 *
 * <p>Contributed as a singleton via Dagger; override for entirely custom
 * serialization orchestration.
 */
public interface ResponseSerializer {

    /**
     * Serializes the given Response and writes it to the HTTP response.
     * Copies status code and headers, then serializes the entity.
     *
     * @param ctx      the current routing context
     * @param response the Response to serialize
     */
    void serialize(RoutingContext ctx, Response response);
}
