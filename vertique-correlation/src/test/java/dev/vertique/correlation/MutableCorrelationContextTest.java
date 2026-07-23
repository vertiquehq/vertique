// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MutableCorrelationContext}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Constructor builds with requestId + correlationId, other fields null/empty.</li>
 *   <li>Setters mutate fields correctly.</li>
 *   <li>{@code addProtocolCorrelation} appends; null is rejected.</li>
 *   <li>{@code protocolCorrelations()} returns an immutable view.</li>
 *   <li>{@code attributes()} returns an immutable view.</li>
 *   <li>{@code snapshot()} produces an independent immutable snapshot.</li>
 *   <li>{@code fromSnapshot} rebuilds a mutable instance whose snapshot equals the original.</li>
 *   <li>Nullable setters accept null, clearing the field.</li>
 * </ul>
 */
class MutableCorrelationContextTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "test");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "test");

    // --- construction ---

    @Test
    @DisplayName("constructor sets requestId and correlationId; other fields are null or empty")
    void constructorInitialisesRequiredFields() {
        MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
        assertEquals(REQ_ID, ctx.requestId());
        assertEquals(CORR_ID, ctx.correlationId());
        assertNull(ctx.causationId());
        assertNull(ctx.trace());
        assertNull(ctx.session());
        assertTrue(ctx.protocolCorrelations().isEmpty());
        assertTrue(ctx.attributes().isEmpty());
    }

    // --- setters ---

    @Nested
    @DisplayName("setters")
    class Setters {

        @Test
        @DisplayName("setCausationId stores the value")
        void setCausationId() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            CorrelationIdentifier causation = new CorrelationIdentifier("cause-1", "upstream");
            ctx.setCausationId(causation);
            assertEquals(causation, ctx.causationId());
        }

        @Test
        @DisplayName("setCausationId(null) clears the field")
        void setCausationIdNull() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            ctx.setCausationId(new CorrelationIdentifier("cause-1", "upstream"));
            ctx.setCausationId(null);
            assertNull(ctx.causationId());
        }

        @Test
        @DisplayName("setTrace stores the value")
        void setTrace() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            TraceReference trace = new TraceReference("trace-abc", "span-def", "traceparent");
            ctx.setTrace(trace);
            assertEquals(trace, ctx.trace());
        }

        @Test
        @DisplayName("setTrace(null) clears the field")
        void setTraceNull() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            ctx.setTrace(new TraceReference("trace-abc", null, "b3"));
            ctx.setTrace(null);
            assertNull(ctx.trace());
        }

        @Test
        @DisplayName("setSession stores the value")
        void setSession() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            CorrelationSessionRef session =
                    new CorrelationSessionRef("session-id", "jwt", "auth-filter", "jti", false, null);
            ctx.setSession(session);
            assertEquals(session, ctx.session());
        }

        @Test
        @DisplayName("setSession(null) clears the field")
        void setSessionNull() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            ctx.setSession(new CorrelationSessionRef("session-id", "jwt", "auth-filter", null, false, null));
            ctx.setSession(null);
            assertNull(ctx.session());
        }

        @Test
        @DisplayName("putAttribute stores the key/value pair")
        void putAttribute() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            ctx.putAttribute("key1", "value1");
            assertEquals("value1", ctx.attributes().get("key1"));
        }
    }

    // --- addProtocolCorrelation ---

    @Nested
    @DisplayName("addProtocolCorrelation")
    class AddProtocolCorrelation {

        @Test
        @DisplayName("appends to the list")
        void appendsToList() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                    "X-Request-Id",
                    "abc123",
                    "http",
                    CorrelationResponseMode.ECHO_SAME_HEADER,
                    CorrelationPropagationMode.PROPAGATE_SAME_HEADER,
                    true,
                    null);
            ctx.addProtocolCorrelation(ref);
            assertEquals(1, ctx.protocolCorrelations().size());
            assertEquals(ref, ctx.protocolCorrelations().get(0));
        }

        @Test
        @DisplayName("null ref is rejected with NullPointerException")
        void nullRejected() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            assertThrows(NullPointerException.class, () -> ctx.addProtocolCorrelation(null));
        }
    }

    // --- immutable views ---

    @Nested
    @DisplayName("immutable views")
    class ImmutableViews {

        @Test
        @DisplayName("protocolCorrelations() returns an unmodifiable list")
        void protocolCorrelationsIsUnmodifiable() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            List<dev.vertique.core.correlation.ProtocolCorrelationRef> view = ctx.protocolCorrelations();
            assertThrows(UnsupportedOperationException.class, () -> view.add(null));
        }

        @Test
        @DisplayName("attributes() returns an unmodifiable map")
        void attributesIsUnmodifiable() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            java.util.Map<String, String> view = ctx.attributes();
            assertThrows(UnsupportedOperationException.class, () -> view.put("k", "v"));
        }
    }

    // --- snapshot ---

    @Nested
    @DisplayName("snapshot()")
    class Snapshot {

        @Test
        @DisplayName("snapshot captures current state as an independent immutable snapshot")
        void snapshotIsIndependent() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            ctx.putAttribute("key1", "value1");
            CorrelationContextSnapshot snap1 = ctx.snapshot();

            // mutate after snapshot
            ctx.putAttribute("key2", "value2");
            CorrelationContextSnapshot snap2 = ctx.snapshot();

            // snap1 should not reflect the later mutation
            assertTrue(snap1.attributes().containsKey("key1"));
            assertTrue(snap1.attributes().size() == 1);
            assertTrue(snap2.attributes().containsKey("key1"));
            assertTrue(snap2.attributes().containsKey("key2"));
        }

        @Test
        @DisplayName("snapshot carries requestId, correlationId, causationId, trace, session")
        void snapshotCarriesAllFields() {
            MutableCorrelationContext ctx = new MutableCorrelationContext(REQ_ID, CORR_ID);
            CorrelationIdentifier causation = new CorrelationIdentifier("cause-1", "upstream");
            TraceReference trace = new TraceReference("trace-abc", "span-def", "traceparent");
            CorrelationSessionRef session =
                    new CorrelationSessionRef("session-id", "jwt", "auth-filter", null, false, null);
            ctx.setCausationId(causation);
            ctx.setTrace(trace);
            ctx.setSession(session);

            CorrelationContextSnapshot snap = ctx.snapshot();
            assertEquals(REQ_ID, snap.requestId());
            assertEquals(CORR_ID, snap.correlationId());
            assertEquals(causation, snap.causationId());
            assertEquals(trace, snap.trace());
            assertEquals(session, snap.session());
        }
    }

    // --- fromSnapshot ---

    @Nested
    @DisplayName("fromSnapshot()")
    class FromSnapshot {

        @Test
        @DisplayName("rebuilds a mutable instance whose snapshot equals the original")
        void rebuildsFromSnapshot() {
            MutableCorrelationContext original = new MutableCorrelationContext(REQ_ID, CORR_ID);
            original.setCausationId(new CorrelationIdentifier("cause-1", "upstream"));
            original.setTrace(new TraceReference("trace-abc", "span-def", "traceparent"));
            original.putAttribute("key1", "value1");
            CorrelationContextSnapshot originalSnap = original.snapshot();

            MutableCorrelationContext rebuilt = MutableCorrelationContext.fromSnapshot(originalSnap);
            assertNotNull(rebuilt);
            assertNotSame(original, rebuilt);
            assertEquals(originalSnap, rebuilt.snapshot());
        }

        @Test
        @DisplayName("rebuilt instance is independently mutable")
        void rebuiltIsIndependentlyMutable() {
            MutableCorrelationContext original = new MutableCorrelationContext(REQ_ID, CORR_ID);
            CorrelationContextSnapshot snap = original.snapshot();

            MutableCorrelationContext rebuilt = MutableCorrelationContext.fromSnapshot(snap);
            rebuilt.putAttribute("new-key", "new-value");

            // original snap should be unaffected
            assertTrue(snap.attributes().isEmpty());
            assertEquals("new-value", rebuilt.attributes().get("new-key"));
        }
    }
}
