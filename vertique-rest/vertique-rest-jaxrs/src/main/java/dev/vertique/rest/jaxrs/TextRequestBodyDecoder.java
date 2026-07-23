// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link RequestBodyDecoder} that handles plain-text request bodies.
 *
 * <p>Accepts requests with a {@code text/*} content type (e.g. {@code text/plain},
 * {@code text/html}) when the target type is {@link String}. Returns the raw body
 * string from the body value.
 *
 * <p>Priority: {@code 1000}.
 */
class TextRequestBodyDecoder implements RequestBodyDecoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} when the content type starts with {@code "text/"} and the
     * target type is {@link String}.
     *
     * @param targetType  the desired Java type
     * @param contentType the {@code Content-Type} header value, or {@code null} if absent
     * @return {@code true} if the content type is a text media type and target is {@code String}
     */
    @Override
    public boolean canDecode(Class<?> targetType, String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/") && targetType == String.class;
    }

    /**
     * Returns the raw body string from the body value.
     *
     * @param ctx        the current routing context (unused by this decoder)
     * @param body       the request body value
     * @param targetType the desired Java type (always {@link String} when this decoder is selected)
     * @return the raw body string, or {@code null} if absent
     */
    @Override
    public Object decode(
            RoutingContext ctx, RequestValue body, Class<?> targetType, java.lang.reflect.Type genericType) {
        return body.getString();
    }
}
