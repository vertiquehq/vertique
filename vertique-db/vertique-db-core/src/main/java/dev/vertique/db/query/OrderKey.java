// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import java.util.Objects;

/**
 * Describes a single column in a keyset-paginated query's {@code ORDER BY} clause, including its
 * sort direction and null handling policy.
 *
 * <p>Use the static factory methods {@link #asc(String)} and {@link #desc(String)} for the common
 * case (no nulls allowed), and the fluent modifiers {@link #nullsFirst()} and {@link #nullsLast()}
 * to enable null-aware pagination:
 *
 * <pre>{@code
 * // Simple: all columns ASC, nulls disallowed
 * .orderBy("name", "id")
 *
 * // Per-column control
 * .orderBy(
 *     OrderKey.desc("priority").nullsLast(),
 *     OrderKey.asc("created_at"),
 *     OrderKey.asc("id"))
 * }</pre>
 *
 * @param column       the SQL column name (must not be null or blank)
 * @param direction    the sort direction
 * @param nullHandling the null handling policy
 * @see OrderDirection
 * @see NullHandling
 * @see PagedQuery
 */
public record OrderKey(String column, OrderDirection direction, NullHandling nullHandling) {

    /**
     * Validates that all fields are non-null, column is not blank, and column contains only safe
     * SQL identifier characters.
     */
    public OrderKey {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("column must not be null or blank");
        }
        SqlIdentifier.validate(column);
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(nullHandling, "nullHandling must not be null");
    }

    /**
     * Creates an ascending order key with null values disallowed.
     *
     * @param column the SQL column name
     * @return an ascending order key
     */
    public static OrderKey asc(String column) {
        return new OrderKey(column, OrderDirection.ASC, NullHandling.DISALLOW);
    }

    /**
     * Creates a descending order key with null values disallowed.
     *
     * @param column the SQL column name
     * @return a descending order key
     */
    public static OrderKey desc(String column) {
        return new OrderKey(column, OrderDirection.DESC, NullHandling.DISALLOW);
    }

    /**
     * Creates an order key with the given direction and null values disallowed.
     *
     * @param column    the SQL column name
     * @param direction the sort direction
     * @return an order key
     */
    public static OrderKey of(String column, OrderDirection direction) {
        return new OrderKey(column, direction, NullHandling.DISALLOW);
    }

    /**
     * Returns a new order key with {@link NullHandling#NULLS_FIRST} policy. Nulls will sort before
     * all non-null values.
     *
     * @return a new order key with nulls-first handling
     */
    public OrderKey nullsFirst() {
        return new OrderKey(column, direction, NullHandling.NULLS_FIRST);
    }

    /**
     * Returns a new order key with {@link NullHandling#NULLS_LAST} policy. Nulls will sort after
     * all non-null values.
     *
     * @return a new order key with nulls-last handling
     */
    public OrderKey nullsLast() {
        return new OrderKey(column, direction, NullHandling.NULLS_LAST);
    }

    /**
     * Returns a new order key with {@link NullHandling#DISALLOW} policy. Null keyset values will be
     * rejected at runtime.
     *
     * @return a new order key with nulls disallowed
     */
    public OrderKey disallowNulls() {
        return new OrderKey(column, direction, NullHandling.DISALLOW);
    }

    /**
     * Returns a new order key with the direction reversed and null handling inverted
     * ({@link NullHandling#NULLS_FIRST} ↔ {@link NullHandling#NULLS_LAST}). Used internally for
     * backward pagination to maintain correct null positioning. {@link NullHandling#DISALLOW} is
     * preserved.
     *
     * @return a new order key with the opposite direction and inverted null handling
     */
    public OrderKey reverse() {
        NullHandling reversedNh =
                switch (nullHandling) {
                    case NULLS_FIRST -> NullHandling.NULLS_LAST;
                    case NULLS_LAST -> NullHandling.NULLS_FIRST;
                    case DISALLOW -> NullHandling.DISALLOW;
                };
        return new OrderKey(column, direction.reverse(), reversedNh);
    }
}
