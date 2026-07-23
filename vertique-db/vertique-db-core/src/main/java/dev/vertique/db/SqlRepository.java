// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dev.vertique.db.query.OffsetPagedQuery;
import dev.vertique.db.query.PagedQuery;
import dev.vertique.db.query.Query;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.util.function.Function;

/**
 * Base interface for database repositories. Provides access to the connection pool, exception mapper,
 * factory methods for fluent query builders, and convenience methods for transactional and
 * connection-scoped operations.
 *
 * <p>Implement via {@link AbstractSqlRepository} or a vendor-specific subclass (e.g., {@code
 * PgSqlRepository}).
 */
public interface SqlRepository {

    /**
     * Returns the underlying connection pool.
     *
     * @return the pool
     */
    Pool pool();

    /**
     * Returns the exception mapper for exception translation.
     *
     * @return the exception mapper
     */
    DbExceptionMapper exceptionMapper();

    /**
     * Returns a new fluent query builder pre-configured with this repository's pool and failure
     * mapper. Vendor-specific implementations return a dialect-specific builder (e.g., {@code
     * PgQuery.Builder}).
     *
     * @param <T> the domain type (inferred from the mapper)
     * @return a new query builder
     */
    <T> Query.Builder<T, ?> query();

    /**
     * Returns a new fluent query builder pre-configured with the given SQL, pool, and failure
     * mapper. Equivalent to {@code this.<T>query().sql(sql)}.
     *
     * <pre>{@code
     * // Find one
     * query("SELECT id, name FROM items WHERE id = $1")
     *     .params(Tuple.of(id))
     *     .mapping(Item::fromRow)
     *     .one();
     *
     * // Find list
     * query("SELECT id, name FROM items WHERE status = $1")
     *     .params(Tuple.of("active"))
     *     .mapping(Item::fromRow)
     *     .list();
     *
     * // Execute mutation
     * query("DELETE FROM items WHERE expired_at < $1")
     *     .params(Tuple.of(threshold))
     *     .execute();
     *
     * // Inside a transaction with a lock
     * transaction().execute(conn ->
     *     query("SELECT id, name FROM items WHERE id = $1")
     *         .on(conn)
     *         .params(Tuple.of(id))
     *         .mapping(Item::fromRow)
     *         .queryClause(yourVendorLockMode)  // e.g., PgLockMode.FOR_UPDATE
     *         .one()
     * );
     * }</pre>
     *
     * @param sql the SQL statement
     * @param <T> the domain type (inferred from the mapper)
     * @return a new query builder with SQL set
     */
    default <T> Query.Builder<T, ?> query(String sql) {
        return this.<T>query().sql(sql);
    }

    /**
     * Returns a new fluent paged query builder pre-configured with this repository's pool and
     * exception mapper. Vendor-specific implementations return a dialect-specific builder (e.g.,
     * {@code PgPagedQuery.Builder}).
     *
     * @param <T> the domain type (inferred from the mapper)
     * @return a new paged query builder
     */
    <T> PagedQuery.Builder<T, ?> pagedQuery();

    /**
     * Returns a new fluent paged query builder pre-configured with the given SQL, pool, and failure
     * mapper. The base SQL must not contain ORDER BY, LIMIT, or OFFSET — the framework appends
     * these automatically.
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
     * // Subsequent pages
     * PageCursor cursor = PageCursor.fromToken(cursorToken);
     * pagedQuery("SELECT id, name, created_at FROM items WHERE status = $1")
     *     .params(Tuple.of("active"))
     *     .mapping(Item::fromRow)
     *     .orderBy("created_at", "id")
     *     .page(cursor);
     * }</pre>
     *
     * @param sql the SQL statement (without ORDER BY, LIMIT, or OFFSET)
     * @param <T> the domain type (inferred from the mapper)
     * @return a new paged query builder with SQL set
     */
    default <T> PagedQuery.Builder<T, ?> pagedQuery(String sql) {
        return this.<T>pagedQuery().sql(sql);
    }

    /**
     * Returns a new fluent offset-paged query builder pre-configured with this repository's pool
     * and exception mapper. Vendor-specific implementations return a dialect-specific builder (e.g.,
     * {@code PgOffsetPagedQuery.Builder}).
     *
     * @param <T> the domain type (inferred from the mapper)
     * @return a new offset-paged query builder
     */
    <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery();

    /**
     * Returns a new fluent offset-paged query builder pre-configured with the given SQL, pool, and
     * exception mapper. The base SQL must not contain ORDER BY, LIMIT, or OFFSET — the framework
     * appends these automatically.
     *
     * <pre>{@code
     * // First page (page 0)
     * offsetPagedQuery("SELECT id, name FROM items WHERE status = $1")
     *     .params(Tuple.of("active"))
     *     .mapping(Item::fromRow)
     *     .orderBy("name", "id")
     *     .pageSize(20)
     *     .page(0);
     * }</pre>
     *
     * @param sql the SQL statement (without ORDER BY, LIMIT, or OFFSET)
     * @param <T> the domain type (inferred from the mapper)
     * @return a new offset-paged query builder with SQL set
     */
    default <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery(String sql) {
        return this.<T>offsetPagedQuery().sql(sql);
    }

    /**
     * Returns a fluent builder for configuring and executing a transaction. Supports setting
     * isolation level and read-only mode.
     *
     * <pre>{@code
     * // Default (database-default isolation, read-write)
     * transaction().execute(conn -> doWork(conn));
     *
     * // Serializable
     * transaction().serializable().execute(conn -> doWork(conn));
     *
     * // Read-only with repeatable read
     * transaction().repeatableRead().readOnly().execute(conn -> doReadWork(conn));
     * }</pre>
     *
     * @return a new transaction builder
     */
    TransactionBuilder transaction();

    /**
     * Executes the given function on a pooled connection without a transaction. Translates
     * exceptions via the exception mapper.
     *
     * @param <T> the result type
     * @param fn  the function to execute with a connection
     * @return a future with the result
     */
    <T> Future<T> withConnection(Function<SqlClient, Future<T>> fn);
}
