// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableEncodeContext}.
 *
 * <p>Verifies that the back-compat convenience constructors yield an empty carrier/fireTime, that
 * the canonical constructor carries a present descriptor through {@link DurableEncodeContext#carrier()}
 * and a present instant through {@link DurableEncodeContext#fireTime()}, and that a null carrier or
 * fireTime {@link Optional} is rejected.
 */
class DurableEncodeContextTest {

    @Test
    @DisplayName("1-arg convenience constructor yields an empty carrier and empty fireTime")
    void convenienceCtorYieldsEmptyCarrier() {
        DurableEncodeContext ctx = new DurableEncodeContext("kafka");

        assertEquals("kafka", ctx.boundary());
        assertTrue(ctx.carrier().isEmpty());
        assertTrue(ctx.fireTime().isEmpty());
    }

    @Test
    @DisplayName("2-arg convenience constructor carries a present descriptor and yields an empty fireTime")
    void twoArgCtorCarriesDescriptorEmptyFireTime() {
        DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.of("OrderPlaced"));
        DurableCarrierDescriptor descriptor = new DurableCarrierDescriptor("carrier-1", target);
        DurableEncodeContext ctx = new DurableEncodeContext("kafka", Optional.of(descriptor));

        assertEquals("kafka", ctx.boundary());
        assertSame(descriptor, ctx.carrier().orElseThrow());
        assertTrue(ctx.fireTime().isEmpty());
    }

    @Test
    @DisplayName("canonical 3-arg constructor carries a present descriptor and a present fireTime")
    void canonicalCtorCarriesDescriptorAndFireTime() {
        DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.of("OrderPlaced"));
        DurableCarrierDescriptor descriptor = new DurableCarrierDescriptor("carrier-1", target);
        Instant fireTime = Instant.parse("2026-07-01T10:15:30Z");
        DurableEncodeContext ctx =
                new DurableEncodeContext("delayed-job", Optional.of(descriptor), Optional.of(fireTime));

        assertEquals("delayed-job", ctx.boundary());
        assertSame(descriptor, ctx.carrier().orElseThrow());
        assertEquals(fireTime, ctx.fireTime().orElseThrow());
    }

    @Test
    @DisplayName("null carrier Optional throws NullPointerException")
    void nullCarrierRejected() {
        assertThrows(NullPointerException.class, () -> new DurableEncodeContext("kafka", null));
    }

    @Test
    @DisplayName("null fireTime Optional throws NullPointerException")
    void nullFireTimeRejected() {
        assertThrows(NullPointerException.class, () -> new DurableEncodeContext("kafka", Optional.empty(), null));
    }
}
