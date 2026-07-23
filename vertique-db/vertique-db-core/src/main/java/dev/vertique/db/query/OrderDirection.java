// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

/**
 * Order direction for keyset-paginated queries. Determines the SQL {@code ORDER BY} direction and
 * the comparison operator used in the keyset WHERE clause.
 *
 * @see PagedQuery
 * @see OrderKey
 */
public enum OrderDirection {

    /** Ascending order. Keyset condition uses {@code >} to fetch rows after the cursor. */
    ASC(">", "ASC"),

    /** Descending order. Keyset condition uses {@code <} to fetch rows before the cursor. */
    DESC("<", "DESC");

    private final String keysetOperator;
    private final String sql;

    OrderDirection(String keysetOperator, String sql) {
        this.keysetOperator = keysetOperator;
        this.sql = sql;
    }

    /**
     * Returns the SQL keyword for this direction ({@code "ASC"} or {@code "DESC"}).
     *
     * @return the SQL keyword
     */
    public String sql() {
        return sql;
    }

    /**
     * Returns the comparison operator for keyset pagination ({@code ">"} for ASC, {@code "<"} for
     * DESC).
     *
     * @return the comparison operator
     */
    public String keysetOperator() {
        return keysetOperator;
    }

    /**
     * Returns the opposite direction.
     *
     * @return {@link #DESC} if this is {@link #ASC}, and vice versa
     */
    public OrderDirection reverse() {
        return this == ASC ? DESC : ASC;
    }
}
