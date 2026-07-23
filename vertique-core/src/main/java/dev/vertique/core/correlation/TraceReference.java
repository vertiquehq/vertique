// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * An immutable reference to a distributed trace, consisting of a mandatory trace ID, an optional
 * span ID, and a source label.
 *
 * <p>The {@code traceId} identifies the end-to-end trace (e.g. the W3C {@code traceparent} trace
 * identifier or a B3 trace ID). The {@code spanId} identifies the current span within that trace
 * and may be absent. The {@code source} labels the protocol or header from which the trace
 * reference was extracted (e.g. {@code "traceparent"}, {@code "b3"}).
 *
 * <p>Instances are safe to share across threads.
 *
 * @param traceId the trace identifier; must not be null or blank
 * @param spanId  the span identifier within the trace; may be {@code null}
 * @param source  the origin label; must not be null
 */
public record TraceReference(String traceId, @Nullable String spanId, String source) {

    /**
     * Constructs a {@link TraceReference}, validating the required components.
     *
     * @param traceId the trace identifier
     * @param spanId  the span identifier; may be {@code null}
     * @param source  the origin label
     * @throws NullPointerException     if {@code traceId} or {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code traceId} is blank
     */
    public TraceReference {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(source, "source");
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("TraceReference traceId must not be blank");
        }
    }
}
