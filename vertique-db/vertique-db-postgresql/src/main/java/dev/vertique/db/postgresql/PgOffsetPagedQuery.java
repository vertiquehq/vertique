// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.query.OffsetPagedQuery;
import dev.vertique.db.query.OrderKey;
import dev.vertique.db.query.QueryClause;
import java.util.List;

/**
 * PostgreSQL implementation of {@link OffsetPagedQuery}. Composes offset-paginated SQL with
 * PostgreSQL-specific syntax: double-quoted identifiers, {@code ORDER BY}, {@code LIMIT}, and
 * {@code OFFSET} clauses.
 *
 * <p>Validates that the base SQL does not contain {@code ORDER BY}, {@code LIMIT}, {@code OFFSET},
 * or inline lock clauses — these are managed by the framework.
 *
 * <pre>{@code
 * // First page (page 0)
 * offsetPagedQuery("SELECT id, name FROM items WHERE status = $1")
 *     .params(Tuple.of("active"))
 *     .mapping(Item::fromRow)
 *     .orderBy("name", "id")
 *     .pageSize(20)
 *     .page(0);
 *
 * // Explicit page and size
 * offsetPagedQuery("SELECT id, name FROM items")
 *     .mapping(Item::fromRow)
 *     .orderBy("name", "id")
 *     .page(2, 15);
 * }</pre>
 *
 * @param <T> the domain type
 * @see PgSqlRepository#offsetPagedQuery()
 */
public final class PgOffsetPagedQuery<T> extends OffsetPagedQuery<T> {

    private PgOffsetPagedQuery(Builder<T> builder) {
        super(builder);
        validateBaseSql(sql());
    }

    /**
     * Creates a new builder for a PostgreSQL offset-paged query.
     *
     * @param <T> the domain type
     * @return a new builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Composes the final paginated SQL string. Appends an {@code ORDER BY} clause built from
     * the given order keys, followed by {@code LIMIT limit OFFSET offset}. An optional query
     * clause (e.g., {@code FOR UPDATE}) is appended last.
     *
     * @param baseSql     the user-provided base SQL
     * @param orderKeys   the effective order keys
     * @param offset      the row offset ({@code page * pageSize})
     * @param limit       the maximum number of rows to fetch ({@code pageSize})
     * @param queryClause optional trailing clause, may be null
     * @return the complete paginated SQL
     */
    @Override
    protected String buildDataSql(
            String baseSql, List<OrderKey> orderKeys, long offset, int limit, QueryClause queryClause) {
        var sb = new StringBuilder(baseSql);
        String[] quotedCols = PgSqlComposer.quoteColumns(orderKeys);

        PgSqlComposer.appendOrderBy(sb, quotedCols, orderKeys);

        sb.append(" LIMIT ").append(limit);
        sb.append(" OFFSET ").append(offset);

        if (queryClause != null) {
            sb.append(' ').append(queryClause.sql());
        }

        return sb.toString();
    }

    // --- SQL Validation ---

    private static void validateBaseSql(String sql) {
        PgSqlComposer.validateBaseSql(sql);
    }

    /**
     * Builder for {@link PgOffsetPagedQuery}.
     *
     * @param <T> the domain type
     */
    public static final class Builder<T> extends OffsetPagedQuery.Builder<T, Builder<T>> {

        /** Creates a new builder. */
        Builder() {}

        /** {@inheritDoc} */
        @Override
        protected Builder<T> self() {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        protected PgOffsetPagedQuery<T> build() {
            return new PgOffsetPagedQuery<>(this);
        }
    }
}
