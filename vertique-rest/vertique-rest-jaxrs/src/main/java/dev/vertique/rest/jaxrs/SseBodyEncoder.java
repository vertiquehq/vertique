// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.config.SseConfig;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.core.response.StreamingBody;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * {@link ResponseBodyEncoder} that handles {@link ReadStream}{@code <SseEvent>} entities with
 * a {@code text/event-stream} content type.
 *
 * <p>When selected, this encoder wraps the source stream in a {@link SseReadStream} that formats
 * each {@link SseEvent} into its SSE wire representation and returns a {@link StreamingBody} with
 * {@code text/event-stream; charset=utf-8} as the content type.
 *
 * <p>Encoder selection is gated on the effective content type alone — not the entity type.
 * This ensures that {@link MediaTypeValidator} startup checks work correctly for methods that
 * declare {@code @Produces("text/event-stream")} regardless of the return type signature.
 *
 * <p>At runtime, {@link #encode} verifies that the entity is indeed a {@link ReadStream} and
 * throws {@link IllegalArgumentException} if it is not, to surface misconfigurations early.
 *
 * <p>Priority: {@code 999} — runs before {@link ReadStreamBodyEncoder} ({@code 1000}) and the
 * JSON fallback ({@code 1100}).
 */
class SseBodyEncoder implements ResponseBodyEncoder {

    // --- Constants ---

    /** Content-Type value emitted in the {@link StreamingBody}. */
    static final String SSE_CONTENT_TYPE = "text/event-stream; charset=utf-8";

    // --- Fields ---

    private final SseConfig config;

    // --- Constructor ---

    /**
     * Creates an encoder with the given SSE configuration.
     *
     * @param config the application-level SSE configuration controlling keep-alive and buffer
     *               settings; must not be {@code null}
     */
    SseBodyEncoder(SseConfig config) {
        this.config = config;
    }

    // --- ResponseBodyEncoder ---

    /**
     * Returns {@code 999} so this encoder is selected before {@link ReadStreamBodyEncoder}
     * ({@code 1000}) but after any application-specific encoders at the default priority
     * ({@code 0}).
     *
     * @return {@code 999}
     */
    @Override
    public int priority() {
        return 999;
    }

    /**
     * Returns {@code true} when the effective content type contains {@code "text/event-stream"}.
     *
     * <p>The check is intentionally on the content type only, not the entity type, so that
     * {@link MediaTypeValidator} startup compatibility checks pass for resource methods that
     * declare {@code @Produces("text/event-stream")}.
     *
     * @param entityType  the runtime class of the response entity (not used for selection)
     * @param contentType the effective Content-Type, or {@code null} if unset
     * @return {@code true} if {@code contentType} contains {@code "text/event-stream"}
     */
    @Override
    public boolean canEncode(Class<?> entityType, String contentType) {
        return contentType != null && contentType.startsWith("text/event-stream");
    }

    /**
     * Wraps the entity {@link ReadStream} in a {@link SseReadStream} and returns a
     * {@link StreamingBody} with {@code text/event-stream; charset=utf-8}.
     *
     * @param ctx      the current routing context providing access to the Vert.x instance and
     *                 HTTP response
     * @param response the full JAX-RS response
     * @param entity   the response entity; must be a {@link ReadStream}{@code <SseEvent>}
     * @return a {@link StreamingBody} backed by the formatted SSE stream
     * @throws IllegalArgumentException if {@code entity} is not a {@link ReadStream}
     */
    @Override
    @SuppressWarnings("unchecked")
    public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
        if (!(entity instanceof ReadStream)) {
            throw new IllegalArgumentException("SseBodyEncoder requires a ReadStream entity but got: "
                    + entity.getClass().getName());
        }
        ReadStream<SseEvent> source = (ReadStream<SseEvent>) entity;
        Vertx vertx = ctx.vertx();
        SseReadStream sseStream = new SseReadStream(source, vertx, config, ctx.response());
        return new StreamingBody(sseStream, SSE_CONTENT_TYPE, null);
    }
}
