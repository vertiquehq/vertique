// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that each {@link IsolationLevel} returns the correct SQL fragment.
 */
class IsolationLevelTest {

    @Test
    @DisplayName("READ_COMMITTED returns 'READ COMMITTED'")
    void readCommitted() {
        assertEquals("READ COMMITTED", IsolationLevel.READ_COMMITTED.sql());
    }

    @Test
    @DisplayName("REPEATABLE_READ returns 'REPEATABLE READ'")
    void repeatableRead() {
        assertEquals("REPEATABLE READ", IsolationLevel.REPEATABLE_READ.sql());
    }

    @Test
    @DisplayName("SERIALIZABLE returns 'SERIALIZABLE'")
    void serializable() {
        assertEquals("SERIALIZABLE", IsolationLevel.SERIALIZABLE.sql());
    }
}
