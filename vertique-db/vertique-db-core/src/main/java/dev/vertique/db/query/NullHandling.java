// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

/**
 * Null handling policy for keyset pagination columns. Controls both SQL {@code ORDER BY} null
 * placement and runtime validation of keyset values.
 *
 * <ul>
 *   <li>{@link #DISALLOW} — null keyset values are rejected at runtime (default)
 *   <li>{@link #NULLS_FIRST} — nulls sort before non-null values ({@code ORDER BY col NULLS FIRST})
 *   <li>{@link #NULLS_LAST} — nulls sort after non-null values ({@code ORDER BY col NULLS LAST})
 * </ul>
 *
 * @see OrderKey
 * @see PagedQuery
 */
public enum NullHandling {

    /**
     * Null keyset values are not allowed. If a keyset value is null at execution time, the query
     * fails fast with an {@link IllegalArgumentException}. This is the default policy and enables
     * optimized SQL generation (row-value tuple comparison).
     */
    DISALLOW,

    /**
     * Nulls sort before all non-null values. Generates {@code ORDER BY col NULLS FIRST} and
     * null-aware keyset comparison predicates.
     */
    NULLS_FIRST,

    /**
     * Nulls sort after all non-null values. Generates {@code ORDER BY col NULLS LAST} and
     * null-aware keyset comparison predicates.
     */
    NULLS_LAST;

    /**
     * Returns {@code true} if this policy allows null keyset values.
     *
     * @return true for {@link #NULLS_FIRST} and {@link #NULLS_LAST}, false for {@link #DISALLOW}
     */
    public boolean allowsNulls() {
        return this != DISALLOW;
    }
}
