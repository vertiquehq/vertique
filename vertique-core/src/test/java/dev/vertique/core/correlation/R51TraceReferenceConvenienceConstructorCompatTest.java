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
 * R51 (trace-reference consolidation) — pins the exact 3-arg {@link TraceReference} construction
 * semantics that the contract requires the new convenience constructor to preserve verbatim once
 * {@code sampled}/{@code traceState} land as new trailing components on the canonical (5-arg)
 * constructor.
 *
 * <p>{@code TraceReferenceTest} already covers this exact 3-arg shape today (happy path, null
 * traceId/source NPE, blank traceId IAE) against what is currently the record's sole (canonical)
 * constructor; this class does not duplicate those assertions. What it adds is the R51-specific
 * framing: after the production change, {@code new TraceReference(traceId, spanId, source)} must
 * become a convenience constructor that delegates to the 5-arg canonical one with {@code
 * sampled=false} and {@code traceState=null} — defaults this class pins explicitly below — while
 * preserving every validation-failure case identically. Compiles and is green today (against the
 * current 3-arg canonical constructor); the fix lane's obligation is that it stays green,
 * unmodified, once that constructor becomes a convenience overload.
 */
class R51TraceReferenceConvenienceConstructorCompatTest {

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
    private static final String SOURCE = "traceparent";

    @Test
    @DisplayName("R51: the 3-arg construction site compiles and behaves identically to today "
            + "(source-compatibility proof for the future convenience constructor)")
    void threeArgConstructionSiteBehavesIdenticallyToToday() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE);
        assertEquals(VALID_TRACE_ID, ref.traceId());
        assertEquals(VALID_SPAN_ID, ref.spanId());
        assertEquals(SOURCE, ref.source());
    }

    @Test
    @DisplayName(
            "R51: the 3-arg construction site with a null spanId still compiles and behaves " + "identically to today")
    void threeArgConstructionSiteWithNullSpanIdBehavesIdenticallyToToday() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, null, SOURCE);
        assertEquals(VALID_TRACE_ID, ref.traceId());
        assertNull(ref.spanId());
        assertEquals(SOURCE, ref.source());
    }

    @Test
    @DisplayName("R51: the 3-arg construction site still rejects a blank traceId with IAE")
    void threeArgConstructionSiteStillRejectsBlankTraceId() {
        assertThrows(IllegalArgumentException.class, () -> new TraceReference("   ", VALID_SPAN_ID, SOURCE));
    }

    @Test
    @DisplayName("R51: the 3-arg construction site still rejects a null traceId with NPE")
    void threeArgConstructionSiteStillRejectsNullTraceId() {
        assertThrows(NullPointerException.class, () -> new TraceReference(null, VALID_SPAN_ID, SOURCE));
    }

    @Test
    @DisplayName("R51: the 3-arg construction site still rejects a null source with NPE")
    void threeArgConstructionSiteStillRejectsNullSource() {
        assertThrows(NullPointerException.class, () -> new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, null));
    }

    // --- R51 fix-lane block: uncommented now that TraceReference has gained the 5-arg canonical
    // constructor (sampled, traceState), with the former 3-arg one now a convenience overload.
    // Pins the exact defaults the convenience constructor applies, and the new traceState bounds —
    // mirroring the W3C limits the deleted dev.vertique.mcp.interceptor.McpTraceContext carried
    // (non-blank when present, <= 512 printable-ASCII characters).

    @Test
    @DisplayName("R51: the 3-arg convenience constructor defaults sampled=false and traceState=null")
    void threeArgConvenienceConstructorDefaultsNewFields() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE);
        assertFalse(ref.sampled());
        assertNull(ref.traceState());
    }

    @Test
    @DisplayName("R51: the 5-arg canonical constructor accepts sampled and a bounded traceState")
    void fiveArgCanonicalConstructorAcceptsSampledAndTraceState() {
        TraceReference ref = new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE, true, "vendor=value1");
        assertTrue(ref.sampled());
        assertEquals("vendor=value1", ref.traceState());
    }

    @Test
    @DisplayName("R51: an oversized traceState (>512 printable-ASCII chars) is rejected")
    void oversizedTraceStateRejected() {
        String oversized = "k=" + "v".repeat(600);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE, true, oversized));
    }

    @Test
    @DisplayName("R51: a blank (present but empty) traceState is rejected")
    void blankTraceStateRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TraceReference(VALID_TRACE_ID, VALID_SPAN_ID, SOURCE, true, "   "));
    }
}
