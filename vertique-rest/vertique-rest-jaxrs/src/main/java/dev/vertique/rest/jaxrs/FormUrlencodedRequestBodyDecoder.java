// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * {@link RequestBodyDecoder} that handles {@code application/x-www-form-urlencoded} request bodies,
 * mapping form attributes to a POJO via {@link JsonObject#mapTo(Class)}.
 *
 * <p>Accepts requests with an {@code application/x-www-form-urlencoded} content type when the
 * target type is a POJO (i.e. not {@link String}, {@link Buffer}, {@code byte[]}, or
 * {@link JsonObject}). Builds a {@link JsonObject} from the request's form attributes and
 * maps it to the target type.
 *
 * <p>Priority: {@code 1000}.
 */
class FormUrlencodedRequestBodyDecoder implements RequestBodyDecoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} when the content type is {@code application/x-www-form-urlencoded}
     * and the target type is a POJO (not {@link String}, {@link Buffer}, {@code byte[]}, or
     * {@link JsonObject}).
     *
     * @param targetType  the desired Java type
     * @param contentType the {@code Content-Type} header value, or {@code null} if absent
     * @return {@code true} if the content type is form-urlencoded and target is a POJO type
     */
    @Override
    public boolean canDecode(Class<?> targetType, String contentType) {
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            return false;
        }
        return targetType != String.class
                && targetType != Buffer.class
                && targetType != byte[].class
                && targetType != JsonObject.class;
    }

    /**
     * Decodes form attributes from the routing context into the target POJO type.
     *
     * <p>Builds a {@link JsonObject} from all form attribute key-value pairs and maps it
     * to the target type via {@link JsonObject#mapTo(Class)}.
     *
     * @param ctx        the current routing context (source of form attributes)
     * @param body       the request body value (unused by this decoder)
     * @param targetType the desired POJO type to map form attributes into
     * @return the decoded POJO, or {@code null} if no form attributes are present
     */
    @Override
    public Object decode(
            RoutingContext ctx, RequestValue body, Class<?> targetType, java.lang.reflect.Type genericType) {
        JsonObject json = new JsonObject();
        for (var entry : ctx.request().formAttributes()) {
            json.put(entry.getKey(), entry.getValue());
        }
        if (json.isEmpty()) {
            return null;
        }
        return json.mapTo(targetType);
    }
}
