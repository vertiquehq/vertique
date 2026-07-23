// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TransactionOptions} record construction and the {@code DEFAULTS} constant.
 */
class TransactionOptionsTest {

    @Test
    @DisplayName("DEFAULTS has null isolationLevel and readOnly=false")
    void defaultsConstant() {
        assertNull(TransactionOptions.DEFAULTS.isolationLevel());
        assertFalse(TransactionOptions.DEFAULTS.readOnly());
    }

    @Test
    @DisplayName("constructor preserves isolation level and readOnly flag")
    void constructor() {
        var opts = new TransactionOptions(IsolationLevel.SERIALIZABLE, true);
        assertEquals(IsolationLevel.SERIALIZABLE, opts.isolationLevel());
        assertTrue(opts.readOnly());
    }

    @Test
    @DisplayName("constructor with null isolation level and readOnly=false matches DEFAULTS")
    void nullIsolation() {
        var opts = new TransactionOptions(null, false);
        assertNull(opts.isolationLevel());
        assertFalse(opts.readOnly());
    }
}
