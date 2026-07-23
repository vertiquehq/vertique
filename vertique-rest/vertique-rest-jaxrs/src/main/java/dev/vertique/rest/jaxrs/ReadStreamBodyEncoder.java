// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.core.response.StreamingBody;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * {@link ResponseBodyEncoder} for {@link ReadStream} entities.
 *
 * <p>Produces a {@link StreamingBody} that pipes the stream directly to the HTTP
 * response without buffering. No default Content-Type is set; the caller is
 * responsible for setting an appropriate {@code Content-Type} on the {@link Response}.
 *
 * <p><strong>Important:</strong> Only {@code ReadStream<Buffer>} implementations whose
 * generic signature resolves concretely to {@link Buffer} are supported. Raw
 * {@code ReadStream} types and streams with non-{@code Buffer} element types are rejected
 * during encoder selection to fail closed rather than triggering a later runtime error.
 *
 * <p>Priority: {@code 1000} (framework default tier).
 */
class ReadStreamBodyEncoder implements ResponseBodyEncoder {

    @Override
    public int priority() {
        return 1000;
    }

    /**
     * Returns {@code true} only when {@code entityType} is a {@link ReadStream}
     * whose generic element type resolves to {@link Buffer}.
     *
     * @param entityType  the runtime class of the response entity
     * @param contentType the effective Content-Type (ignored)
     * @return {@code true} if the entity is a supported {@link ReadStream<Buffer>}
     */
    @Override
    public boolean canEncode(Class<?> entityType, String contentType) {
        return isBufferReadStreamType(entityType);
    }

    /**
     * Wraps the stream in a {@link StreamingBody} with no default content type or length.
     *
     * @param ctx      the current routing context
     * @param response the full JAX-RS response
     * @param entity   the non-null entity to encode
     * @return a {@link StreamingBody} for piping to the HTTP response
     */
    @Override
    @SuppressWarnings("unchecked")
    public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
        if (!isBufferReadStreamType(entity.getClass())) {
            throw new IllegalArgumentException(
                    "ReadStreamBodyEncoder only supports ReadStream<Buffer> implementations");
        }
        ReadStream<Buffer> stream = (ReadStream<Buffer>) entity;
        return new StreamingBody(stream, null, null);
    }

    private static boolean isBufferReadStreamType(Class<?> entityType) {
        return ReadStream.class.isAssignableFrom(entityType)
                && TypeResolver.resolveTypeArgument(entityType, ReadStream.class) == Buffer.class;
    }
}
