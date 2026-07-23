// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationContextSnapshot}.
 *
 * <p>Verifies: happy-path construction with all fields; minimal construction via
 * {@link CorrelationContextSnapshot#of(CorrelationIdentifier, CorrelationIdentifier)};
 * null requestId/correlationId rejection; defensive copies for protocolCorrelations and
 * attributes; returned collections are immutable; equals/hashCode correctness.
 */
class CorrelationContextSnapshotTest {

    private static final CorrelationIdentifier REQUEST_ID = new CorrelationIdentifier("req-1", "header");
    private static final CorrelationIdentifier CORRELATION_ID = new CorrelationIdentifier("corr-1", "header");
    private static final CorrelationIdentifier CAUSATION_ID = new CorrelationIdentifier("cause-1", "header");
    private static final TraceReference TRACE = new TraceReference("trace-abc", "span-xyz", "b3");

    private static ProtocolCorrelationRef makeRef() {
        return new ProtocolCorrelationRef(
                "X-Request-Id",
                "req-val-1",
                "ingress",
                CorrelationResponseMode.ECHO_SAME_HEADER,
                CorrelationPropagationMode.PROPAGATE_SAME_HEADER,
                true,
                Map.of());
    }

    // --- happy path (all fields) ---

    @Test
    @DisplayName("constructs snapshot with all fields populated")
    void happyPathAllFields() {
        CorrelationSessionRef session = new CorrelationSessionRef("sess-1", "jwt", "auth", "jti", false, Map.of());
        CorrelationContextSnapshot snap = new CorrelationContextSnapshot(
                REQUEST_ID, CORRELATION_ID, CAUSATION_ID, TRACE, List.of(makeRef()), session, Map.of("tenant", "acme"));

        assertEquals(REQUEST_ID, snap.requestId());
        assertEquals(CORRELATION_ID, snap.correlationId());
        assertEquals(CAUSATION_ID, snap.causationId());
        assertEquals(TRACE, snap.trace());
        assertEquals(1, snap.protocolCorrelations().size());
        assertNotNull(snap.session());
        assertEquals("acme", snap.attributes().get("tenant"));
    }

    // --- minimal factory ---

    @Test
    @DisplayName("of(requestId, correlationId) creates minimal snapshot with no optional fields")
    void minimalFactoryOfMethod() {
        CorrelationContextSnapshot snap = CorrelationContextSnapshot.of(REQUEST_ID, CORRELATION_ID);
        assertEquals(REQUEST_ID, snap.requestId());
        assertEquals(CORRELATION_ID, snap.correlationId());
        assertNull(snap.causationId());
        assertNull(snap.trace());
        assertTrue(snap.protocolCorrelations().isEmpty());
        assertNull(snap.session());
        assertTrue(snap.attributes().isEmpty());
    }

    // --- null rejection ---

    @Test
    @DisplayName("null requestId throws NullPointerException")
    void nullRequestIdThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new CorrelationContextSnapshot(null, CORRELATION_ID, null, null, null, null, null));
    }

    @Test
    @DisplayName("null correlationId throws NullPointerException")
    void nullCorrelationIdThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new CorrelationContextSnapshot(REQUEST_ID, null, null, null, null, null, null));
    }

    // --- null protocolCorrelations treated as empty ---

    @Test
    @DisplayName("null protocolCorrelations is treated as empty list")
    void nullProtocolCorrelationsTreatedAsEmpty() {
        CorrelationContextSnapshot snap =
                new CorrelationContextSnapshot(REQUEST_ID, CORRELATION_ID, null, null, null, null, null);
        assertTrue(snap.protocolCorrelations().isEmpty());
    }

    // --- null attributes treated as empty ---

    @Test
    @DisplayName("null attributes map is treated as empty")
    void nullAttributesTreatedAsEmpty() {
        CorrelationContextSnapshot snap =
                new CorrelationContextSnapshot(REQUEST_ID, CORRELATION_ID, null, null, List.of(), null, null);
        assertTrue(snap.attributes().isEmpty());
    }

    // --- defensive copies ---

    @Test
    @DisplayName("mutating input protocolCorrelations list after construction does not affect snapshot")
    void defensiveCopyOfProtocolCorrelations() {
        List<ProtocolCorrelationRef> mutable = new ArrayList<>();
        mutable.add(makeRef());
        CorrelationContextSnapshot snap =
                new CorrelationContextSnapshot(REQUEST_ID, CORRELATION_ID, null, null, mutable, null, Map.of());
        mutable.add(makeRef());
        assertEquals(1, snap.protocolCorrelations().size(), "List must not reflect mutation of input");
    }

    @Test
    @DisplayName("mutating input attributes map after construction does not affect snapshot")
    void defensiveCopyOfAttributes() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("k", "v");
        CorrelationContextSnapshot snap =
                new CorrelationContextSnapshot(REQUEST_ID, CORRELATION_ID, null, null, List.of(), null, mutable);
        mutable.put("extra", "injected");
        assertEquals(1, snap.attributes().size(), "Attributes must not reflect mutation of input map");
    }

    // --- immutable collections ---

    @Test
    @DisplayName("protocolCorrelations accessor returns unmodifiable list")
    void protocolCorrelationsIsUnmodifiable() {
        CorrelationContextSnapshot snap =
                new CorrelationContextSnapshot(REQUEST_ID, CORRELATION_ID, null, null, List.of(makeRef()), null, null);
        assertThrows(UnsupportedOperationException.class, () -> snap.protocolCorrelations()
                .add(makeRef()));
    }

    @Test
    @DisplayName("attributes accessor returns unmodifiable map")
    void attributesIsUnmodifiable() {
        CorrelationContextSnapshot snap = new CorrelationContextSnapshot(
                REQUEST_ID, CORRELATION_ID, null, null, List.of(), null, Map.of("k", "v"));
        assertThrows(
                UnsupportedOperationException.class, () -> snap.attributes().put("x", "y"));
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("snapshots with same fields are equal")
    void equalityHolds() {
        CorrelationContextSnapshot a = CorrelationContextSnapshot.of(REQUEST_ID, CORRELATION_ID);
        CorrelationContextSnapshot b = CorrelationContextSnapshot.of(REQUEST_ID, CORRELATION_ID);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("snapshots with different requestId are not equal")
    void inequalityOnRequestId() {
        CorrelationContextSnapshot a = CorrelationContextSnapshot.of(REQUEST_ID, CORRELATION_ID);
        CorrelationContextSnapshot b =
                CorrelationContextSnapshot.of(new CorrelationIdentifier("req-2", "header"), CORRELATION_ID);
        assertTrue(!a.equals(b));
    }
}
