// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OutboxSort}. */
@DisplayName("OutboxSort")
class OutboxSortTest {

    @Test
    @DisplayName("there are exactly four orders")
    void fourOrders() {
        assertEquals(4, OutboxSort.values().length);
    }

    @Test
    @DisplayName("each order names its timestamp column and direction")
    void columnAndDirection() {
        assertEquals("created_at", OutboxSort.CREATED_AT_DESC.column());
        assertFalse(OutboxSort.CREATED_AT_DESC.ascending());
        assertEquals("created_at", OutboxSort.CREATED_AT_ASC.column());
        assertTrue(OutboxSort.CREATED_AT_ASC.ascending());
        assertEquals("updated_at", OutboxSort.UPDATED_AT_DESC.column());
        assertFalse(OutboxSort.UPDATED_AT_DESC.ascending());
        assertEquals("updated_at", OutboxSort.UPDATED_AT_ASC.column());
        assertTrue(OutboxSort.UPDATED_AT_ASC.ascending());
    }
}
