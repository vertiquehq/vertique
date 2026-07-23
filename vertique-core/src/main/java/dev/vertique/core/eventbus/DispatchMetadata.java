// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import java.util.Map;
import java.util.Optional;

/**
 * Typed dispatch context carried alongside a {@link DispatchEnvelope}.
 *
 * <p>The {@link #dispatchContext()} map is the single propagation channel, keyed by type FQCN.
 * Values include any context the caller chose to propagate —
 * {@code SecurityContext},
 * {@link dev.vertique.logging.DiagnosticContextSnapshot} (MDC), and
 * {@link dev.vertique.core.context.DurablePropagationMetadata} are examples, none of which are
 * special-cased carriers.
 *
 * <p>MDC entries ride inside the dispatch-context map under the key
 * {@code MDCContext.class.getName()}, encoded as a
 * {@link dev.vertique.logging.DiagnosticContextSnapshot} by the service-dispatch encoder
 * registered in {@code dev.vertique.logging.LoggingContextModule} (which uses
 * {@code dev.vertique.context.ServiceDispatchCodecs.snapshotEncoder/Decoder}), and decoded
 * back into a live {@link dev.vertique.logging.MDCContext} on the receive side.
 *
 * <p>The internal map is a real snapshot taken via {@link Map#copyOf}, not an unmodifiable view.
 * The local event-bus codec passes object references, so without a copy a caller mutating its
 * source map after dispatch would change the metadata observed by the receiver.
 */
public final class DispatchMetadata {

    private static final DispatchMetadata EMPTY = new DispatchMetadata(Map.of());

    private final Map<String, Object> dispatchContext;

    private DispatchMetadata(Map<String, Object> dispatchContext) {
        this.dispatchContext = dispatchContext;
    }

    // --- Factory Methods ---

    /**
     * Returns an empty metadata instance (no dispatch context).
     *
     * @return the shared empty instance
     */
    public static DispatchMetadata empty() {
        return EMPTY;
    }

    /**
     * Creates a metadata instance with a snapshot of the given dispatch-context map.
     *
     * <p>The caller map is copied via {@link Map#copyOf} so subsequent caller mutations do not
     * affect the metadata. A {@code null} map is treated as empty. Keys and values must be
     * non-null; passing a map with a null key or value throws {@link NullPointerException} as
     * {@link Map#copyOf} would.
     *
     * @param dispatchContext the dispatch-context map keyed by type FQCN, or {@code null}
     * @return the metadata instance
     */
    public static DispatchMetadata of(Map<String, Object> dispatchContext) {
        if (dispatchContext == null || dispatchContext.isEmpty()) {
            return EMPTY;
        }
        return new DispatchMetadata(Map.copyOf(dispatchContext));
    }

    // --- Accessors ---

    /**
     * Returns the dispatch-context map keyed by type FQCN (never {@code null}).
     *
     * @return the dispatch-context map
     */
    public Map<String, Object> dispatchContext() {
        return dispatchContext;
    }

    /**
     * Returns the typed context value stored under {@code type.getName()}, or empty if absent
     * or of incompatible type.
     *
     * @param type the context value type
     * @param <C>  the context type
     * @return the context value, or empty
     */
    @SuppressWarnings("unchecked")
    public <C> Optional<C> context(Class<C> type) {
        Object value = dispatchContext.get(type.getName());
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of((C) value);
    }
}
