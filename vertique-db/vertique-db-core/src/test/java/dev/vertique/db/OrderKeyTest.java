// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.query.NullHandling;
import dev.vertique.db.query.OrderDirection;
import dev.vertique.db.query.OrderKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderKeyTest {

    // -- Factory methods --

    @Test
    @DisplayName("asc() creates ASC key with DISALLOW null handling")
    void asc_createsAscWithDisallow() {
        OrderKey key = OrderKey.asc("name");
        assertEquals("name", key.column());
        assertEquals(OrderDirection.ASC, key.direction());
        assertEquals(NullHandling.DISALLOW, key.nullHandling());
    }

    @Test
    @DisplayName("desc() creates DESC key with DISALLOW null handling")
    void desc_createsDescWithDisallow() {
        OrderKey key = OrderKey.desc("name");
        assertEquals("name", key.column());
        assertEquals(OrderDirection.DESC, key.direction());
        assertEquals(NullHandling.DISALLOW, key.nullHandling());
    }

    @Test
    @DisplayName("of() creates key with given direction and DISALLOW null handling")
    void of_createsWithDisallow() {
        OrderKey key = OrderKey.of("name", OrderDirection.DESC);
        assertEquals("name", key.column());
        assertEquals(OrderDirection.DESC, key.direction());
        assertEquals(NullHandling.DISALLOW, key.nullHandling());
    }

    // -- Fluent modifiers --

    @Test
    @DisplayName("nullsFirst() returns new instance with NULLS_FIRST handling")
    void nullsFirst_returnsNewInstanceWithNullsFirst() {
        OrderKey key = OrderKey.asc("name").nullsFirst();
        assertEquals("name", key.column());
        assertEquals(OrderDirection.ASC, key.direction());
        assertEquals(NullHandling.NULLS_FIRST, key.nullHandling());
    }

    @Test
    @DisplayName("nullsLast() returns new instance with NULLS_LAST handling")
    void nullsLast_returnsNewInstanceWithNullsLast() {
        OrderKey key = OrderKey.desc("name").nullsLast();
        assertEquals("name", key.column());
        assertEquals(OrderDirection.DESC, key.direction());
        assertEquals(NullHandling.NULLS_LAST, key.nullHandling());
    }

    @Test
    @DisplayName("disallowNulls() returns new instance with DISALLOW handling")
    void disallowNulls_returnsNewInstanceWithDisallow() {
        OrderKey key = OrderKey.asc("name").nullsFirst().disallowNulls();
        assertEquals(NullHandling.DISALLOW, key.nullHandling());
    }

    // -- Reverse --

    @Test
    @DisplayName("reverse() flips ASC to DESC")
    void reverse_flipsDirection() {
        OrderKey key = OrderKey.asc("name").reverse();
        assertEquals(OrderDirection.DESC, key.direction());
    }

    @Test
    @DisplayName("reverse() inverts NULLS_FIRST to NULLS_LAST")
    void reverse_invertsNullsFirst_toNullsLast() {
        OrderKey key = OrderKey.asc("name").nullsFirst().reverse();
        assertEquals(OrderDirection.DESC, key.direction());
        assertEquals(NullHandling.NULLS_LAST, key.nullHandling());
    }

    @Test
    @DisplayName("reverse() inverts NULLS_LAST to NULLS_FIRST")
    void reverse_invertsNullsLast_toNullsFirst() {
        OrderKey key = OrderKey.asc("name").nullsLast().reverse();
        assertEquals(OrderDirection.DESC, key.direction());
        assertEquals(NullHandling.NULLS_FIRST, key.nullHandling());
    }

    @Test
    @DisplayName("reverse() preserves DISALLOW null handling")
    void reverse_preservesDisallow() {
        OrderKey key = OrderKey.asc("name").reverse();
        assertEquals(NullHandling.DISALLOW, key.nullHandling());
    }

    @Test
    @DisplayName("reverse() is symmetric — reverse(reverse(x)) == x")
    void reverse_isSymmetric() {
        OrderKey original = OrderKey.asc("name").nullsFirst();
        assertEquals(original, original.reverse().reverse());
    }

    // -- Validation --

    @Test
    @DisplayName("Rejects null column name")
    void rejectsNullColumn() {
        assertThrows(IllegalArgumentException.class, () -> OrderKey.asc(null));
    }

    @Test
    @DisplayName("Rejects blank column name")
    void rejectsBlankColumn() {
        assertThrows(IllegalArgumentException.class, () -> OrderKey.asc("  "));
    }

    @Test
    @DisplayName("Rejects empty column name")
    void rejectsEmptyColumn() {
        assertThrows(IllegalArgumentException.class, () -> OrderKey.asc(""));
    }

    @Test
    @DisplayName("Rejects null direction")
    void rejectsNullDirection() {
        assertThrows(NullPointerException.class, () -> new OrderKey("name", null, NullHandling.DISALLOW));
    }

    @Test
    @DisplayName("Rejects null null-handling policy")
    void rejectsNullNullHandling() {
        assertThrows(NullPointerException.class, () -> new OrderKey("name", OrderDirection.ASC, null));
    }

    // -- SQL identifier validation --

    @Test
    @DisplayName("rejectsSqlInjection: asc() rejects SQL injection in column name")
    void rejectsSqlInjection() {
        assertThrows(InvalidDataAccessUsageException.class, () -> OrderKey.asc("id; DROP TABLE items--"));
    }

    @Test
    @DisplayName("rejectsColumnWithSpaces: asc() rejects column name containing a space")
    void rejectsColumnWithSpaces() {
        assertThrows(InvalidDataAccessUsageException.class, () -> OrderKey.asc("col name"));
    }

    @Test
    @DisplayName("rejectsColumnStartingWithDigit: asc() rejects column name starting with digit")
    void rejectsColumnStartingWithDigit() {
        assertThrows(InvalidDataAccessUsageException.class, () -> OrderKey.asc("1col"));
    }

    @Test
    @DisplayName("acceptsDotQualifiedColumn: asc() accepts schema-qualified column name")
    void acceptsDotQualifiedColumn() {
        OrderKey key = assertDoesNotThrow(() -> OrderKey.asc("t.name"));
        assertEquals("t.name", key.column());
    }

    @Test
    @DisplayName("acceptsUnderscoreColumn: asc() accepts snake_case column name")
    void acceptsUnderscoreColumn() {
        assertDoesNotThrow(() -> OrderKey.asc("created_at"));
    }
}
