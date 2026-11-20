// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import dev.vertique.db.DbExceptionMapper;
import dev.vertique.db.RowMapper;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The configuration both paged-query builders share: the base SQL, the client, the parameters, the row mapper, the
 * exception mapper, the default page size, the ordering and the vendor query clause. {@link PagedQuery.Builder} and
 * {@link OffsetPagedQuery.Builder} add what is particular to keyset and offset pagination, so the two accept and
 * reject the same input in the same way.
 *
 * @param <T> the domain type
 * @param <B> the concrete builder type (self-type)
 */
abstract class AbstractPagedQueryBuilder<T, B extends AbstractPagedQueryBuilder<T, B>> {

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

    /** Creates a builder with default values. */
    protected AbstractPagedQueryBuilder() {}

    /**
     * Returns this builder cast to the concrete type.
     *
     * @return this builder
     */
    protected abstract B self();

    /**
     * Sets the base SQL query. Must not contain ORDER BY, LIMIT, or OFFSET — the framework appends these
     * automatically.
     *
     * @param sql the SQL string
     * @return this builder
     */
    public B sql(String sql) {
        this.sql = sql;
        return self();
    }

    /**
     * Sets the SQL client to use for execution. Overrides the default pool. Pass a {@code SqlConnection} for
     * transactional execution.
     *
     * @param client the SQL client (pool or connection)
     * @return this builder
     */
    public B on(SqlClient client) {
        this.client = client;
        return self();
    }

    /**
     * Sets the base parameters for the prepared query. These are the parameters of the user-provided SQL; any
     * parameters the framework adds for pagination come after them.
     *
     * @param params the query parameters
     * @return this builder
     */
    public B params(Tuple params) {
        this.params = params;
        return self();
    }

    /**
     * Sets the row mapper for converting database rows to domain objects. Required.
     *
     * @param mapper the row mapper
     * @return this builder
     */
    public B mapping(RowMapper<T> mapper) {
        this.mapper = mapper;
        return self();
    }

    /**
     * Sets the exception mapper for exception translation. Typically set automatically by the repository's paged
     * query factory method.
     *
     * @param exceptionMapper the exception mapper
     * @return this builder
     */
    public B exceptionMapper(DbExceptionMapper exceptionMapper) {
        this.exceptionMapper = exceptionMapper;
        return self();
    }

    /**
     * Sets the default page size, used when the terminal call names none. Defaults to 20 if not set.
     *
     * @param pageSize the default page size
     * @return this builder
     */
    public B pageSize(int pageSize) {
        this.defaultPageSize = pageSize;
        return self();
    }

    /**
     * Sets the column ordering using the given column names. All columns use {@link OrderDirection#ASC} and
     * {@link NullHandling#DISALLOW}. At least one column is required.
     *
     * @param columns the column names in sort priority order
     * @return this builder
     * @throws NullPointerException if the columns, or one of them, is null
     * @throws IllegalArgumentException if no column is given or a column is repeated
     * @throws dev.vertique.db.exception.InvalidDataAccessUsageException if a column name is blank or is not a safe
     *     SQL identifier
     */
    public B orderBy(String... columns) {
        return orderBy(OrderDirection.ASC, NullHandling.DISALLOW, columns);
    }

    /**
     * Sets the column ordering with a uniform direction. All columns use {@link NullHandling#DISALLOW}. At least one
     * column is required.
     *
     * @param direction the sort direction for all columns
     * @param columns the column names in sort priority order
     * @return this builder
     * @throws NullPointerException if the direction, the columns, or one of the columns is null
     * @throws IllegalArgumentException if no column is given or a column is repeated
     * @throws dev.vertique.db.exception.InvalidDataAccessUsageException if a column name is blank or is not a safe
     *     SQL identifier
     */
    public B orderBy(OrderDirection direction, String... columns) {
        return orderBy(direction, NullHandling.DISALLOW, columns);
    }

    /**
     * Sets the column ordering with a uniform direction and null handling policy. At least one column is required.
     *
     * @param direction the sort direction for all columns
     * @param nullHandling the null handling policy for all columns
     * @param columns the column names in sort priority order
     * @return this builder
     * @throws NullPointerException if the direction, the policy, the columns, or one of the columns is null
     * @throws IllegalArgumentException if no column is given or a column is repeated
     * @throws dev.vertique.db.exception.InvalidDataAccessUsageException if a column name is blank or is not a safe
     *     SQL identifier
     */
    public B orderBy(OrderDirection direction, NullHandling nullHandling, String... columns) {
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(nullHandling, "nullHandling must not be null");
        Objects.requireNonNull(columns, "columns must not be null");
        if (columns.length == 0) {
            throw new IllegalArgumentException("At least one column is required");
        }
        SqlIdentifier.validateColumnNames(columns);
        List<OrderKey> keys = new ArrayList<>(columns.length);
        for (String col : columns) {
            keys.add(new OrderKey(col, direction, nullHandling));
        }
        this.orderKeys = List.copyOf(keys);
        return self();
    }

    /**
     * Sets the column ordering with per-column control over direction and null handling. At least one order key is
     * required.
     *
     * @param first the first order key
     * @param rest additional order keys
     * @return this builder
     * @throws NullPointerException if an order key, or the array of the rest, is null
     * @throws IllegalArgumentException if two order keys name the same column
     */
    public B orderBy(OrderKey first, OrderKey... rest) {
        Objects.requireNonNull(first, "first order key must not be null");
        Objects.requireNonNull(rest, "rest must not be null");
        List<OrderKey> keys = new ArrayList<>(1 + rest.length);
        keys.add(first);
        for (OrderKey key : rest) {
            Objects.requireNonNull(key, "order key must not be null");
            keys.add(key);
        }
        SqlIdentifier.validateColumnNames(keys.stream().map(OrderKey::column).toArray(String[]::new));
        this.orderKeys = List.copyOf(keys);
        return self();
    }

    /**
     * Sets the vendor-specific query clause appended after the pagination clause. Use vendor-specific enums (e.g.,
     * {@code PgLockMode}).
     *
     * @param queryClause the query clause to append
     * @return this builder
     */
    public B queryClause(QueryClause queryClause) {
        this.queryClause = queryClause;
        return self();
    }
}
