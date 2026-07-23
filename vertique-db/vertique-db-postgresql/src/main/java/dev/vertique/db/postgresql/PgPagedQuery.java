// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.query.NullHandling;
import dev.vertique.db.query.OrderDirection;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.PagedQuery;
import dev.vertique.db.query.QueryClause;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL implementation of {@link PagedQuery}. Composes paginated SQL with PostgreSQL-specific
 * syntax: {@code $N} parameter placeholders, keyset comparison conditions, and {@code LIMIT} clause.
 *
 * <p>Validates that the base SQL does not contain {@code ORDER BY}, {@code LIMIT}, {@code OFFSET},
 * or inline lock clauses — these are managed by the framework.
 *
 * <p>SQL generation uses three tiers based on the order key configuration:
 *
 * <ul>
 *   <li><b>Tier A</b> (fast path): uniform direction + all DISALLOW → row-value tuple comparison
 *   <li><b>Tier B</b>: mixed directions + all DISALLOW → lexicographic predicate
 *   <li><b>Tier C</b>: any NULLS_FIRST/NULLS_LAST → null-aware lexicographic predicate
 * </ul>
 *
 * <pre>{@code
 * // First page: SELECT * FROM items ORDER BY name ASC, id ASC LIMIT 21
 * pagedQuery("SELECT id, name FROM items")
 *     .mapping(Item::fromRow)
 *     .orderBy("name", "id")
 *     .pageSize(20)
 *     .page();
 *
 * // Subsequent page with cursor
 * pagedQuery("SELECT id, name FROM items")
 *     .mapping(Item::fromRow)
 *     .orderBy("name", "id")
 *     .page(cursor);
 *
 * // Mixed directions
 * pagedQuery("SELECT id, name, priority FROM items")
 *     .mapping(Item::fromRow)
 *     .orderBy(OrderKey.desc("priority"), OrderKey.asc("id"))
 *     .page(cursor);
 * }</pre>
 *
 * @param <T> the domain type
 * @see PgSqlRepository#pagedQuery(String)
 */
public final class PgPagedQuery<T> extends PagedQuery<T> {

    private PgPagedQuery(Builder<T> builder) {
        super(builder);
        validateBaseSql(sql());
    }

    /**
     * Creates a new builder for a PostgreSQL paged query.
     *
     * @param <T> the domain type
     * @return a new builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    @Override
    protected String buildPaginatedSql(
            String baseSql,
            List<OrderKey> orderKeys,
            boolean hasKeysetValues,
            List<Object> keysetValues,
            int baseParamCount,
            int fetchCount,
            QueryClause queryClause) {

        var sb = new StringBuilder(baseSql);
        String[] quotedCols = quoteColumns(orderKeys);

        // Keyset condition
        if (hasKeysetValues) {
            boolean hasWhere = hasWhereClause(baseSql);
            sb.append(hasWhere ? " AND " : " WHERE ");

            boolean allDisallow = orderKeys.stream().allMatch(k -> k.nullHandling() == NullHandling.DISALLOW);
            boolean uniformDirection =
                    orderKeys.stream().map(OrderKey::direction).distinct().count() == 1;
            boolean hasNullValues = keysetValues.stream().anyMatch(Objects::isNull);

            if (allDisallow && uniformDirection && !hasNullValues) {
                // Tier A: row-value tuple comparison (fastest)
                appendTupleComparison(sb, quotedCols, orderKeys, baseParamCount);
            } else if (allDisallow && !hasNullValues) {
                // Tier B: mixed-direction lexicographic (no nulls)
                appendLexicographicComparison(sb, quotedCols, orderKeys, baseParamCount);
            } else {
                // Tier C: null-aware lexicographic
                appendNullAwareLexicographicComparison(sb, quotedCols, orderKeys, keysetValues, baseParamCount);
            }
        }

        // ORDER BY
        appendOrderBy(sb, quotedCols, orderKeys);

        // LIMIT
        sb.append(" LIMIT ").append(fetchCount);

        // Query clause (e.g., FOR UPDATE)
        if (queryClause != null) {
            sb.append(' ').append(queryClause.sql());
        }

        return sb.toString();
    }

    /**
     * Tier A: Row-value tuple comparison. Used when all keys have the same direction and nulls
     * are disallowed.
     * Generates: (col1, col2) &gt; ($N, $N+1)
     *
     * @param sb             the string builder to append to
     * @param quotedCols     pre-quoted column names
     * @param orderKeys      the order keys
     * @param baseParamCount the number of base query parameters (for offset in placeholders)
     */
    private static void appendTupleComparison(
            StringBuilder sb, String[] quotedCols, List<OrderKey> orderKeys, int baseParamCount) {
        OrderDirection direction = orderKeys.get(0).direction();
        sb.append('(');
        for (int i = 0; i < orderKeys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quotedCols[i]);
        }
        sb.append(") ").append(direction.keysetOperator()).append(" (");
        for (int i = 0; i < orderKeys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('$').append(baseParamCount + i + 1);
        }
        sb.append(')');
    }

    /**
     * Tier B: Lexicographic comparison for mixed directions without nulls.
     * Generates: (col1 &gt; $1) OR (col1 = $1 AND col2 &lt; $2)
     * For N columns, generates N OR-ed clauses where clause i checks columns 0..i.
     *
     * @param sb             the string builder to append to
     * @param quotedCols     pre-quoted column names
     * @param orderKeys      the order keys
     * @param baseParamCount the number of base query parameters (for offset in placeholders)
     */
    private static void appendLexicographicComparison(
            StringBuilder sb, String[] quotedCols, List<OrderKey> orderKeys, int baseParamCount) {
        sb.append('(');
        for (int i = 0; i < orderKeys.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append('(');
            // Equality conditions for columns 0..i-1
            for (int j = 0; j < i; j++) {
                sb.append(quotedCols[j])
                        .append(" = $")
                        .append(baseParamCount + j + 1)
                        .append(" AND ");
            }
            // Comparison condition for column i
            sb.append(quotedCols[i])
                    .append(' ')
                    .append(orderKeys.get(i).direction().keysetOperator())
                    .append(" $")
                    .append(baseParamCount + i + 1);
            sb.append(')');
        }
        sb.append(')');
    }

    /**
     * Tier C: Null-aware lexicographic comparison. Similar to Tier B but adds IS NULL/IS NOT NULL
     * branches for columns that allow nulls.
     *
     * <p>For a column with NULLS_LAST + ASC, a row "comes after" the cursor if:
     * <ul>
     *   <li>cursor value is non-null and row value &gt; cursor value, OR
     *   <li>cursor value is non-null and row value IS NULL (nulls sort last), OR
     *   <li>cursor value is null and row value IS NULL and next columns decide
     * </ul>
     *
     * <p>For NULLS_FIRST + ASC, a row "comes after" the cursor if:
     * <ul>
     *   <li>cursor value is null and row value IS NOT NULL, OR
     *   <li>both non-null and row value &gt; cursor value
     * </ul>
     *
     * @param sb             the string builder to append to
     * @param quotedCols     pre-quoted column names
     * @param orderKeys      the order keys
     * @param keysetValues   the cursor's keyset values
     * @param baseParamCount the number of base query parameters (for offset in placeholders)
     */
    private static void appendNullAwareLexicographicComparison(
            StringBuilder sb,
            String[] quotedCols,
            List<OrderKey> orderKeys,
            List<Object> keysetValues,
            int baseParamCount) {
        // Pre-compute param indices: null values don't get a $N placeholder
        int[] paramIndices = new int[orderKeys.size()];
        int nextParam = baseParamCount + 1;
        for (int i = 0; i < keysetValues.size(); i++) {
            if (keysetValues.get(i) != null) {
                paramIndices[i] = nextParam++;
            } else {
                paramIndices[i] = -1; // not referenced in SQL
            }
        }

        sb.append('(');
        for (int i = 0; i < orderKeys.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append('(');
            // Equality conditions for columns 0..i-1 (null-safe)
            for (int j = 0; j < i; j++) {
                appendNullSafeEquality(sb, quotedCols[j], keysetValues.get(j), paramIndices[j]);
                sb.append(" AND ");
            }
            // Strict ordering condition for column i (null-aware)
            appendNullAwareStrictOrder(sb, orderKeys.get(i), quotedCols[i], keysetValues.get(i), paramIndices[i]);
            sb.append(')');
        }
        sb.append(')');
    }

    /**
     * Generates null-safe equality: {@code (col = $N)} or {@code (col IS NULL)} when the cursor
     * value is null.
     *
     * @param sb           the string builder to append to
     * @param quotedCol    the pre-quoted column name
     * @param cursorValue  the cursor value for this column (may be null)
     * @param paramIndex   the parameter placeholder index
     */
    private static void appendNullSafeEquality(StringBuilder sb, String quotedCol, Object cursorValue, int paramIndex) {
        if (cursorValue == null) {
            sb.append(quotedCol).append(" IS NULL");
        } else {
            assert paramIndex > 0 : "paramIndex must be positive for non-null cursor value";
            sb.append(quotedCol).append(" = $").append(paramIndex);
        }
    }

    /**
     * Generates the strict ordering condition for a single column, handling nulls based on the
     * column's {@link NullHandling} policy and the actual cursor value.
     *
     * @param sb           the string builder to append to
     * @param key          the order key (for direction and null handling)
     * @param quotedCol    the pre-quoted column name
     * @param cursorValue  the cursor value for this column (may be null)
     * @param paramIndex   the parameter placeholder index
     */
    private static void appendNullAwareStrictOrder(
            StringBuilder sb, OrderKey key, String quotedCol, Object cursorValue, int paramIndex) {
        NullHandling nh = key.nullHandling();

        if (!nh.allowsNulls()) {
            // DISALLOW: simple comparison, no nulls possible
            assert paramIndex > 0 : "paramIndex must be positive for non-null cursor value";
            sb.append(quotedCol)
                    .append(' ')
                    .append(key.direction().keysetOperator())
                    .append(" $")
                    .append(paramIndex);
            return;
        }

        if (cursorValue == null) {
            // Cursor value is null — NULLS_LAST means nulls are at the end, nothing after
            if (nh == NullHandling.NULLS_LAST) {
                sb.append("FALSE");
            } else {
                // NULLS_FIRST: nulls are at the beginning, non-null values come after
                sb.append(quotedCol).append(" IS NOT NULL");
            }
        } else {
            // Cursor value is non-null
            assert paramIndex > 0 : "paramIndex must be positive for non-null cursor value";
            sb.append('(');
            sb.append(quotedCol)
                    .append(' ')
                    .append(key.direction().keysetOperator())
                    .append(" $")
                    .append(paramIndex);
            // NULLS_LAST: null rows sort after all non-null rows
            if (nh == NullHandling.NULLS_LAST) {
                sb.append(" OR ").append(quotedCol).append(" IS NULL");
            }
            sb.append(')');
        }
    }

    /**
     * Pre-computes quoted column names for all order keys.
     *
     * @see PgSqlComposer#quoteColumns(List)
     */
    private static String[] quoteColumns(List<OrderKey> orderKeys) {
        return PgSqlComposer.quoteColumns(orderKeys);
    }

    /**
     * Appends the {@code ORDER BY} clause with per-column direction and null handling.
     *
     * @see PgSqlComposer#appendOrderBy(StringBuilder, String[], List)
     */
    private static void appendOrderBy(StringBuilder sb, String[] quotedCols, List<OrderKey> orderKeys) {
        PgSqlComposer.appendOrderBy(sb, quotedCols, orderKeys);
    }

    // --- SQL Validation ---

    private static void validateBaseSql(String sql) {
        PgSqlComposer.validateBaseSql(sql);
    }

    /**
     * Builder for {@link PgPagedQuery}.
     *
     * @param <T> the domain type
     */
    public static final class Builder<T> extends PagedQuery.Builder<T, Builder<T>> {

        Builder() {}

        @Override
        protected Builder<T> self() {
            return this;
        }

        @Override
        protected PgPagedQuery<T> build() {
            return new PgPagedQuery<>(this);
        }
    }
}
