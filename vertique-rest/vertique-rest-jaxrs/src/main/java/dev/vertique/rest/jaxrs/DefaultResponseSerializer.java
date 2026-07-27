// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.ResponseSerializer;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.core.response.StreamingBody;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ResponseSerializer} that encodes a JAX-RS {@link Response} entity and writes
 * it to the HTTP wire.
 *
 * <p>This serializer is invoked only when the response has a non-null entity. Status code and
 * response headers are expected to have already been written to the {@link HttpServerResponse}
 * by {@link ResponsePipeline} before {@link #serialize} is called. HEAD requests and empty-body
 * responses (204, 304, 412, etc.) are handled upstream and never reach this serializer.
 *
 * <p>Body encoding is delegated to the first {@link ResponseBodyEncoder} in the
 * {@link dev.vertique.core.extension.OrderedExtension}-sorted list whose
 * {@link ResponseBodyEncoder#canEncode} returns {@code true} for the entity type and
 * effective content type. If no encoder matches, a {@code 500} response is sent and a
 * warning is logged.
 *
 * <p>After the encoder encodes the entity, all {@link RequestInterceptor#onSerialize}
 * hooks are invoked in {@link dev.vertique.core.extension.OrderedExtension} order
 * (phase → priority → orderKey) for observability (logging, metrics, audit).
 */
public class DefaultResponseSerializer implements ResponseSerializer {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultResponseSerializer.class);

    private final List<RequestInterceptor> hooks;
    private final List<ResponseBodyEncoder> encoders;

    /**
     * Creates a serializer with the given lifecycle hooks and body encoders.
     *
     * @param hooks    the hooks to invoke during serialization, already sorted by
     *                 {@link dev.vertique.core.extension.OrderedExtension#comparator()} (phase → priority → orderKey)
     * @param encoders the body encoders to select from, already sorted by
     *                 {@link dev.vertique.core.extension.OrderedExtension#comparator()} (phase → priority → orderKey)
     */
    public DefaultResponseSerializer(List<RequestInterceptor> hooks, List<ResponseBodyEncoder> encoders) {
        this.hooks = hooks;
        this.encoders = encoders;
    }

    /**
     * Encodes the response entity and writes it to the HTTP wire.
     *
     * <p>This method assumes that:
     * <ul>
     *   <li>The HTTP response status code and headers have already been set by the caller.</li>
     *   <li>The response entity is non-null (empty-body paths are handled upstream).</li>
     * </ul>
     *
     * @param ctx      the current routing context; provides access to the HTTP response
     * @param response the JAX-RS response whose entity is to be encoded and written
     * @return an already-succeeded future; this stub does not yet mirror wire completion
     */
    @Override
    public Future<Void> serialize(RoutingContext ctx, Response response) {
        HttpServerResponse httpResponse = ctx.response();
        Object entity = response.getEntity();

        // Null entity: the caller should have called httpResponse.end() already.
        // This path handles the edge case where serialize() is called with a null entity
        // (e.g. from sendError with a no-body response).
        if (entity == null) {
            for (RequestInterceptor hook : hooks) {
                hook.onSerialize(ctx, response, null);
            }
            httpResponse.end();
            return Future.succeededFuture();
        }

        // Determine effective content type from the already-set response headers
        String effectiveContentType = httpResponse.headers().get("Content-Type");

        // Select encoder
        ResponseBodyEncoder selectedEncoder = null;
        for (ResponseBodyEncoder encoder : encoders) {
            if (encoder.canEncode(entity.getClass(), effectiveContentType)) {
                selectedEncoder = encoder;
                break;
            }
        }

        if (selectedEncoder == null) {
            LOG.warn(
                    "No ResponseBodyEncoder found for entity type {} with content type {}",
                    entity.getClass().getName(),
                    effectiveContentType != null ? effectiveContentType : "(none)");
            httpResponse.setStatusCode(500);
            String errorJson = Json.encode(ProblemDetail.of(
                    500, "No ResponseBodyEncoder for " + entity.getClass().getName()));
            BufferedBody errorBody = new BufferedBody(Buffer.buffer(errorJson), "application/problem+json", null);

            Response errorResponse =
                    Response.status(500).type("application/problem+json").build();
            for (RequestInterceptor hook : hooks) {
                hook.onSerialize(ctx, errorResponse, errorBody);
            }

            httpResponse.putHeader("Content-Type", "application/problem+json");
            httpResponse.end(errorJson);
            return Future.succeededFuture();
        }

        // Encode
        SerializedBody body = selectedEncoder.encode(ctx, response, entity);

        // Observe
        for (RequestInterceptor hook : hooks) {
            hook.onSerialize(ctx, response, body);
        }

        // Dispatch to wire
        switch (body) {
            case BufferedBody(var buf, var ct, var len) -> {
                applyEncoderHeaders(httpResponse, ct, len);
                httpResponse.end(buf);
            }
            case StreamingBody(var stream, var ct, var len) -> {
                applyEncoderHeaders(httpResponse, ct, len);
                stream.pipeTo(httpResponse);
            }
        }
        return Future.succeededFuture();
    }

    /**
     * Applies encoder-provided content type and content length to the HTTP response,
     * unless they are already set.
     *
     * @param httpResponse  the HTTP response to update
     * @param contentType   the content type from the encoder; {@code null} to skip
     * @param contentLength the content length from the encoder; {@code null} to skip
     */
    private static void applyEncoderHeaders(HttpServerResponse httpResponse, String contentType, Long contentLength) {
        if (contentType != null && !httpResponse.headers().contains("Content-Type")) {
            httpResponse.putHeader("Content-Type", contentType);
        }
        if (contentLength != null) {
            httpResponse.putHeader("Content-Length", contentLength.toString());
        }
    }
}
