// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link RequestBodyDecoder} that handles binary ({@code application/octet-stream}) request bodies.
 *
 * <p>Accepts requests with an {@code application/octet-stream} content type when the target
 * type is either {@link Buffer} or {@code byte[]}. Reads the raw body value from the
 * body value and adapts it to the requested target type.
 *
 * <p>Priority: {@code 1000}.
 */
class BinaryRequestBodyDecoder implements RequestBodyDecoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} when the content type is {@code application/octet-stream} and the
     * target type is {@link Buffer} or {@code byte[]}.
     *
     * @param targetType  the desired Java type
     * @param contentType the {@code Content-Type} header value, or {@code null} if absent
     * @return {@code true} if the content type is octet-stream and target is a binary type
     */
    @Override
    public boolean canDecode(Class<?> targetType, String contentType) {
        return contentType != null
                && contentType.toLowerCase().startsWith("application/octet-stream")
                && (targetType == Buffer.class || targetType == byte[].class);
    }

    /**
     * Decodes the request body to a {@link Buffer} or {@code byte[]} depending on the target type.
     *
     * @param ctx        the current routing context (unused by this decoder)
     * @param body       the request body value
     * @param targetType the desired Java type ({@link Buffer} or {@code byte[]})
     * @return the decoded value, or {@code null} if the raw body is absent or not a {@link Buffer}
     */
    @Override
    public Object decode(
            RoutingContext ctx, RequestValue body, Class<?> targetType, java.lang.reflect.Type genericType) {
        Object raw = body.get();
        if (targetType == Buffer.class && raw instanceof Buffer) {
            return raw;
        }
        if (targetType == byte[].class && raw instanceof Buffer buf) {
            return buf.getBytes();
        }
        return null;
    }
}
