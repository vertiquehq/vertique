// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.query.NullHandling;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.SqlIdentifier;
import dev.vertique.db.query.SqlScanner;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared SQL composition and validation utilities for PostgreSQL paginated queries. Used by both
 * {@link PgPagedQuery} (keyset) and {@link PgOffsetPagedQuery} (offset) to avoid duplicating ORDER
 * BY generation, column quoting, and base SQL validation logic.
 *
 * <p>Package-private — not part of the public API.
 */
final class PgSqlComposer {

    private PgSqlComposer() {}

    // --- SQL validation patterns ---

    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFSET_PATTERN = Pattern.compile("\\bOFFSET\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCK_PATTERN =
            Pattern.compile("\\bFOR\\s+(UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Validates that the base SQL does not contain clauses managed by the framework. Throws
     * {@link IllegalArgumentException} if any disallowed clause is detected at the top level
     * (outside parentheses, string literals, and quoted identifiers).
     *
     * @param sql the base SQL to validate
     * @throws IllegalArgumentException if the SQL contains ORDER BY, LIMIT, OFFSET, or lock clauses
     */
    static void validateBaseSql(String sql) {
        if (SqlScanner.containsAtTopLevel(sql, ORDER_BY_PATTERN)) {
            throw new IllegalArgumentException(
                    "Base SQL for paged queries must not contain ORDER BY — the framework appends it automatically.");
        }
        if (SqlScanner.containsAtTopLevel(sql, LIMIT_PATTERN)) {
            throw new IllegalArgumentException(
                    "Base SQL for paged queries must not contain LIMIT — the framework appends it automatically.");
        }
        if (SqlScanner.containsAtTopLevel(sql, OFFSET_PATTERN)) {
            throw new IllegalArgumentException(
                    "Base SQL for paged queries must not contain OFFSET — the framework appends it automatically.");
        }
        if (SqlScanner.containsAtTopLevel(sql, LOCK_PATTERN)) {
            throw new IllegalArgumentException(
                    "Base SQL must not contain FOR UPDATE/SHARE — use .queryClause(PgLockMode.FOR_UPDATE) instead.");
        }
    }

    /**
     * Pre-computes quoted column names for all order keys, avoiding redundant
     * {@link SqlIdentifier#quote} calls in nested loops.
     *
     * @param orderKeys the order keys
     * @return array of quoted column names, one per order key
     */
    static String[] quoteColumns(List<OrderKey> orderKeys) {
        String[] quoted = new String[orderKeys.size()];
        for (int i = 0; i < orderKeys.size(); i++) {
            quoted[i] = SqlIdentifier.quote(orderKeys.get(i).column());
        }
        return quoted;
    }

    /**
     * Appends the {@code ORDER BY} clause with per-column direction and null handling.
     *
     * @param sb         the string builder to append to
     * @param quotedCols pre-quoted column names
     * @param orderKeys  the order keys
     */
    static void appendOrderBy(StringBuilder sb, String[] quotedCols, List<OrderKey> orderKeys) {
        sb.append(" ORDER BY ");
        for (int i = 0; i < orderKeys.size(); i++) {
            if (i > 0) sb.append(", ");
            OrderKey key = orderKeys.get(i);
            sb.append(quotedCols[i]).append(' ').append(key.direction().sql());
            if (key.nullHandling() == NullHandling.NULLS_FIRST) {
                sb.append(" NULLS FIRST");
            } else if (key.nullHandling() == NullHandling.NULLS_LAST) {
                sb.append(" NULLS LAST");
            }
        }
    }
}
