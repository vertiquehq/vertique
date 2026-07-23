// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UnboundCorrelationContext}.
 *
 * <p>Verifies: sentinel identifiers carry the documented marker value {@code "unavailable"};
 * nullable fields return {@code null}; collection fields return empty; {@code snapshot()} succeeds;
 * the class implements {@link CorrelationContext}. The proof that an {@code AuthorizationDecisionEvent}
 * can be constructed with this sentinel lives with the security module (the event is no longer a core
 * type), so {@code vertique-core} keeps no test dependency on a security type.
 */
class UnboundCorrelationContextTest {

    private static final UnboundCorrelationContext SENTINEL = UnboundCorrelationContext.INSTANCE;

    // --- sentinel identifier values ---

    @Test
    @DisplayName("requestId returns non-null CorrelationIdentifier with value 'unavailable'")
    void requestId_returnsSentinelIdentifier() {
        CorrelationIdentifier id = SENTINEL.requestId();

        assertNotNull(id);
        assertEquals("unavailable", id.value());
    }

    @Test
    @DisplayName("correlationId returns non-null CorrelationIdentifier with value 'unavailable'")
    void correlationId_returnsSentinelIdentifier() {
        CorrelationIdentifier id = SENTINEL.correlationId();

        assertNotNull(id);
        assertEquals("unavailable", id.value());
    }

    // --- nullable fields ---

    @Test
    @DisplayName("causationId, trace, and session return null")
    void nullableFields_returnNull() {
        assertNull(SENTINEL.causationId());
        assertNull(SENTINEL.trace());
        assertNull(SENTINEL.session());
    }

    // --- collection fields ---

    @Test
    @DisplayName("protocolCorrelations returns empty list")
    void protocolCorrelations_returnsEmpty() {
        assertNotNull(SENTINEL.protocolCorrelations());
        assertEquals(0, SENTINEL.protocolCorrelations().size());
    }

    @Test
    @DisplayName("attributes returns empty map")
    void attributes_returnsEmpty() {
        assertNotNull(SENTINEL.attributes());
        assertEquals(0, SENTINEL.attributes().size());
    }

    // --- snapshot ---

    @Test
    @DisplayName("snapshot returns a valid CorrelationContextSnapshot with sentinel ids")
    void snapshot_returnsValidSnapshot() {
        CorrelationContextSnapshot snap = assertDoesNotThrow(SENTINEL::snapshot);

        assertNotNull(snap);
        assertEquals("unavailable", snap.requestId().value());
        assertEquals("unavailable", snap.correlationId().value());
    }

    // --- instanceof check ---

    @Test
    @DisplayName("INSTANCE implements CorrelationContext")
    void implementsCorrelationContext() {
        assertInstanceOf(CorrelationContext.class, SENTINEL);
    }

    // --- INSTANCE is singleton ---

    @Test
    @DisplayName("INSTANCE is the same reference returned by CorrelationContext.unbound()")
    void unboundFactory_returnsSameInstance() {
        assertSame(UnboundCorrelationContext.INSTANCE, CorrelationContext.unbound());
    }

    // --- distinguishable from a bound context ---

    @Test
    @DisplayName("sentinel requestId is distinguishable from a real bound context identifier")
    void isDistinguishable_fromBoundContext() {
        CorrelationIdentifier realId = new CorrelationIdentifier("a1b2c3d4", "header");
        CorrelationIdentifier sentinelId = SENTINEL.requestId();

        assertEquals("unavailable", sentinelId.value());
        // A real request id will differ from "unavailable"
        assertNotNull(realId.value());
        assertEquals("a1b2c3d4", realId.value());
    }
}
