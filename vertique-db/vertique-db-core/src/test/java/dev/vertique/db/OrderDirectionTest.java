// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.OrderDirection;
import org.junit.jupiter.api.Test;

class OrderDirectionTest {

    @Test
    void asc_sql_returnsASC() {
        assertEquals("ASC", OrderDirection.ASC.sql());
    }

    @Test
    void desc_sql_returnsDESC() {
        assertEquals("DESC", OrderDirection.DESC.sql());
    }

    @Test
    void asc_keysetOperator_returnsGreaterThan() {
        assertEquals(">", OrderDirection.ASC.keysetOperator());
    }

    @Test
    void desc_keysetOperator_returnsLessThan() {
        assertEquals("<", OrderDirection.DESC.keysetOperator());
    }

    @Test
    void asc_reverse_returnsDESC() {
        assertEquals(OrderDirection.DESC, OrderDirection.ASC.reverse());
    }

    @Test
    void desc_reverse_returnsASC() {
        assertEquals(OrderDirection.ASC, OrderDirection.DESC.reverse());
    }

    @Test
    void reverse_isSymmetric() {
        assertEquals(OrderDirection.ASC, OrderDirection.ASC.reverse().reverse());
    }
}
