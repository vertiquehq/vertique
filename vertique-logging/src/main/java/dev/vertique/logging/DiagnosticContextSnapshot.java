// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import java.util.Map;

/**
 * Immutable snapshot of MDC entries propagated across service-dispatch envelopes.
 *
 * <p>Instances are produced by the MDC service-dispatch encoder registered in
 * {@code LoggingContextModule} (via {@code ServiceDispatchCodecs.snapshotEncoder}) at send time,
 * and consumed by the matching decoder at receive time. They travel inside the
 * {@code dispatchContext} FQCN map of
 * {@link dev.vertique.core.eventbus.DispatchMetadata} under the key
 * {@code MDCContext.class.getName()}.
 *
 * <p>The entries map is never {@code null}; a {@code null} constructor argument is silently
 * converted to an empty map.
 *
 * @param entries the MDC key-value pairs captured at dispatch time; never {@code null}
 */
public record DiagnosticContextSnapshot(Map<String, String> entries) {

    /**
     * Canonical constructor — defensively copies the supplied map and normalises {@code null} to
     * empty so callers never have to guard for {@code null}.
     *
     * @param entries the MDC entries; may be {@code null} (treated as empty)
     */
    public DiagnosticContextSnapshot {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    // --- Factory Methods ---

    /**
     * Returns a snapshot with no entries.
     *
     * @return the empty snapshot instance
     */
    public static DiagnosticContextSnapshot empty() {
        return new DiagnosticContextSnapshot(Map.of());
    }

    // --- Queries ---

    /**
     * Returns {@code true} when this snapshot carries no MDC entries.
     *
     * @return {@code true} if {@link #entries()} is empty
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
