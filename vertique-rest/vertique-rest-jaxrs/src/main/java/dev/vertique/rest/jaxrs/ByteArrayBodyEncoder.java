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
 * {@link ResponseBodyEncoder} for {@code byte[]} entities.
 *
 * <p>Wraps the byte array in a {@link Buffer} and sets the default Content-Type to
 * {@code application/octet-stream}. The caller may override the content type by
 * setting an explicit {@code Content-Type} header on the {@link Response}.
 *
 * <p>Priority: {@code 1000} (framework default tier).
 */
class ByteArrayBodyEncoder implements ResponseBodyEncoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} when {@code entityType} is exactly {@code byte[]}.
     *
     * @param entityType  the runtime class of the response entity
     * @param contentType the effective Content-Type (ignored)
     * @return {@code true} if the entity is a {@code byte[]}
     */
    @Override
    public boolean canEncode(Class<?> entityType, String contentType) {
        return entityType == byte[].class;
    }

    /**
     * Wraps the byte array in a {@link BufferedBody} with {@code application/octet-stream}
     * as the default content type.
     *
     * @param ctx      the current routing context
     * @param response the full JAX-RS response
     * @param entity   the non-null entity to encode
     * @return a {@link BufferedBody} wrapping the bytes with their length
     */
    @Override
    public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
        byte[] bytes = (byte[]) entity;
        return new BufferedBody(Buffer.buffer(bytes), "application/octet-stream", (long) bytes.length);
    }
}
