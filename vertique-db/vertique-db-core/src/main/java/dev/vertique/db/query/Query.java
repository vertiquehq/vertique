// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import dev.vertique.db.DbExceptionMapper;
import dev.vertique.db.RowMapper;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.IncorrectResultSizeDataAccessException;
import dev.vertique.db.exception.InvalidDataAccessUsageException;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.SqlResult;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Fluent query builder and executor for non-paginated database operations. Provides terminal
 * methods that execute SQL, map results, and automatically translate exceptions via {@link
 * DbExceptionMapper}.
 *
 * <p>Instances are created via the {@link SqlRepository#query(String)} factory method, which
 * returns a vendor-specific builder pre-configured with the repository's exception mapper and
 * connection pool. The builder supports both pool-level and connection-level (transactional)
 * execution:
 *
 * <pre>{@code
 * // Query a single row — returns Future<Optional<Item>>
 * query("SELECT id, name FROM items WHERE id = $1")
 *     .params(Tuple.of(itemId))
 *     .mapping(row -> new Item(row.getUUID("id"), row.getString("name")))
 *     .one();
 *
 * // Query a list — returns Future<List<Item>>
 * query("SELECT id, name FROM items WHERE status = $1")
 *     .params(Tuple.of("active"))
 *     .mapping(row -> new Item(row.getUUID("id"), row.getString("name")))
 *     .list();
 *
 * // Execute a mutation — returns Future<Integer> (rowCount)
 * query("DELETE FROM items WHERE status = $1")
 *     .params(Tuple.of("expired"))
 *     .execute();
 *
 * // Mutation with RETURNING — returns Future<Item>, fails if 0 rows
 * query("INSERT INTO items (id, name) VALUES ($1, $2) RETURNING *")
 *     .params(Tuple.of(itemId, name))
 *     .mapping(Item::fromRow)
 *     .returning();
 *
 * // Inside a transaction — use .on(conn)
 * transaction().execute(conn ->
 *     query("SELECT * FROM items WHERE id = $1")
 *         .on(conn)
 *         .params(Tuple.of(itemId))
 *         .mapping(Item::fromRow)
 *         .queryClause(yourVendorLockMode)  // e.g., PgLockMode.FOR_UPDATE
 *         .one()
 *         .flatMap(item -> query("UPDATE items SET name = $1 WHERE id = $2")
 *             .on(conn)
 *             .params(Tuple.of(newName, itemId))
 *             .execute())
 * );
 *
 * // Batch execution
 * query("INSERT INTO items (id, name) VALUES ($1, $2)")
 *     .batch(tuples)
 *     .execute();
 * }</pre>
 *
 * @param <T> the domain type returned by the row mapper (if set)
 * @see SqlRepository#query(String)
 * @see SqlRepository#query()
 */
public abstract class Query<T> {

    private final String sql;
    private final SqlClient client;
    private final Tuple params;
    private final RowMapper<T> mapper;
    private final DbExceptionMapper exceptionMapper;
    private final QueryClause queryClause;
    private final List<Tuple> batch;

    /**
     * Constructs a Query from the given builder.
     *
     * @param builder the builder with all configuration
     */
    protected Query(Builder<T, ?> builder) {
        this.sql = Objects.requireNonNull(builder.sql, "sql must not be null");
        this.client = Objects.requireNonNull(builder.client, "client must not be null");
        this.params = builder.params != null ? builder.params : Tuple.tuple();
        this.mapper = builder.mapper;
        this.exceptionMapper = Objects.requireNonNull(builder.exceptionMapper, "exceptionMapper must not be null");
        this.queryClause = builder.queryClause;
        this.batch = builder.batch;
    }

    /**
     * Builds the final SQL string by appending the {@link QueryClause} (if any) to the base SQL.
     * Vendor-specific subclasses override this to validate that the base SQL does not contain
     * vendor-specific trailing clauses.
     *
     * @return the complete SQL to execute
     */
    public String buildSql() {
        if (queryClause != null) {
            return sql + " " + queryClause.sql();
        }
        return sql;
    }

    // -- Terminal methods --

    /**
     * Executes the query and returns the first row mapped to a domain object, or an empty Optional
     * if no rows match.
     *
     * <p>Requires a mapper to be set via {@link Builder#mapping(RowMapper)}.
     *
     * @return a future with the optional result
     * @throws IllegalStateException if no mapper was configured
     */
    public Future<Optional<T>> one() {
        requireMapper("one()");
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .mapping(mapper::map)
                .execute(params)
                .map(this::firstRowAsOptional)
                .recover(t -> translated(t, "Query.one() failed: " + finalSql));
    }

    /**
     * Executes the query and returns all rows mapped to a list of domain objects. Returns an empty
     * list if no rows match.
     *
     * <p>Requires a mapper to be set via {@link Builder#mapping(RowMapper)}.
     *
     * @return a future with the list of results
     * @throws IllegalStateException if no mapper was configured
     */
    public Future<List<T>> list() {
        requireMapper("list()");
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .collecting(Collectors.mapping(mapper::map, Collectors.toList()))
                .execute(params)
                .map(SqlResult::value)
                .recover(t -> translated(t, "Query.list() failed: " + finalSql));
    }

    /**
     * Executes a mutation (INSERT, UPDATE, DELETE) and returns the number of affected rows. For
     * batch operations (configured via {@link Builder#batch(List)}), returns the total row count
     * across all batch entries.
     *
     * @return a future with the affected row count
     */
    public Future<Integer> execute() {
        if (batch != null) {
            return executeBatch();
        }
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .execute(params)
                .map(SqlResult::rowCount)
                .recover(t -> translated(t, "Query.execute() failed: " + finalSql));
    }

    /**
     * Executes a mutation with a RETURNING clause and maps the first returned row to a domain
     * object. Fails with {@link DataAccessException} if the mutation affected zero rows (e.g. an
     * UPDATE WHERE clause matched nothing).
     *
     * <p>Requires a mapper to be set via {@link Builder#mapping(RowMapper)}.
     *
     * @return a future with the mapped returned row
     * @throws IllegalStateException if no mapper was configured
     * @see #returningOptional()
     */
    public Future<T> returning() {
        requireMapper("returning()");
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .mapping(mapper::map)
                .execute(params)
                .flatMap(rows -> {
                    var iterator = rows.iterator();
                    if (!iterator.hasNext()) {
                        return Future.failedFuture(
                                new DataAccessException("Expected a returned row but got none: " + finalSql));
                    }
                    T first = iterator.next();
                    if (iterator.hasNext()) {
                        return Future.failedFuture(new IncorrectResultSizeDataAccessException(
                                "Expected exactly 1 returned row but got more", 1, -1));
                    }
                    return Future.succeededFuture(first);
                })
                .recover(t -> translated(t, "Query.returning() failed: " + finalSql));
    }

    /**
     * Executes a mutation with a RETURNING clause and maps the first returned row to a domain
     * object, or returns an empty Optional if no rows were affected. Useful for INSERT...ON CONFLICT
     * DO NOTHING where zero returned rows is a valid outcome.
     *
     * <p>Requires a mapper to be set via {@link Builder#mapping(RowMapper)}.
     *
     * @return a future with the optional mapped result
     * @throws IllegalStateException if no mapper was configured
     * @see #returning()
     */
    public Future<Optional<T>> returningOptional() {
        requireMapper("returningOptional()");
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .mapping(mapper::map)
                .execute(params)
                .map(this::firstRowAsOptional)
                .recover(t -> translated(t, "Query.returningOptional() failed: " + finalSql));
    }

    /**
     * Executes a {@code SELECT COUNT(*)} query and returns the count as a long. The query must
     * return exactly one row with a single numeric column (e.g. {@code SELECT COUNT(*) FROM ...}).
     * Returns {@code 0L} if no rows are returned.
     *
     * <pre>{@code
     * query("SELECT COUNT(*) FROM items WHERE status = $1")
     *     .params(Tuple.of("active"))
     *     .count();
     * }</pre>
     *
     * @return a future with the count value
     */
    public Future<Long> count() {
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .execute(params)
                .map(rows -> {
                    var iterator = rows.iterator();
                    if (!iterator.hasNext()) {
                        return 0L;
                    }
                    Row row = iterator.next();
                    if (row.size() != 1) {
                        throw new InvalidDataAccessUsageException(
                                "count() expects a single-column result, got " + row.size() + " columns");
                    }
                    return row.getLong(0);
                })
                .recover(t -> translated(t, "Query.count() failed: " + finalSql));
    }

    /**
     * Executes the query and returns the raw {@link RowSet} without any mapping. This is an escape
     * hatch for cases where none of the typed terminal methods fit. The exception mapper is still
     * applied.
     *
     * @return a future with the raw result set
     */
    public Future<RowSet<Row>> rows() {
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .execute(params)
                .recover(t -> translated(t, "Query.rows() failed: " + finalSql));
    }

    /**
     * Prepares the query and returns a streaming cursor that maps rows to domain objects. The
     * returned stream uses backpressure-aware demand mode — call {@code fetch(n)} or {@code
     * resume()} to receive items.
     *
     * <p>Requires a mapper and a {@link SqlConnection} (not a {@link io.vertx.sqlclient.Pool}). Use
     * this inside {@code withConnection()} or {@code transaction().execute()}.
     *
     * <p>The underlying {@link io.vertx.sqlclient.PreparedStatement} is closed automatically when
     * the stream ends or encounters an error. Callers should set both a {@code handler} and an
     * {@code endHandler} (or call {@code close()}) to ensure cleanup.
     *
     * <pre>{@code
     * repository.transaction().execute(conn ->
     *     repository.<Item>query("SELECT id, name FROM items")
     *         .on(conn)
     *         .mapping(Item::fromRow)
     *         .stream(100)
     *         .compose(stream -> {
     *             Promise<Void> promise = Promise.promise();
     *             stream.handler(item -> process(item))
     *                   .endHandler(promise::complete)
     *                   .exceptionHandler(promise::fail);
     *             return promise.future();
     *         })
     * );
     * }</pre>
     *
     * @param fetchSize the number of rows to fetch per batch from the database cursor
     * @return a future with the mapped read stream
     * @throws IllegalStateException if no mapper was configured
     */
    public Future<ReadStream<T>> stream(int fetchSize) {
        requireMapper("stream()");
        if (fetchSize <= 0) {
            return Future.failedFuture(new IllegalArgumentException("fetchSize must be positive, got: " + fetchSize));
        }
        if (!(client instanceof SqlConnection conn)) {
            return Future.failedFuture(new InvalidDataAccessUsageException(
                    "stream() requires a SqlConnection (use .on(conn) inside a transaction or withConnection())"));
        }
        var finalSql = buildSql();
        return conn.prepare(finalSql)
                .map(ps -> (ReadStream<T>) new MappedRowStream<>(
                        ps.createStream(fetchSize, params), mapper, ps, exceptionMapper, finalSql))
                .recover(t -> translated(t, "Query.stream() failed: " + finalSql));
    }

    // -- Internal execution --

    private Future<Integer> executeBatch() {
        if (queryClause != null) {
            return Future.failedFuture(
                    new InvalidDataAccessUsageException("queryClause cannot be combined with batch execution"));
        }
        var finalSql = buildSql();
        return client.preparedQuery(finalSql)
                .executeBatch(batch)
                .map(rows -> {
                    int total = 0;
                    RowSet<Row> current = rows;
                    while (current != null) {
                        total += current.rowCount();
                        current = current.next();
                    }
                    return total;
                })
                .recover(t -> translated(t, "Query.execute() batch failed: " + finalSql));
    }

    private Optional<T> firstRowAsOptional(RowSet<T> rows) {
        var iterator = rows.iterator();
        if (!iterator.hasNext()) {
            return Optional.empty();
        }
        T first = iterator.next();
        if (iterator.hasNext()) {
            throw new IncorrectResultSizeDataAccessException("Expected at most 1 row but got more", 1, -1);
        }
        return Optional.of(first);
    }

    private <X> Future<X> translated(Throwable t, String context) {
        return Future.failedFuture(exceptionMapper.translate(t, context));
    }

    private void requireMapper(String methodName) {
        if (mapper == null) {
            throw new IllegalStateException("A mapper must be configured via .mapping() before calling " + methodName);
        }
    }

    // -- Accessors --

    /** The base SQL query. */
    public String sql() {
        return sql;
    }

    /** The SQL client (pool or connection) to execute against. */
    public SqlClient client() {
        return client;
    }

    /** The bound parameters. */
    public Tuple params() {
        return params;
    }

    /** The row mapper, or null if not set. */
    public RowMapper<T> mapper() {
        return mapper;
    }

    /** The query clause, or null if not set. */
    public QueryClause queryClause() {
        return queryClause;
    }

    /**
     * Abstract builder for {@link Query} subclasses. Uses the self-type pattern so subclass builders
     * return the correct type from setter methods.
     *
     * <p>The builder doubles as the executor — terminal methods ({@link #one()}, {@link #list()},
     * {@link #execute()}, {@link #returning()}) build and execute the query in one step, avoiding an
     * unnecessary intermediate object.
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
        protected QueryClause queryClause;
        protected List<Tuple> batch;

        protected Builder() {}

        /** Returns this builder cast to the concrete type. */
        protected abstract B self();

        /**
         * Builds and returns the Query. Terminal methods ({@link #one()}, etc.) call this
         * internally.
         */
        protected abstract Query<T> build();

        // -- Configuration methods --

        /**
         * Sets the base SQL query.
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
         * SqlConnection} from {@code pool.withTransaction()} for transactional execution.
         *
         * @param client the SQL client (pool or connection)
         * @return this builder
         */
        public B on(SqlClient client) {
            this.client = client;
            return self();
        }

        /**
         * Sets the parameters for the prepared query. If not set, defaults to an empty tuple.
         *
         * @param params the query parameters
         * @return this builder
         */
        public B params(Tuple params) {
            this.params = params;
            return self();
        }

        /**
         * Sets the row mapper for converting database rows to domain objects. Required for {@link
         * #one()}, {@link #list()}, and {@link #returning()}.
         *
         * @param mapper the row mapper
         * @return this builder
         */
        public B mapping(RowMapper<T> mapper) {
            this.mapper = mapper;
            return self();
        }

        /**
         * Sets the exception mapper for exception translation. This is required — the query will
         * reject a null exception mapper at build time. Typically set automatically by the
         * repository's {@code query()} factory method.
         *
         * @param exceptionMapper the exception mapper
         * @return this builder
         */
        public B exceptionMapper(DbExceptionMapper exceptionMapper) {
            this.exceptionMapper = exceptionMapper;
            return self();
        }

        /**
         * Sets the vendor-specific query clause appended after the base SQL. Use vendor-specific
         * enums (e.g., {@code PgLockMode}). Cannot be combined with {@link #batch(List)}.
         *
         * @param queryClause the query clause to append
         * @return this builder
         */
        public B queryClause(QueryClause queryClause) {
            this.queryClause = queryClause;
            return self();
        }

        /**
         * Sets the batch parameter list for batch execution. When set, {@link #execute()} uses
         * {@code preparedQuery.executeBatch()}. Cannot be combined with {@link
         * #queryClause(QueryClause)}.
         *
         * <pre>{@code
         * var tuples = items.stream()
         *     .map(item -> Tuple.of(item.id(), item.name()))
         *     .toList();
         *
         * query("INSERT INTO items (id, name) VALUES ($1, $2)")
         *     .batch(tuples)
         *     .execute();
         * }</pre>
         *
         * @param batch the list of parameter tuples
         * @return this builder
         */
        public B batch(List<Tuple> batch) {
            this.batch = batch;
            return self();
        }

        // -- Terminal methods (delegate to built Query) --

        /**
         * Executes the query and returns the first row mapped to a domain object, or an empty
         * Optional if no rows match.
         *
         * @return a future with the optional result
         * @see Query#one()
         */
        public Future<Optional<T>> one() {
            return build().one();
        }

        /**
         * Executes the query and returns all rows mapped to a list of domain objects.
         *
         * @return a future with the list of results
         * @see Query#list()
         */
        public Future<List<T>> list() {
            return build().list();
        }

        /**
         * Executes a mutation and returns the affected row count.
         *
         * @return a future with the affected row count
         * @see Query#execute()
         */
        public Future<Integer> execute() {
            return build().execute();
        }

        /**
         * Executes a mutation with RETURNING and maps the first returned row. Fails if no rows are
         * returned.
         *
         * @return a future with the mapped returned row
         * @see Query#returning()
         */
        public Future<T> returning() {
            return build().returning();
        }

        /**
         * Executes a mutation with RETURNING and maps the first returned row, or returns an empty
         * Optional if no rows were affected.
         *
         * @return a future with the optional mapped result
         * @see Query#returningOptional()
         */
        public Future<Optional<T>> returningOptional() {
            return build().returningOptional();
        }

        /**
         * Executes a {@code SELECT COUNT(*)} query and returns the count.
         *
         * @return a future with the count value
         * @see Query#count()
         */
        public Future<Long> count() {
            return build().count();
        }

        /**
         * Executes the query and returns the raw RowSet.
         *
         * @return a future with the raw result set
         * @see Query#rows()
         */
        public Future<RowSet<Row>> rows() {
            return build().rows();
        }

        /**
         * Prepares and returns a streaming cursor that maps rows to domain objects.
         *
         * @param fetchSize the number of rows to fetch per batch
         * @return a future with the mapped read stream
         * @see Query#stream(int)
         */
        public Future<ReadStream<T>> stream(int fetchSize) {
            return build().stream(fetchSize);
        }
    }
}
