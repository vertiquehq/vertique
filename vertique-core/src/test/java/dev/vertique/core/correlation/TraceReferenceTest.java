// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TraceReference}.
 *
 * <p>Verifies: happy path with both spanId variants (null and present); null traceId/source
 * rejects with NPE; blank traceId rejects with IAE; null source rejects with NPE.
 */
class TraceReferenceTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs with traceId, spanId, and source")
    void happyPathWithSpanId() {
        TraceReference ref = new TraceReference("trace-abc", "span-xyz", "b3-header");
        assertEquals("trace-abc", ref.traceId());
        assertEquals("span-xyz", ref.spanId());
        assertEquals("b3-header", ref.source());
    }

    @Test
    @DisplayName("constructs with null spanId")
    void happyPathWithNullSpanId() {
        TraceReference ref = new TraceReference("trace-abc", null, "w3c-header");
        assertEquals("trace-abc", ref.traceId());
        assertNull(ref.spanId());
        assertEquals("w3c-header", ref.source());
    }

    // --- null rejection ---

    @Test
    @DisplayName("null traceId throws NullPointerException")
    void nullTraceIdThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new TraceReference(null, "span-1", "source"));
    }

    @Test
    @DisplayName("null source throws NullPointerException")
    void nullSourceThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new TraceReference("trace-1", null, null));
    }

    // --- blank rejection ---

    @Test
    @DisplayName("blank traceId throws IllegalArgumentException")
    void blankTraceIdThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new TraceReference("   ", null, "source"));
    }

    @Test
    @DisplayName("empty traceId throws IllegalArgumentException")
    void emptyTraceIdThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new TraceReference("", null, "source"));
    }
}
