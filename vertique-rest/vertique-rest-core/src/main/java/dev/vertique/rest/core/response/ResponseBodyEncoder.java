// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;

/**
 * SPI for encoding a response entity to the wire.
 *
 * <p>Implementations handle specific entity types and/or media types. The framework
 * serializer selects the first matching encoder from a priority-sorted list and delegates
 * body encoding to it.
 *
 * <p>Encoders are contributed via Dagger {@code Set<ResponseBodyEncoder>} multibinding.
 * Ordering follows the {@link OrderedExtension} contract: phase first, then
 * {@link #priority()} ascending, then {@link #orderKey()} as a stable tie-break.
 * Framework defaults use priority 1000; application encoders at the default priority (0)
 * automatically take precedence.
 *
 * <p>Example: a custom XML encoder contributed in an application module:
 * <pre>{@code
 * @Provides @IntoSet
 * static ResponseBodyEncoder xmlEncoder(XmlMapper mapper) {
 *     return new XmlBodyEncoder(mapper);
 * }
 * }</pre>
 *
 * @see OrderedExtension
 */
public interface ResponseBodyEncoder extends OrderedExtension {

    /**
     * Returns {@code true} if this encoder can encode the given entity type
     * with the given content type.
     *
     * @param entityType  the runtime class of the response entity
     * @param contentType the effective Content-Type from the response, or {@code null}
     *                    if no explicit Content-Type was set
     * @return {@code true} if this encoder can handle the combination
     */
    boolean canEncode(Class<?> entityType, String contentType);

    /**
     * Encodes the entity into a {@link SerializedBody}.
     *
     * @param ctx      the current routing context
     * @param response the full JAX-RS response (for header inspection if needed)
     * @param entity   the non-null entity to encode
     * @return a {@link BufferedBody} or {@link StreamingBody}
     */
    SerializedBody encode(RoutingContext ctx, Response response, Object entity);
}
