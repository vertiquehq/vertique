// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import dev.vertique.db.DbExceptionMapper;
import dev.vertique.db.RowMapper;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent builder and executor for offset-based paginated database queries. Supports traditional
 * {@code LIMIT/OFFSET} pagination with a {@code COUNT(*)} sub-query to determine total pages.
 *
 * <p>Unlike {@link PagedQuery} (keyset/cursor-based), offset pagination allows random-access by
 * page number but degrades in performance at deep offsets. Prefer keyset pagination for large
 * datasets or real-time feeds.
 *
 * <p>Instances are created via the {@link dev.vertique.db.SqlRepository#offsetPagedQuery(String)}
 * factory method, which returns a vendor-specific builder pre-configured with the repository's
 * exception mapper and connection pool.
 *
 * <p>The base SQL must <b>not</b> contain {@code ORDER BY}, {@code LIMIT}, or {@code OFFSET}
 * clauses — the framework appends these automatically based on the configured order keys and
 * page number.
 *
 * <pre>{@code
 * // Page 0 (first page)
 * offsetPagedQuery("SELECT id, name, created_at FROM items WHERE status = $1")
 *     .params(Tuple.of("active"))
 *     .mapping(Item::fromRow)
 *     .orderBy("created_at", "id")
 *     .pageSize(20)
 *     .page(0);
 *
 * // Explicit page size
 * offsetPagedQuery("SELECT id, name FROM items")
 *     .mapping(Item::fromRow)
 *     .orderBy("name", "id")
 *     .page(2, 15);
 * }</pre>
 *
 * @param <T> the domain type returned by the row mapper
 * @see OffsetPagedResult
 * @see OrderDirection
 * @see dev.vertique.db.SqlRepository#offsetPagedQuery(String)
 */
public abstract class OffsetPagedQuery<T> {

    private final String sql;
    private final SqlClient client;
    private final Tuple params;
    private final RowMapper<T> mapper;
    private final DbExceptionMapper exceptionMapper;
    private final List<OrderKey> orderKeys;
    private final int defaultPageSize;
    private final QueryClause queryClause;

    /**
     * Constructs an OffsetPagedQuery from the given builder.
     *
     * @param builder the builder with all configuration
     */
    protected OffsetPagedQuery(Builder<T, ?> builder) {
        this.sql = Objects.requireNonNull(builder.sql, "sql must not be null");
        this.client = Objects.requireNonNull(builder.client, "client must not be null");
        this.params = builder.params != null ? builder.params : Tuple.tuple();
        this.mapper = Objects.requireNonNull(builder.mapper, "mapper must not be null for paged queries");
        this.exceptionMapper = Objects.requireNonNull(builder.exceptionMapper, "exceptionMapper must not be null");
        this.orderKeys =
                Objects.requireNonNull(builder.orderKeys, "orderKeys must not be null (call orderBy() on the builder)");
        if (this.orderKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one order key is required");
        }
        this.defaultPageSize = builder.defaultPageSize > 0 ? builder.defaultPageSize : 20;
        this.queryClause = builder.queryClause;
    }

    /**
     * Composes the final paginated SQL string. Vendor-specific subclasses implement parameter
     * placeholder syntax (e.g., {@code $N} for PostgreSQL), ORDER BY clause, and LIMIT/OFFSET.
     *
     * @param baseSql     the user-provided base SQL
     * @param orderKeys   the effective order keys
     * @param offset      the row offset ({@code page * pageSize})
     * @param limit       the maximum number of rows to fetch ({@code pageSize})
     * @param queryClause optional trailing clause (e.g., FOR UPDATE), may be null
     * @return the complete SQL to execute
     */
    protected abstract String buildDataSql(
            String baseSql, List<OrderKey> orderKeys, long offset, int limit, QueryClause queryClause);

    /**
     * Builds the COUNT(*) wrapper SQL used to determine the total number of matching rows.
     *
     * @return the count SQL wrapping the base SQL
     */
    protected String buildCountSql() {
        return "SELECT COUNT(*) FROM (" + sql + ") _cnt";
    }

    // --- Terminal methods ---

    /**
     * Executes the paginated query for the given page using the configured default page size.
     *
     * @param page the zero-based page number
     * @return a future with the paged result
     */
    public Future<OffsetPagedResult<T>> page(int page) {
        return page(page, defaultPageSize);
    }

    /**
     * Executes the paginated query for the given page and page size. Runs a {@code COUNT(*)} query
     * and the data query in parallel and combines their results into an {@link OffsetPagedResult}.
     *
     * @param page     the zero-based page number
     * @param pageSize the number of items per page
     * @return a future with the paged result
     */
    public Future<OffsetPagedResult<T>> page(int page, int pageSize) {
        if (page < 0) {
            return Future.failedFuture(new IllegalArgumentException("page must be >= 0, got: " + page));
        }
        if (pageSize < 1) {
            return Future.failedFuture(new IllegalArgumentException("pageSize must be >= 1, got: " + pageSize));
        }

        long offset = (long) page * pageSize;
        String countSql = buildCountSql();
        String dataSql = buildDataSql(sql, orderKeys, offset, pageSize, queryClause);

        Future<Long> countFuture = client.preparedQuery(countSql)
                .execute(params)
                .map(rows -> {
                    var it = rows.iterator();
                    return it.hasNext() ? it.next().getLong(0) : 0L;
                });

        Future<List<T>> dataFuture = client.preparedQuery(dataSql)
                .execute(params)
                .map(rows -> {
                    List<T> items = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        items.add(mapper.map(row));
                    }
                    return items;
                });

        return Future.all(countFuture, dataFuture)
                .map(cf -> new OffsetPagedResult<>(dataFuture.result(), countFuture.result(), page, pageSize))
                .recover(t -> translated(t, "OffsetPagedQuery.page() failed"));
    }

    /**
     * Translates a failure through the configured exception mapper.
     *
     * @param t       the throwable to translate
     * @param context a descriptive context string for the exception mapper
     * @param <X>     the result type
     * @return a failed future with the translated exception
     */
    private <X> Future<X> translated(Throwable t, String context) {
        return Future.failedFuture(exceptionMapper.translate(t, context));
    }

    // --- Accessors ---

    /**
     * The base SQL query (without ORDER BY, LIMIT, or OFFSET).
     *
     * @return the base SQL string
     */
    public String sql() {
        return sql;
    }

    /**
     * The SQL client (pool or connection) to execute against.
     *
     * @return the SQL client
     */
    public SqlClient client() {
        return client;
    }

    /**
     * The base query parameters.
     *
     * @return the query parameters
     */
    public Tuple params() {
        return params;
    }

    /**
     * The default page size.
     *
     * @return the default page size
     */
    public int defaultPageSize() {
        return defaultPageSize;
    }

    /**
     * Returns the order keys that define the pagination sort order.
     *
     * @return an unmodifiable list of order keys
     */
    public List<OrderKey> orderKeys() {
        return orderKeys;
    }

    /**
     * Abstract builder for {@link OffsetPagedQuery} subclasses. Uses the self-type pattern so
     * subclass builders return the correct type from setter methods.
     *
     * <p>The builder doubles as the executor — the terminal methods {@link #page(int)} and
     * {@link #page(int, int)} build and execute the query in one step.
     *
     * @param <T> the domain type
     * @param <B> the concrete builder type (self-type)
     */
    public abstract static class Builder<T, B extends Builder<T, B>> {

        /** The base SQL query. */
        protected String sql;

        /** The SQL client to execute queries against. */
        protected SqlClient client;

        /** The base query parameters. */
        protected Tuple params;

        /** The row mapper. */
        protected RowMapper<T> mapper;

        /** The exception mapper for exception translation. */
        protected DbExceptionMapper exceptionMapper;

        /** The order keys for the ORDER BY clause. */
        protected List<OrderKey> orderKeys;

        /** The default page size. */
        protected int defaultPageSize = 20;

        /** The optional query clause (e.g., FOR UPDATE). */
        protected QueryClause queryClause;

        /** Creates a new builder with default values. */
        protected Builder() {}

        /**
         * Returns this builder cast to the concrete type.
         *
         * @return this builder
         */
        protected abstract B self();

        /**
         * Builds and returns the OffsetPagedQuery. Terminal methods call this internally.
         *
         * @return the built OffsetPagedQuery
         */
        protected abstract OffsetPagedQuery<T> build();

        // --- Configuration methods ---

        /**
         * Sets the base SQL query. Must not contain ORDER BY, LIMIT, or OFFSET — the framework
         * appends these automatically.
         *
         * @param sql the SQL string
         * @return this builder
         */
        public B sql(String sql) {
            this.sql = sql;
            return self();
        }

        /**
         * Sets the SQL client to use for execution. Overrides the default pool. Pass a
         * {@code SqlConnection} for transactional execution.
         *
         * @param client the SQL client (pool or connection)
         * @return this builder
         */
        public B on(SqlClient client) {
            this.client = client;
            return self();
        }

        /**
         * Sets the base parameters for the prepared query.
         *
         * @param params the query parameters
         * @return this builder
         */
        public B params(Tuple params) {
            this.params = params;
            return self();
        }

        /**
         * Sets the row mapper for converting database rows to domain objects.
         *
         * @param mapper the row mapper
         * @return this builder
         */
        public B mapping(RowMapper<T> mapper) {
            this.mapper = mapper;
            return self();
        }

        /**
         * Sets the exception mapper for exception translation. Typically set automatically by the
         * repository's {@code offsetPagedQuery()} factory method.
         *
         * @param exceptionMapper the exception mapper
         * @return this builder
         */
        public B exceptionMapper(DbExceptionMapper exceptionMapper) {
            this.exceptionMapper = exceptionMapper;
            return self();
        }

        /**
         * Sets the default page size. Used when no page size is specified in the
         * {@link #page(int)} call. Defaults to 20 if not set.
         *
         * @param pageSize the default page size
         * @return this builder
         */
        public B pageSize(int pageSize) {
            this.defaultPageSize = pageSize;
            return self();
        }

        /**
         * Sets the column ordering using the given column names. All columns use
         * {@link OrderDirection#ASC} and {@link NullHandling#DISALLOW}. At least one column is
         * required.
         *
         * @param columns the column names in sort priority order
         * @return this builder
         * @throws IllegalArgumentException if columns is empty, contains null/blank names, or duplicates
         */
        public B orderBy(String... columns) {
            return orderBy(OrderDirection.ASC, NullHandling.DISALLOW, columns);
        }

        /**
         * Sets the column ordering with a uniform direction. All columns use
         * {@link NullHandling#DISALLOW}. At least one column is required.
         *
         * @param direction the sort direction for all columns
         * @param columns   the column names in sort priority order
         * @return this builder
         * @throws IllegalArgumentException if columns is empty, contains null/blank names, or duplicates
         */
        public B orderBy(OrderDirection direction, String... columns) {
            return orderBy(direction, NullHandling.DISALLOW, columns);
        }

        /**
         * Sets the column ordering with a uniform direction and null handling policy. At least
         * one column is required.
         *
         * @param direction    the sort direction for all columns
         * @param nullHandling the null handling policy for all columns
         * @param columns      the column names in sort priority order
         * @return this builder
         * @throws IllegalArgumentException if columns is empty, contains null/blank names, or duplicates
         */
        public B orderBy(OrderDirection direction, NullHandling nullHandling, String... columns) {
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(nullHandling, "nullHandling must not be null");
            Objects.requireNonNull(columns, "columns must not be null");
            if (columns.length == 0) {
                throw new IllegalArgumentException("At least one column is required");
            }
            validateColumnNames(columns);
            List<OrderKey> keys = new ArrayList<>(columns.length);
            for (String col : columns) {
                keys.add(new OrderKey(col, direction, nullHandling));
            }
            this.orderKeys = List.copyOf(keys);
            return self();
        }

        /**
         * Sets the column ordering with per-column control over direction and null handling.
         * At least one order key is required.
         *
         * @param first the first order key
         * @param rest  additional order keys
         * @return this builder
         * @throws IllegalArgumentException if duplicate column names are found
         */
        public B orderBy(OrderKey first, OrderKey... rest) {
            Objects.requireNonNull(first, "first order key must not be null");
            List<OrderKey> keys = new ArrayList<>(1 + rest.length);
            keys.add(first);
            for (OrderKey key : rest) {
                Objects.requireNonNull(key, "order key must not be null");
                keys.add(key);
            }
            String[] columnNames = keys.stream().map(OrderKey::column).toArray(String[]::new);
            validateColumnNames(columnNames);
            this.orderKeys = List.copyOf(keys);
            return self();
        }

        /**
         * Sets the vendor-specific query clause appended after the OFFSET clause. Use vendor-specific
         * enums (e.g., {@code PgLockMode}).
         *
         * @param queryClause the query clause to append
         * @return this builder
         */
        public B queryClause(QueryClause queryClause) {
            this.queryClause = queryClause;
            return self();
        }

        // --- Terminal methods (delegate to built OffsetPagedQuery) ---

        /**
         * Executes the paged query for the given page using the configured default page size.
         *
         * @param page the zero-based page number
         * @return a future with the paged result
         */
        public Future<OffsetPagedResult<T>> page(int page) {
            return build().page(page);
        }

        /**
         * Executes the paged query for the given page and page size.
         *
         * @param page     the zero-based page number
         * @param pageSize the number of items per page
         * @return a future with the paged result
         */
        public Future<OffsetPagedResult<T>> page(int page, int pageSize) {
            return build().page(page, pageSize);
        }

        /** Validates that column names are non-null, non-blank, safe SQL identifiers, and unique. */
        private static void validateColumnNames(String[] columns) {
            SqlIdentifier.validateColumnNames(columns);
        }
    }
}
