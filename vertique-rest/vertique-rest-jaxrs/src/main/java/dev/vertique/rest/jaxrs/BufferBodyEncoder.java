// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * {@link ResponseBodyEncoder} for {@link Buffer} entities.
 *
 * <p>Passes the buffer through as pre-serialized content without setting a default
 * Content-Type (the caller is responsible for setting an appropriate Content-Type
 * on the {@link Response} or relying on a previous middleware).
 *
 * <p>Priority: {@code 1000} (framework default tier).
 */
class BufferBodyEncoder implements ResponseBodyEncoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} when {@code entityType} is {@link Buffer} or a subclass.
     *
     * @param entityType  the runtime class of the response entity
     * @param contentType the effective Content-Type (ignored)
     * @return {@code true} if the entity is a {@link Buffer}
     */
    @Override
    public boolean canEncode(Class<?> entityType, String contentType) {
        return Buffer.class.isAssignableFrom(entityType);
    }

    /**
     * Wraps the buffer in a {@link BufferedBody} with no default content type.
     *
     * @param ctx      the current routing context
     * @param response the full JAX-RS response
     * @param entity   the non-null entity to encode
     * @return a {@link BufferedBody} with the buffer and its length
     */
    @Override
    public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
        Buffer buffer = (Buffer) entity;
        return new BufferedBody(buffer, null, (long) buffer.length());
    }
}
