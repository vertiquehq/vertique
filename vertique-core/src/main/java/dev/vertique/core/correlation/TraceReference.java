// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * An immutable reference to a distributed trace, consisting of a mandatory trace ID, an optional
 * span ID, a source label, and the W3C sampling/vendor-state facts carried alongside them.
 *
 * <p>The {@code traceId} identifies the end-to-end trace (e.g. the W3C {@code traceparent} trace
 * identifier or a B3 trace ID). The {@code spanId} identifies the current span within that trace
 * and may be absent. The {@code source} labels the protocol or header from which the trace
 * reference was extracted (e.g. {@code "traceparent"}, {@code "b3"}). {@code sampled} mirrors the
 * W3C {@code traceparent} sampled flag. {@code traceState} carries the optional, bounded W3C
 * {@code tracestate} vendor-specific state string, when present.
 *
 * <p>Instances are safe to share across threads.
 *
 * <p>{@code sampled} and {@code traceState}
 * are trailing components on the canonical (5-arg) constructor. This is the framework's single
 * trace-reference type. {@link #TraceReference(String, String, String)} remains a source-compatible
 * convenience constructor for the three released construction sites, defaulting {@code
 * sampled=false} and {@code traceState=null}.
 *
 * @param traceId    the trace identifier; must not be null or blank
 * @param spanId     the span identifier within the trace; may be {@code null}
 * @param source     the origin label; must not be null
 * @param sampled    the W3C {@code traceparent} sampled flag
 * @param traceState the optional, bounded W3C {@code tracestate} string; may be {@code null}
 */
public record TraceReference(
        String traceId,
        @Nullable String spanId,
        String source,
        boolean sampled,
        @Nullable String traceState) {

    /** The maximum accepted length of a present {@code traceState}, per the W3C {@code tracestate} bound. */
    private static final int MAX_TRACE_STATE_CHARS = 512;

    private static final int PRINTABLE_ASCII_MINIMUM = 0x20;
    private static final int PRINTABLE_ASCII_MAXIMUM = 0x7E;

    /**
     * Constructs a {@link TraceReference}, validating the required components and the bounded
     * optional {@code traceState}.
     *
     * @param traceId    the trace identifier
     * @param spanId     the span identifier; may be {@code null}
     * @param source     the origin label
     * @param sampled    the W3C {@code traceparent} sampled flag
     * @param traceState the optional, bounded W3C {@code tracestate} string; may be {@code null}
     * @throws NullPointerException     if {@code traceId} or {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code traceId} is blank, or {@code traceState} is
     *     present and blank, exceeds {@value #MAX_TRACE_STATE_CHARS} characters, or contains a
     *     character outside the printable-ASCII range (0x20-0x7E)
     */
    public TraceReference {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(source, "source");
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("TraceReference traceId must not be blank");
        }
        validateTraceState(traceState);
    }

    /**
     * Convenience constructor preserving the three released construction sites' exact
     * source-compatible shape: delegates to the canonical constructor with {@code sampled=false}
     * and {@code traceState=null}.
     *
     * @param traceId the trace identifier
     * @param spanId  the span identifier; may be {@code null}
     * @param source  the origin label
     * @throws NullPointerException     if {@code traceId} or {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code traceId} is blank
     */
    public TraceReference(String traceId, @Nullable String spanId, String source) {
        this(traceId, spanId, source, false, null);
    }

    private static void validateTraceState(@Nullable String traceState) {
        if (traceState == null) {
            return;
        }
        if (traceState.isBlank()) {
            throw new IllegalArgumentException("TraceReference traceState must not be blank when present");
        }
        if (traceState.length() > MAX_TRACE_STATE_CHARS) {
            throw new IllegalArgumentException(
                    "TraceReference traceState must not exceed " + MAX_TRACE_STATE_CHARS + " characters");
        }
        if (traceState.chars().anyMatch(character -> !isPrintableAscii(character))) {
            throw new IllegalArgumentException(
                    "TraceReference traceState must contain only printable ASCII characters");
        }
    }

    private static boolean isPrintableAscii(int character) {
        return character >= PRINTABLE_ASCII_MINIMUM && character <= PRINTABLE_ASCII_MAXIMUM;
    }
}
