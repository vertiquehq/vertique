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
 * {@link ResponseBodyEncoder} for {@link String} entities.
 *
 * <p>Encodes the string as UTF-8 bytes and sets the default Content-Type to
 * {@code text/plain}. The caller may override the content type by setting an
 * explicit {@code Content-Type} header on the {@link Response}.
 *
 * <p>Priority: {@code 1000} (framework default tier).
 */
class StringBodyEncoder implements ResponseBodyEncoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} when {@code entityType} is exactly {@link String}.
     *
     * @param entityType  the runtime class of the response entity
     * @param contentType the effective Content-Type (ignored)
     * @return {@code true} if the entity is a {@link String}
     */
    @Override
    public boolean canEncode(Class<?> entityType, String contentType) {
        return entityType == String.class;
    }

    /**
     * Encodes the string into a {@link BufferedBody} with {@code text/plain} as the
     * default content type. Content-Length is omitted to avoid mismatches with
     * multi-byte UTF-8 characters.
     *
     * @param ctx      the current routing context
     * @param response the full JAX-RS response
     * @param entity   the non-null entity to encode
     * @return a {@link BufferedBody} containing the encoded string
     */
    @Override
    public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
        String str = (String) entity;
        return new BufferedBody(Buffer.buffer(str), "text/plain", null);
    }
}
