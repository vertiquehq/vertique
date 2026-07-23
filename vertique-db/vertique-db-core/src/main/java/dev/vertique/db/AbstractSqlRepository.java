// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dev.vertique.db.query.OffsetPagedQuery;
import dev.vertique.db.query.PagedQuery;
import dev.vertique.db.query.Query;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.util.Objects;
import java.util.function.Function;

/**
 * Base implementation of {@link SqlRepository}. Provides {@link #transaction()} and {@link
 * #withConnection} with automatic exception translation via the {@link DbExceptionMapper}.
 *
 * <p>Vendor modules provide a concrete subclass (e.g., {@code PgSqlRepository}) that implements
 * the {@link #query()} factory method returning a dialect-specific builder.
 */
public abstract class AbstractSqlRepository implements SqlRepository {

    /** The connection pool used by this repository. */
    protected final Pool pool;

    /** The exception mapper used for exception translation. */
    protected final DbExceptionMapper exceptionMapper;

    /**
     * Creates a new repository.
     *
     * @param pool            the connection pool
     * @param exceptionMapper the exception mapper for exception translation
     */
    protected AbstractSqlRepository(Pool pool, DbExceptionMapper exceptionMapper) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");
    }

    /** {@inheritDoc} */
    @Override
    public Pool pool() {
        return pool;
    }

    /** {@inheritDoc} */
    @Override
    public DbExceptionMapper exceptionMapper() {
        return exceptionMapper;
    }

    /** {@inheritDoc} */
    @Override
    public abstract <T> Query.Builder<T, ?> query();

    /** {@inheritDoc} */
    @Override
    public abstract <T> PagedQuery.Builder<T, ?> pagedQuery();

    /** {@inheritDoc} */
    @Override
    public abstract <T> OffsetPagedQuery.Builder<T, ?> offsetPagedQuery();

    /** {@inheritDoc} */
    @Override
    public TransactionBuilder transaction() {
        return new TransactionBuilder(this);
    }

    /** {@inheritDoc} */
    @Override
    public <T> Future<T> withConnection(Function<SqlClient, Future<T>> fn) {
        return pool.withConnection(fn::apply)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "Connection operation failed")));
    }

    // --- Transaction execution ---

    /**
     * Executes a transaction with the given options and a generic {@code "Transaction failed"}
     * exception-translation context. Called internally by {@link TransactionBuilder#execute(Function)}.
     * Not part of the public API.
     *
     * @param options the transaction options (isolation level, read-only flag)
     * @param fn      the function to execute within the transaction
     * @param <T>     the result type
     * @return a future that completes with the function's result on success, or fails with a
     *         translated exception on failure
     */
    <T> Future<T> executeTransaction(TransactionOptions options, Function<SqlConnection, Future<T>> fn) {
        return executeTransaction(options, "Transaction failed", fn);
    }

    /**
     * Executes a transaction with the given options and a caller-supplied
     * {@code operationName} that is used as the {@link DbExceptionMapper} context on failure.
     * Called internally by {@link TransactionBuilder#execute(String, Function)}. Not part of the
     * public API.
     *
     * <p>Use this overload when the generic {@code "Transaction failed"} context is too weak for
     * diagnosing failures (e.g., when multiple distinct operations route through the same
     * repository and the exception mapper's translated messages need to disambiguate them).
     *
     * @param options       the transaction options (isolation level, read-only flag)
     * @param operationName a short identifier for the operation, used as the exception-translation
     *                      context (e.g., {@code "workflow_instances archiveBefore"})
     * @param fn            the function to execute within the transaction
     * @param <T>           the result type
     * @return a future that completes with the function's result on success, or fails with a
     *         translated exception on failure
     */
    <T> Future<T> executeTransaction(
            TransactionOptions options, String operationName, Function<SqlConnection, Future<T>> fn) {
        Objects.requireNonNull(operationName, "operationName");
        return pool.withTransaction(
                        conn -> applyTransactionOptions(conn, options).compose(v -> fn.apply(conn)))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, operationName)));
    }

    /**
     * Applies transaction options (isolation level, read-only mode) to the connection before the
     * function executes. Uses standard SQL statements compatible with most vendors. Vendor
     * subclasses may override for dialect-specific syntax.
     *
     * @param conn    the connection on which the transaction is running
     * @param options the transaction options to apply
     * @return a future that completes when all options have been applied
     */
    protected Future<Void> applyTransactionOptions(SqlConnection conn, TransactionOptions options) {
        Future<Void> setup = Future.succeededFuture();
        if (options.isolationLevel() != null) {
            setup = conn.query("SET TRANSACTION ISOLATION LEVEL "
                            + options.isolationLevel().sql())
                    .execute()
                    .mapEmpty();
        }
        if (options.readOnly()) {
            setup = setup.compose(
                    v -> conn.query("SET TRANSACTION READ ONLY").execute().mapEmpty());
        }
        return setup;
    }
}
