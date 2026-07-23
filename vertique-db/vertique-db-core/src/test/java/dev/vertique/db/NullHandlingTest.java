// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.NullHandling;
import org.junit.jupiter.api.Test;

class NullHandlingTest {

    @Test
    void disallow_doesNotAllowNulls() {
        assertFalse(NullHandling.DISALLOW.allowsNulls());
    }

    @Test
    void nullsFirst_allowsNulls() {
        assertTrue(NullHandling.NULLS_FIRST.allowsNulls());
    }

    @Test
    void nullsLast_allowsNulls() {
        assertTrue(NullHandling.NULLS_LAST.allowsNulls());
    }

    @Test
    void values_containsAllThreeEntries() {
        assertEquals(3, NullHandling.values().length);
    }
}
