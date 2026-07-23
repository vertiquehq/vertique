// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * SPI for decoding a typed context value from the service-dispatch carrier map.
 *
 * <p>Registered via Dagger multibindings as {@code Set<ServiceDispatchContextDecoder<?>>}. At each
 * inbound service invocation, the invoker reads the raw value at {@link #key()} from the
 * dispatch-context map and calls {@link #decode} to produce the typed binding installed into
 * {@link ContextHolder}.
 *
 * <p>Implementations MUST NOT return {@code null} — a null return is an SPI contract violation
 * treated as a decode failure with a throttled WARN (FR-CTX-051).
 *
 * <p>Implementations MUST NOT block the Vert.x event loop (FR-CTX-049).
 *
 * @param <T> the context value type this decoder produces; must implement {@link ContextValue}
 */
public interface ServiceDispatchContextDecoder<T extends ContextValue> {

    /**
     * Returns the context type this decoder produces.
     *
     * @return the context type; never {@code null}
     */
    Class<T> type();

    /**
     * Returns the key under which the encoded value is looked up in the dispatch-context map.
     * Defaults to the type's fully qualified class name.
     *
     * @return the dispatch-context map key; never {@code null}
     */
    default String key() {
        return type().getName();
    }

    /**
     * Decodes the raw dispatch-context map value into a typed context value.
     *
     * @param value   the raw value from the dispatch-context map at {@link #key()}
     * @param context the decode context identifying the source boundary
     * @return the decode result; must not be {@code null}
     */
    ContextDecodeResult<T> decode(Object value, ServiceDispatchDecodeContext context);
}
