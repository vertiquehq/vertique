// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableDecodeContext}.
 *
 * <p>Verifies that the back-compat convenience constructor yields an empty carrier, that the
 * canonical constructor carries a present descriptor through {@link DurableDecodeContext#carrier()},
 * and that a null carrier {@link Optional} is rejected.
 */
class DurableDecodeContextTest {

    @Test
    @DisplayName("convenience constructor yields an empty carrier")
    void convenienceCtorYieldsEmptyCarrier() {
        DurableDecodeContext ctx = new DurableDecodeContext("kafka");

        assertEquals("kafka", ctx.boundary());
        assertTrue(ctx.carrier().isEmpty());
    }

    @Test
    @DisplayName("canonical constructor carries a present descriptor")
    void canonicalCtorCarriesDescriptor() {
        DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.of("OrderPlaced"));
        DurableCarrierDescriptor descriptor = new DurableCarrierDescriptor("carrier-1", target);
        DurableDecodeContext ctx = new DurableDecodeContext("kafka", Optional.of(descriptor));

        assertEquals("kafka", ctx.boundary());
        assertSame(descriptor, ctx.carrier().orElseThrow());
    }

    @Test
    @DisplayName("null carrier Optional throws NullPointerException")
    void nullCarrierRejected() {
        assertThrows(NullPointerException.class, () -> new DurableDecodeContext("kafka", null));
    }
}
