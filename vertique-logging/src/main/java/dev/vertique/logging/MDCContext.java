// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import dev.vertique.context.ContextSnapshot;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.ServiceDispatchCodecs;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import java.util.HashMap;
import java.util.Map;

/**
 * Package-private per-request MDC storage, held in a single {@link ContextHolder} slot under the
 * {@code MDCContext.class.getName()} key. The sole public mutator surface is {@link MDCContexts};
 * this class provides only the primitive operations that {@code MDCContexts} needs.
 *
 * <p>Instances are mutable; all mutation must happen on a Vert.x duplicated context so that
 * concurrent dispatches do not observe each other's MDC state.
 */
final class MDCContext implements ContextValue {

    // --- Storage ---

    private final Map<String, String> map;

    // --- Constructors ---

    /** Creates an empty MDC context. */
    MDCContext() {
        this.map = new HashMap<>();
    }

    /**
     * Creates an MDC context pre-populated with the given entries. Defensively copies to ensure
     * isolation from the caller (used during snapshot rebind).
     *
     * @param initialEntries entries to copy into the new context; must not be {@code null}
     */
    MDCContext(Map<String, String> initialEntries) {
        this.map = new HashMap<>(initialEntries);
    }

    // --- Read operations ---

    /**
     * Returns an immutable snapshot of the current entries. Used by
     * {@link DefaultContextHolder#snapshot()} to capture the MDC state for a
     * {@link ContextSnapshot} without exposing the live map.
     *
     * @return an immutable copy of the current MDC entries
     */
    Map<String, String> mapView() {
        return Map.copyOf(map);
    }

    /**
     * Returns the value bound to the given key, or {@code null} if absent.
     *
     * @param key the MDC key
     * @return the current value, or {@code null}
     */
    String get(String key) {
        return map.get(key);
    }

    /**
     * Returns {@code true} if no entries are currently bound.
     *
     * @return {@code true} when the internal map is empty
     */
    boolean isEmpty() {
        return map.isEmpty();
    }

    // --- Write operations ---

    /**
     * Associates the given key with the given value.
     *
     * @param key   the MDC key; must not be {@code null}
     * @param value the value to associate; must not be {@code null}
     */
    void put(String key, String value) {
        map.put(key, value);
    }

    /**
     * Copies all entries from the given map into this context.
     *
     * @param entries entries to copy in; must not be {@code null}
     */
    void putAll(Map<String, String> entries) {
        map.putAll(entries);
    }

    /**
     * Removes the entry for the given key.
     *
     * @param key the MDC key to remove
     */
    void remove(String key) {
        map.remove(key);
    }

    /** Removes all entries from this context. */
    void clear() {
        map.clear();
    }

    /**
     * Returns an immutable snapshot of all current entries, suitable for propagation (e.g., into a
     * service dispatch envelope).
     *
     * @return immutable copy of current entries
     */
    Map<String, String> copy() {
        return Map.copyOf(map);
    }

    // --- Static factories ---

    /**
     * Materialises a fresh {@link MDCContext} from a {@link DiagnosticContextSnapshot}.
     * Used by the snapshot-decoder factory in {@link ServiceDispatchCodecs} so all snapshot/restore
     * pairs go through one shared implementation.
     *
     * @param snapshot the diagnostic context snapshot; must not be {@code null}
     * @return a new MDCContext containing a defensive copy of the snapshot's entries
     */
    static MDCContext fromSnapshot(DiagnosticContextSnapshot snapshot) {
        return new MDCContext(snapshot.entries());
    }
}
