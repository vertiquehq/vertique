// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import java.util.Map;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {\ dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * An opaque, immutable snapshot of all values bound in a Vert.x context-local slot at the moment
 * {@link ContextValues#snapshot()} was called.
 *
 * <p>Consumers obtain a snapshot via {@link ContextValues#snapshot()} and restore it on a
 * different (or the same) duplicated context via {@link ContextValues#bindSnapshot(ContextSnapshot)}.
 * The contents are intentionally opaque: there is no public accessor that returns the underlying
 * map, preventing callers from depending on the internal storage layout.
 *
 * <p><b>Deep-copy contract.</b> Framework-owned mutable values whose type has a registered
 * {@link dev.vertique.core.context.ContextValueAdapter} are deep-copied into an immutable form
 * when a snapshot is captured (the adapter's {@code snapshot(...)} method produces the frozen
 * form). {@link ContextValues#bindSnapshot(ContextSnapshot)} then calls
 * {@code restoreFromSnapshot(...)} on the same adapter to materialise a fresh live value when the
 * snapshot is installed. Subsequent mutation in the source or target context does not
 * retroactively affect the snapshot, nor does one bind-snapshot affect another. Values whose
 * type has no registered adapter are stored by reference (correct for truly immutable types).
 *
 * <p>This class is intentionally {@code final} and not a {@code record}: a record's structural
 * accessors would expose the internal map to the public API surface.
 */
public final class ContextSnapshot {

    // --- Singleton empty instance ---

    private static final ContextSnapshot EMPTY = new ContextSnapshot(Map.of());

    // --- State ---

    private final Map<String, Object> values;

    // --- Constructors ---

    /**
     * Package-private constructor. Only {@link DefaultContextHolder#snapshot()} creates instances.
     *
     * @param values the FQCN-keyed values to snapshot; copied immutably
     */
    ContextSnapshot(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    // --- Public API ---

    /**
     * Returns {@code true} if this snapshot contains no bindings.
     *
     * @return {@code true} when the snapshot is empty
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns a canonical empty snapshot. Calling {@link ContextValues#bindSnapshot(ContextSnapshot)}
     * with the empty snapshot is a no-op and does not require a duplicated Vert.x context.
     *
     * @return the shared empty snapshot instance
     */
    public static ContextSnapshot empty() {
        return EMPTY;
    }

    // --- Package-private accessor ---

    /**
     * Returns the raw FQCN-keyed value map. Package-private — only
     * {@link DefaultContextHolder} and {@link ContextValues} call this.
     *
     * @return the immutable internal map
     */
    Map<String, Object> values() {
        return values;
    }
}
