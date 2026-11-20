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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Fluent builder and executor for keyset-paginated database queries. Provides stateless cursor-based
 * pagination using the fetch-N+1 pattern: fetches one extra row to detect whether more pages exist
 * without a separate COUNT query.
 *
 * <p>Instances are created via the {@link SqlRepository#pagedQuery(String)} factory method, which
 * returns a vendor-specific builder pre-configured with the repository's exception mapper and
 * connection pool.
 *
 * <h3>Keyset pagination</h3>
 *
 * <p>Unlike offset-based pagination ({@code OFFSET N}), keyset pagination uses a WHERE clause on
 * the sort columns to seek directly to the correct position. This is O(1) regardless of page depth,
 * compared to O(n) for offset pagination.
 *
 * <p>The base SQL must <b>not</b> contain {@code ORDER BY}, {@code LIMIT}, or {@code OFFSET}
 * clauses — the framework appends these automatically based on the configured order keys and page
 * size.
 *
 * <pre>{@code
 * // First page
 * pagedQuery("SELECT id, name, created_at FROM items WHERE status = $1")
 *     .params(Tuple.of("active"))
 *     .mapping(Item::fromRow)
 *     .orderBy("created_at", "id")
 *     .pageSize(20)
 *     .page();
 *
 * // Subsequent pages using cursor token from PagedResult
 * PageCursor cursor = PageCursor.fromToken(cursorToken);
 * pagedQuery("SELECT id, name, created_at FROM items WHERE status = $1")
 *     .params(Tuple.of("active"))
 *     .mapping(Item::fromRow)
 *     .orderBy("created_at", "id")
 *     .page(cursor);
 * }</pre>
 *
 * <h3>Composite order keys</h3>
 *
 * <p>When the primary sort column is not unique (e.g. {@code created_at}), add a unique tiebreaker
 * column (e.g. {@code id}) to ensure deterministic ordering. The framework generates row-value
 * comparison syntax: {@code (created_at, id) > ($2, $3)}.
 *
 * <h3>Backward pagination</h3>
 *
 * <p>The {@link PagedResult#previousCursorToken()} contains a backward cursor. When executed, each
 * order key's direction is reversed, results are fetched, then reversed in memory to maintain the
 * original display order.
 *
 * <h3>Limitations</h3>
 *
 * <ul>
 *   <li>The base SQL must be a simple SELECT without ORDER BY/LIMIT/OFFSET
 *   <li>WHERE clause detection uses string scanning — CTEs with internal WHERE clauses may cause
 *       false positives; wrap such CTEs in a subquery
 *   <li>Order key columns must appear in the SELECT list and be available by name via {@code
 *       Row.getValue(columnName)}
 * </ul>
 *
 * @param <T> the domain type returned by the row mapper
 * @see PageCursor
 * @see PagedResult
 * @see OrderDirection
 * @see SqlRepository#pagedQuery(String)
 */
@Slf4j
public abstract class PagedQuery<T> {

    private final String sql;
    private final SqlClient client;
    private final Tuple params;
    private final RowMapper<T> mapper;
    private final DbExceptionMapper exceptionMapper;
    private final List<OrderKey> orderKeys;
    private final int defaultPageSize;
    private final QueryClause queryClause;
    private final Set<String> uniqueKeyColumns;

    /** Guards against emitting the tiebreaker warning more than once per instance. */
    private volatile boolean tiebreakerWarningEmitted;

    /**
     * Constructs a PagedQuery from the given builder.
     *
     * @param builder the builder with all configuration
     */
    protected PagedQuery(Builder<T, ?> builder) {
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
        this.uniqueKeyColumns = builder.uniqueKeyColumns;
        // Validate that declared unique key columns appear in the order keys
        if (uniqueKeyColumns != null) {
            Set<String> orderKeyNames = new HashSet<>();
            for (OrderKey key : this.orderKeys) {
                orderKeyNames.add(key.column());
            }
            for (String col : uniqueKeyColumns) {
                if (!orderKeyNames.contains(col)) {
                    throw new IllegalArgumentException(
                            "uniqueKey column '" + col + "' does not appear in the orderBy columns");
                }
            }
        }
        this.tiebreakerWarningEmitted = false;
    }

    /**
     * Composes the final paginated SQL string. Vendor-specific subclasses implement parameter
     * placeholder syntax (e.g., {@code $N} for PostgreSQL), row-value comparison, and LIMIT clause.
     *
     * @param baseSql         the user-provided base SQL
     * @param orderKeys       the effective order keys (already reversed if backward navigation)
     * @param hasKeysetValues true if keyset values are present (not first page)
     * @param keysetValues    the cursor's keyset values (empty list for first page)
     * @param baseParamCount  the number of base query parameters (for offset in placeholders)
     * @param fetchCount      the number of rows to fetch (pageSize + 1)
     * @param queryClause     optional trailing clause (e.g., FOR UPDATE), may be null
     * @return the complete SQL to execute
     */
    protected abstract String buildPaginatedSql(
            String baseSql,
            List<OrderKey> orderKeys,
            boolean hasKeysetValues,
            List<Object> keysetValues,
            int baseParamCount,
            int fetchCount,
            QueryClause queryClause);

    /** Pattern matching a top-level WHERE clause (case-insensitive, word boundary). */
    private static final Pattern WHERE_PATTERN = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Detects whether the base SQL contains a top-level WHERE clause. Uses parenthesis-depth-aware
     * scanning to avoid false positives from WHERE clauses inside subqueries or CTEs.
     *
     * @param baseSql the base SQL to scan
     * @return true if a top-level WHERE clause is detected
     */
    protected boolean hasWhereClause(String baseSql) {
        return SqlScanner.containsAtTopLevel(baseSql, WHERE_PATTERN);
    }

    // -- Terminal methods --

    /**
     * Executes the paginated query for the first page using the configured default page size and sort
     * direction.
     *
     * @return a future with the paged result
     */
    public Future<PagedResult<T>> page() {
        return page(PageCursor.first(defaultPageSize));
    }

    /**
     * Executes the paginated query using the given cursor.
     *
     * <p>For first-page cursors (no keyset values), fetches the first page. For subsequent cursors,
     * applies the keyset condition. For backward cursors, reverses each order key's direction,
     * fetches, then reverses results in memory.
     *
     * @param cursor the page cursor (from {@link PageCursor#first}, {@link PageCursor#fromToken}, or
     *               {@link PagedResult#nextCursorToken()}/{@link PagedResult#previousCursorToken()})
     * @return a future with the paged result
     */
    public Future<PagedResult<T>> page(PageCursor cursor) {
        Objects.requireNonNull(cursor, "cursor must not be null");

        // Emit a one-time warning if no unique tiebreaker column has been declared
        if (uniqueKeyColumns == null && !tiebreakerWarningEmitted) {
            tiebreakerWarningEmitted = true;
            log.warn("PagedQuery has no declared unique tiebreaker column (use .uniqueKey()). "
                    + "Pagination may skip or duplicate rows if the ORDER BY columns are not unique.");
        }

        int pageSize = cursor.pageSize() > 0 ? cursor.pageSize() : defaultPageSize;
        boolean backward = cursor.backward();
        boolean hasKeysetValues = !cursor.isFirstPage();
        List<Object> keysetValues = cursor.keysetValues();

        if (hasKeysetValues && keysetValues.size() != orderKeys.size()) {
            return Future.failedFuture(new IllegalArgumentException("Cursor keyset values count (" + keysetValues.size()
                    + ") does not match order keys count (" + orderKeys.size() + ")"));
        }

        // Validate null keyset values against DISALLOW policy
        if (hasKeysetValues) {
            for (int i = 0; i < orderKeys.size(); i++) {
                if (keysetValues.get(i) == null
                        && !orderKeys.get(i).nullHandling().allowsNulls()) {
                    return Future.failedFuture(
                            new IllegalArgumentException("Null keyset value at position " + i + " for column '"
                                    + orderKeys.get(i).column()
                                    + "' but null handling is DISALLOW"));
                }
            }
        }

        // Reverse each key's direction for backward navigation
        List<OrderKey> effectiveKeys =
                backward ? orderKeys.stream().map(OrderKey::reverse).collect(Collectors.toList()) : orderKeys;

        int fetchCount = pageSize + 1;
        int baseParamCount = params.size();

        String finalSql = buildPaginatedSql(
                sql, effectiveKeys, hasKeysetValues, keysetValues, baseParamCount, fetchCount, queryClause);

        Tuple combinedParams = buildParams(hasKeysetValues, keysetValues);

        return client.preparedQuery(finalSql)
                .execute(combinedParams)
                .map(rows -> {
                    List<T> items = new ArrayList<>();
                    List<List<Object>> keysetValuesList = new ArrayList<>();

                    for (Row row : rows) {
                        items.add(mapper.map(row));
                        List<Object> keyset = new ArrayList<>(orderKeys.size());
                        for (OrderKey key : orderKeys) {
                            keyset.add(row.getValue(key.column()));
                        }
                        keysetValuesList.add(keyset);
                    }

                    // Validate keyset values against DISALLOW policy
                    for (int rowIdx = 0; rowIdx < keysetValuesList.size(); rowIdx++) {
                        List<Object> keyset = keysetValuesList.get(rowIdx);
                        for (int i = 0; i < orderKeys.size(); i++) {
                            if (keyset.get(i) == null
                                    && !orderKeys.get(i).nullHandling().allowsNulls()) {
                                throw new IllegalStateException("Null value in column '"
                                        + orderKeys.get(i).column()
                                        + "' at row " + rowIdx
                                        + " but null handling is DISALLOW. "
                                        + "Use .nullsFirst() or .nullsLast() on the OrderKey if nulls are expected.");
                            }
                        }
                    }

                    boolean hasMore = items.size() > pageSize;
                    if (hasMore) {
                        items = new ArrayList<>(items.subList(0, pageSize));
                        keysetValuesList = new ArrayList<>(keysetValuesList.subList(0, pageSize));
                    }

                    if (backward) {
                        Collections.reverse(items);
                        Collections.reverse(keysetValuesList);
                    }

                    return buildPagedResult(items, keysetValuesList, hasMore, hasKeysetValues, backward, pageSize);
                })
                .recover(t -> translated(t, "PagedQuery.page() failed: " + finalSql));
    }

    // -- Internal helpers --

    private Tuple buildParams(boolean hasKeysetValues, List<Object> keysetValues) {
        if (!hasKeysetValues) {
            return params;
        }
        Tuple combined = Tuple.tuple();
        for (int i = 0; i < params.size(); i++) {
            combined.addValue(params.getValue(i));
        }
        for (Object v : keysetValues) {
            if (v != null) {
                combined.addValue(v);
            }
        }
        return combined;
    }

    private PagedResult<T> buildPagedResult(
            List<T> items,
            List<List<Object>> keysetValuesList,
            boolean hasMore,
            boolean hasKeysetValues,
            boolean backward,
            int pageSize) {

        if (items.isEmpty()) {
            return PagedResult.empty();
        }

        String nextToken = null;
        String prevToken = null;

        List<Object> lastKeyset = keysetValuesList.get(items.size() - 1);
        List<Object> firstKeyset = keysetValuesList.get(0);

        if (backward) {
            // After backward fetch + reverse:
            // - Always provide a next cursor (we came from the forward direction)
            nextToken = PageCursor.of(lastKeyset, pageSize, false).toToken();
            // - Previous exists only if we fetched more than pageSize (more pages behind)
            if (hasMore) {
                prevToken = PageCursor.of(firstKeyset, pageSize, true).toToken();
            }
        } else {
            // Forward navigation:
            // - Next cursor if there are more rows
            if (hasMore) {
                nextToken = PageCursor.of(lastKeyset, pageSize, false).toToken();
            }
            // - Previous cursor if we're not on the first page
            if (hasKeysetValues) {
                prevToken = PageCursor.of(firstKeyset, pageSize, true).toToken();
            }
        }

        return new PagedResult<>(List.copyOf(items), nextToken, prevToken);
    }

    private <X> Future<X> translated(Throwable t, String context) {
        return Future.failedFuture(exceptionMapper.translate(t, context));
    }

    // -- Accessors --

    /** The base SQL query (without ORDER BY, LIMIT, or keyset condition). */
    public String sql() {
        return sql;
    }

    /** The SQL client (pool or connection) to execute against. */
    public SqlClient client() {
        return client;
    }

    /** The base query parameters. */
    public Tuple params() {
        return params;
    }

    /** The default page size. */
    public int defaultPageSize() {
        return defaultPageSize;
    }

    /**
     * Returns the order keys that define the keyset pagination sort order.
     *
     * @return an unmodifiable list of order keys
     */
    public List<OrderKey> orderKeys() {
        return orderKeys;
    }

    /**
     * Abstract builder for {@link PagedQuery} subclasses. Uses the self-type pattern so subclass
     * builders return the correct type from setter methods.
     *
     * <p>The builder doubles as the executor — the terminal method {@link #page(PageCursor)} builds
     * and executes the query in one step.
     *
     * @param <T> the domain type
     * @param <B> the concrete builder type (self-type)
     */
    public abstract static class Builder<T, B extends Builder<T, B>> {

        protected String sql;
        protected SqlClient client;
        protected Tuple params;
        protected RowMapper<T> mapper;
        protected DbExceptionMapper exceptionMapper;
        protected List<OrderKey> orderKeys;
        protected int defaultPageSize = 20;
        protected QueryClause queryClause;
        protected Set<String> uniqueKeyColumns;

        protected Builder() {}

        /** Returns this builder cast to the concrete type. */
        protected abstract B self();

        /**
         * Builds and returns the PagedQuery. Terminal methods call this internally.
         *
         * @return the built PagedQuery
         */
        protected abstract PagedQuery<T> build();

        // -- Configuration methods --

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
         * Sets the SQL client to use for execution. Overrides the default pool. Pass a {@code
         * SqlConnection} for transactional execution.
         *
         * @param client the SQL client (pool or connection)
         * @return this builder
         */
        public B on(SqlClient client) {
            this.client = client;
            return self();
        }

        /**
         * Sets the base parameters for the prepared query. These are the parameters for the
         * user-provided WHERE clause; keyset parameters are appended automatically.
         *
         * @param params the query parameters
         * @return this builder
         */
        public B params(Tuple params) {
            this.params = params;
            return self();
        }

        /**
         * Sets the row mapper for converting database rows to domain objects. Required for paged
         * queries.
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
         * repository's {@code pagedQuery()} factory method.
         *
         * @param exceptionMapper the exception mapper
         * @return this builder
         */
        public B exceptionMapper(DbExceptionMapper exceptionMapper) {
            this.exceptionMapper = exceptionMapper;
            return self();
        }

        /**
         * Sets the default page size. Used when no page size is specified in the cursor. Defaults to
         * 20 if not set.
         *
         * @param pageSize the default page size
         * @return this builder
         */
        public B pageSize(int pageSize) {
            this.defaultPageSize = pageSize;
            return self();
        }

        /**
         * Sets the keyset column ordering using the given column names. All columns use
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
         * Sets the keyset column ordering with a uniform direction. All columns use
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
         * Sets the keyset column ordering with a uniform direction and null handling policy. At least
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
         * Sets the keyset column ordering with per-column control over direction and null handling.
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

        private static void validateColumnNames(String[] columns) {
            SqlIdentifier.validateColumnNames(columns);
        }

        /**
         * Sets the vendor-specific query clause appended after the LIMIT clause. Use vendor-specific
         * enums (e.g., {@code PgLockMode}).
         *
         * @param queryClause the query clause to append
         * @return this builder
         */
        public B queryClause(QueryClause queryClause) {
            this.queryClause = queryClause;
            return self();
        }

        /**
         * Declares the column(s) that form a unique key in the result set, serving as tiebreakers
         * for stable ordering. When set, suppresses the tiebreaker warning emitted by
         * {@link PagedQuery#page(PageCursor)} when no unique key is declared.
         *
         * <p>At least one column must be specified, and every declared column must appear in the
         * {@code orderBy} configuration. This constraint is enforced at build time.
         *
         * <p>Example:
         * <pre>{@code
         * pagedQuery("SELECT id, name, created_at FROM items")
         *     .mapping(Item::fromRow)
         *     .orderBy("created_at", "id")
         *     .uniqueKey("id")   // id is the unique tiebreaker
         *     .page();
         * }</pre>
         *
         * @param columns the unique key column name(s); must appear in the orderBy columns
         * @return this builder
         * @throws NullPointerException     if {@code columns} is null
         * @throws IllegalArgumentException if {@code columns} is empty or contains invalid identifiers
         */
        public B uniqueKey(String... columns) {
            Objects.requireNonNull(columns, "columns");
            if (columns.length == 0) {
                throw new IllegalArgumentException("uniqueKey requires at least one column");
            }
            for (String col : columns) {
                SqlIdentifier.validate(col);
            }
            this.uniqueKeyColumns = Set.of(columns);
            return self();
        }

        // -- Terminal methods (delegate to built PagedQuery) --

        /**
         * Executes the paged query for the first page using the configured default page size and
         * sort direction.
         *
         * @return a future with the paged result
         */
        public Future<PagedResult<T>> page() {
            return build().page();
        }

        /**
         * Executes the paged query using the given cursor.
         *
         * @param cursor the page cursor
         * @return a future with the paged result
         */
        public Future<PagedResult<T>> page(PageCursor cursor) {
            return build().page(cursor);
        }
    }
}
