// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InboundContextInitializationContext}.
 *
 * <p>Verifies that null and blank boundaries are rejected, and that a valid boundary round-trips.
 */
class InboundContextInitializationContextTest {

    @Test
    @DisplayName("null boundary throws NullPointerException")
    void nullBoundaryThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new InboundContextInitializationContext(null));
    }

    @Test
    @DisplayName("blank boundary throws IllegalArgumentException")
    void blankBoundaryThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new InboundContextInitializationContext("   "));
    }

    @Test
    @DisplayName("empty string boundary throws IllegalArgumentException")
    void emptyBoundaryThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new InboundContextInitializationContext(""));
    }

    @Test
    @DisplayName("valid boundary is stored and accessible")
    void validBoundaryIsStored() {
        InboundContextInitializationContext ctx = new InboundContextInitializationContext("kafka");
        assertEquals("kafka", ctx.boundary());
    }
}
