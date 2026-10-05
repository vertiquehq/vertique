// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationContext#generated(String)} /
 * {@link GeneratedCorrelationContext}.
 *
 * <p>Verifies: minted identifiers are unique UUID v4 values carrying the supplied source; the
 * reserved unbound sentinel is never returned; optional fields are empty; blank/null sources are
 * rejected.
 */
class GeneratedCorrelationContextTest {

    private static final String SOURCE = "generated:test";

    @Test
    @DisplayName("generated() returns a CorrelationContext with UUID request and correlation ids")
    void generated_returnsJoinableIds() {
        CorrelationContext ctx = CorrelationContext.generated(SOURCE);

        assertInstanceOf(CorrelationContext.class, ctx);
        assertNotNull(ctx.requestId());
        assertNotNull(ctx.correlationId());
        assertEquals(SOURCE, ctx.requestId().source());
        assertEquals(SOURCE, ctx.correlationId().source());
        assertDoesNotThrow(() -> UUID.fromString(ctx.requestId().value()));
        assertDoesNotThrow(() -> UUID.fromString(ctx.correlationId().value()));
        assertNotEquals(ctx.requestId().value(), ctx.correlationId().value());
    }

    @Test
    @DisplayName("generated() never returns the unbound sentinel identifiers")
    void generated_isNotUnboundSentinel() {
        CorrelationContext ctx = CorrelationContext.generated(SOURCE);

        assertNotEquals(
                UnboundCorrelationContext.SENTINEL_ID_VALUE, ctx.requestId().value());
        assertNotEquals(
                UnboundCorrelationContext.SENTINEL_ID_SOURCE, ctx.requestId().source());
        assertNotEquals(UnboundCorrelationContext.INSTANCE.requestId(), ctx.requestId());
    }

    @Test
    @DisplayName("each generated() call mints distinct request ids")
    void generated_idsAreUniquePerCall() {
        CorrelationContext a = CorrelationContext.generated(SOURCE);
        CorrelationContext b = CorrelationContext.generated(SOURCE);

        assertNotEquals(a.requestId().value(), b.requestId().value());
        assertNotEquals(a.correlationId().value(), b.correlationId().value());
    }

    @Test
    @DisplayName("optional fields are empty / null")
    void optionalFieldsEmpty() {
        CorrelationContext ctx = CorrelationContext.generated(SOURCE);

        assertNull(ctx.causationId());
        assertNull(ctx.trace());
        assertNull(ctx.session());
        assertTrue(ctx.protocolCorrelations().isEmpty());
        assertTrue(ctx.attributes().isEmpty());
    }

    @Test
    @DisplayName("snapshot preserves the minted identifiers")
    void snapshot_preservesIds() {
        CorrelationContext ctx = CorrelationContext.generated(SOURCE);
        CorrelationContextSnapshot snap = ctx.snapshot();

        assertEquals(ctx.requestId(), snap.requestId());
        assertEquals(ctx.correlationId(), snap.correlationId());
    }

    @Test
    @DisplayName("null source is rejected")
    void nullSourceRejected() {
        assertThrows(NullPointerException.class, () -> CorrelationContext.generated(null));
    }

    @Test
    @DisplayName("blank source is rejected")
    void blankSourceRejected() {
        assertThrows(IllegalArgumentException.class, () -> CorrelationContext.generated("  "));
    }
}
