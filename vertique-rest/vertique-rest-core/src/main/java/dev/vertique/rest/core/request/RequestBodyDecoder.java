// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Type;

/**
 * SPI for decoding a request body from the wire to a Java object.
 *
 * <p>Implementations handle specific target types and/or media types. The framework
 * invoker selects the first matching decoder from a priority-sorted list and delegates
 * body decoding to it.
 *
 * <p>Decoders are contributed via Dagger {@code Set<RequestBodyDecoder>} multibinding.
 * Ordering follows the {@link OrderedExtension} contract: phase first, then
 * {@link #priority()} ascending, then {@link #orderKey()} as a stable tie-break.
 * Framework defaults use priority 1000; application decoders at the default priority (0)
 * automatically take precedence.
 *
 * <p>Example: a custom XML decoder contributed in an application module:
 * <pre>{@code
 * @Provides @IntoSet
 * static RequestBodyDecoder xmlDecoder(XmlMapper mapper) {
 *     return new XmlRequestBodyDecoder(mapper);
 * }
 * }</pre>
 *
 * @see OrderedExtension
 */
public interface RequestBodyDecoder extends OrderedExtension {

    /**
     * Returns {@code true} if this decoder can decode a request body into the given target
     * type from the given content type.
     *
     * @param targetType  the desired Java type for the decoded body
     * @param contentType the {@code Content-Type} header value from the request, or {@code null}
     *                    if not present
     * @return {@code true} if this decoder can handle the combination
     */
    boolean canDecode(Class<?> targetType, String contentType);

    /**
     * Decodes the request body into the target Java type.
     *
     * <p>Implementations that do not need generic type information may override
     * {@link #decode(RoutingContext, RequestValue, Class)} instead — the default
     * implementation of this method delegates to it.
     *
     * @param ctx         the current routing context (for header and form attribute access)
     * @param body        the request body value
     * @param targetType  the raw Java class for the result (e.g. {@code List.class})
     * @param genericType the full generic type including type parameters (e.g. {@code List<MyPojo>}),
     *                    or {@code null} if generic info is unavailable
     * @return the decoded body value, or {@code null} if the body is absent or empty
     */
    default Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType, Type genericType) {
        return decode(ctx, body, targetType);
    }

    /**
     * Decodes the request body into the target Java type (without generic type info).
     *
     * <p>Convenience method for decoders that do not need the full generic type.
     * Override this method for simple decoders; override
     * {@link #decode(RoutingContext, RequestValue, Class, Type)} for decoders
     * that need generic type information (e.g. {@code List<T>} mapping).
     *
     * @param ctx        the current routing context (for header and form attribute access)
     * @param body       the request body value
     * @param targetType the desired Java type for the result
     * @return the decoded body value, or {@code null} if the body is absent or empty
     */
    default Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
        throw new UnsupportedOperationException(
                "Override decode(ctx, body, targetType) or decode(ctx, body, targetType, genericType)");
    }
}
