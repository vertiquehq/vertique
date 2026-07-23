// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * SPI for encoding a typed context value into the service-dispatch carrier map.
 *
 * <p>Registered via Dagger multibindings as {@code Set<ServiceDispatchContextEncoder<?>>}. At each
 * outbound service call, the {@link ServiceDispatchContextCapturer} reads the current binding for
 * {@link #type()} from {@link ContextHolder} and, if present, calls {@link #encode} to produce the
 * wire value stored under {@link #key()} in the dispatch-context map.
 *
 * <p>Implementations MUST NOT return {@code null} from {@link #encode} — a null return is an SPI
 * contract violation that fails the outgoing service call (FR-CTX-050).
 *
 * <p>Implementations MUST NOT block the Vert.x event loop (FR-CTX-049).
 *
 * @param <T> the context value type this encoder handles; must implement {@link ContextValue}
 */
public interface ServiceDispatchContextEncoder<T extends ContextValue> {

    /**
     * Returns the context type this encoder handles.
     *
     * @return the context type; never {@code null}
     */
    Class<T> type();

    /**
     * Returns the key under which the encoded value is stored in the dispatch-context map.
     * Defaults to the type's fully qualified class name.
     *
     * @return the dispatch-context map key; never {@code null}
     */
    default String key() {
        return type().getName();
    }

    /**
     * Encodes the given context value for inclusion in the outgoing dispatch-context map.
     *
     * @param value   the currently bound context value; never {@code null}
     * @param context the encode context identifying the dispatch boundary
     * @return the encoded value to store under {@link #key()}; must not be {@code null}
     */
    Object encode(T value, ServiceDispatchEncodeContext context);
}
