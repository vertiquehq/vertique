// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableCarrierDescriptor} and its {@link DurableTarget} component.
 *
 * <p>Verifies that a carrier id and target round-trip through the accessors, that a blank carrier id
 * and a null target are rejected, and that {@code DurableTarget} rejects blank kind/address while
 * accepting both a present and an empty message type.
 */
class DurableCarrierDescriptorTest {

    @Test
    @DisplayName("carrier id and target round-trip through the accessors")
    void carrierIdAndTargetRoundTrip() {
        DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.of("OrderPlaced"));
        DurableCarrierDescriptor descriptor = new DurableCarrierDescriptor("carrier-1", target);

        assertEquals("carrier-1", descriptor.carrierId());
        assertSame(target, descriptor.target());
    }

    @Test
    @DisplayName("blank carrier id throws IllegalArgumentException")
    void blankCarrierIdRejected() {
        DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new DurableCarrierDescriptor("   ", target));
    }

    @Test
    @DisplayName("null carrier id throws NullPointerException")
    void nullCarrierIdRejected() {
        DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.empty());
        assertThrows(NullPointerException.class, () -> new DurableCarrierDescriptor(null, target));
    }

    @Test
    @DisplayName("null target throws NullPointerException")
    void nullTargetRejected() {
        assertThrows(NullPointerException.class, () -> new DurableCarrierDescriptor("carrier-1", null));
    }

    @Nested
    @DisplayName("DurableTarget")
    class DurableTargetTest {

        @Test
        @DisplayName("blank kind throws IllegalArgumentException")
        void blankKindRejected() {
            assertThrows(
                    IllegalArgumentException.class, () -> new DurableTarget("  ", "orders-topic", Optional.empty()));
        }

        @Test
        @DisplayName("blank address throws IllegalArgumentException")
        void blankAddressRejected() {
            assertThrows(IllegalArgumentException.class, () -> new DurableTarget("kafka", "  ", Optional.empty()));
        }

        @Test
        @DisplayName("null message type throws NullPointerException")
        void nullMessageTypeRejected() {
            assertThrows(NullPointerException.class, () -> new DurableTarget("kafka", "orders-topic", null));
        }

        @Test
        @DisplayName("present message type constructs and round-trips")
        void messagePresentConstructs() {
            DurableTarget target = new DurableTarget("kafka", "orders-topic", Optional.of("OrderPlaced"));

            assertEquals("kafka", target.kind());
            assertEquals("orders-topic", target.address());
            assertEquals(Optional.of("OrderPlaced"), target.messageType());
        }

        @Test
        @DisplayName("empty message type constructs and round-trips")
        void messageEmptyConstructs() {
            DurableTarget target = new DurableTarget("outbox", "outbox-table", Optional.empty());

            assertEquals("outbox", target.kind());
            assertEquals("outbox-table", target.address());
            assertTrue(target.messageType().isEmpty());
        }
    }
}
