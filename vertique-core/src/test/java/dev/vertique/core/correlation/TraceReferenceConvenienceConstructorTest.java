// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the 3-arg {@link TraceReference} construction semantics: {@code new TraceReference(traceId, spanId,
 * source)} is a convenience constructor that delegates to the 5-arg canonical constructor with
 * {@code sampled=false} and {@code traceState=null}, and preserves every validation failure of the
 * canonical constructor.
 *
 * <p>{@code TraceReferenceTest} covers the happy path and the null/blank rejections of the 3-arg shape;
 * this class pins the defaults the convenience constructor applies and the 5-arg {@code traceState}
 * bounds.
 */
class TraceReferenceConvenienceConstructorTest {

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
    private static final String SOURCE = "traceparent";

    @Test
    @DisplayName("the 3-arg construction site populates traceId, spanId and source")
    void threeArgConstructionSitePopulatesComponents() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE);
        assertEquals(VALID_TRACE_ID, ref.traceId());
        assertEquals(VALID_SPAN_ID, ref.spanId());
        assertEquals(SOURCE, ref.source());
    }

    @Test
    @DisplayName("the 3-arg construction site accepts a null spanId")
    void threeArgConstructionSiteAcceptsNullSpanId() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, null, SOURCE);
        assertEquals(VALID_TRACE_ID, ref.traceId());
        assertNull(ref.spanId());
        assertEquals(SOURCE, ref.source());
    }

    @Test
    @DisplayName("the 3-arg construction site rejects a blank traceId with IAE")
    void threeArgConstructionSiteRejectsBlankTraceId() {
        assertThrows(IllegalArgumentException.class, () -> new TraceReference("   ", VALID_SPAN_ID, SOURCE));
    }

    @Test
    @DisplayName("the 3-arg construction site rejects a null traceId with NPE")
    void threeArgConstructionSiteRejectsNullTraceId() {
        assertThrows(NullPointerException.class, () -> new TraceReference(null, VALID_SPAN_ID, SOURCE));
    }

    @Test
    @DisplayName("the 3-arg construction site rejects a null source with NPE")
    void threeArgConstructionSiteRejectsNullSource() {
        assertThrows(NullPointerException.class, () -> new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, null));
    }

    // --- defaults and traceState bounds ---
    // The 3-arg convenience constructor applies sampled=false and traceState=null. The traceState bounds
    // mirror the W3C limits (non-blank when present, <= 512 printable-ASCII characters).

    @Test
    @DisplayName("the 3-arg convenience constructor defaults sampled=false and traceState=null")
    void threeArgConvenienceConstructorDefaultsSampledAndTraceState() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE);
        assertFalse(ref.sampled());
        assertNull(ref.traceState());
    }

    @Test
    @DisplayName("the 5-arg canonical constructor accepts sampled and a bounded traceState")
    void fiveArgCanonicalConstructorAcceptsSampledAndTraceState() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE, true, "vendor=value1");
        assertTrue(ref.sampled());
        assertEquals("vendor=value1", ref.traceState());
    }

    @Test
    @DisplayName("an oversized traceState (>512 printable-ASCII chars) is rejected")
    void oversizedTraceStateRejected() {
        String oversized = "k=" + "v".repeat(600);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE, true, oversized));
    }

    @Test
    @DisplayName("a blank (present but empty) traceState is rejected")
    void blankTraceStateRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE, true, "   "));
    }
}
