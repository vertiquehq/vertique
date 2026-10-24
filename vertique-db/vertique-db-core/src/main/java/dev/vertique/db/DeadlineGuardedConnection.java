// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.sqlclient.PrepareOptions;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.SqlResult;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.spi.DatabaseMetadata;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * The connection handed to the work of a deadline-bounded transaction. It wraps the pooled
 * connection and refuses every statement that executes after the deadline.
 *
 * <p>The refusal happens at execute time, not when the statement is built: once the transaction
 * has been rolled back and its connection returned to the pool, a stale handle would send a late
 * statement straight to the physical connection, in autocommit or inside the pool's next holder's
 * transaction. Refusing before the statement reaches the handle means a continuation that resumes
 * after the deadline writes nothing anywhere.
 *
 * <p>{@link #prepare(String)} is always refused because a prepared statement executes outside this
 * guard's wrappers. {@link #begin()} and {@link #close()} are always refused because the work owns
 * neither the transaction nor the connection.
 */
final class DeadlineGuardedConnection implements SqlConnection {

    private final SqlConnection delegate;
    private volatile boolean expired;
    private final AtomicInteger refused = new AtomicInteger();

    DeadlineGuardedConnection(SqlConnection delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Marks the deadline as passed: every statement executed from now on is refused. */
    void expire() {
        expired = true;
    }

    /** Returns the number of statements refused after expiry. */
    int refusedStatements() {
        return refused.get();
    }

    private <R> Future<R> guard(java.util.function.Supplier<Future<R>> execution) {
        if (expired) {
            refused.incrementAndGet();
            return Future.failedFuture(
                    new TransactionDeadlineExceededException("Transaction deadline exceeded; statement refused"));
        }
        return execution.get();
    }

    private static <R> Future<R> usageError(String message) {
        return Future.failedFuture(new InvalidDataAccessUsageException(message));
    }

    // --- SqlClient ---

    @Override
    public Query<RowSet<Row>> query(String sql) {
        return new GuardedQuery<>(delegate.query(sql));
    }

    @Override
    public PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
        return new GuardedPreparedQuery<>(delegate.preparedQuery(sql));
    }

    @Override
    public PreparedQuery<RowSet<Row>> preparedQuery(String sql, PrepareOptions options) {
        return new GuardedPreparedQuery<>(delegate.preparedQuery(sql, options));
    }

    @Override
    public Future<Void> close() {
        return usageError("The connection of a deadline-bounded transaction cannot be closed by the work");
    }

    // --- SqlConnection ---

    @Override
    public Future<PreparedStatement> prepare(String sql) {
        return usageError("prepare() is unavailable in a deadline-bounded transaction");
    }

    @Override
    public Future<PreparedStatement> prepare(String sql, PrepareOptions options) {
        return usageError("prepare() is unavailable in a deadline-bounded transaction");
    }

    @Override
    public SqlConnection exceptionHandler(Handler<Throwable> handler) {
        delegate.exceptionHandler(handler);
        return this;
    }

    @Override
    public SqlConnection closeHandler(Handler<Void> handler) {
        delegate.closeHandler(handler);
        return this;
    }

    @Override
    public Future<Transaction> begin() {
        return usageError("begin() is unavailable: the transaction is owned by the deadline-bounded execution");
    }

    @Override
    public Transaction transaction() {
        return delegate.transaction();
    }

    @Override
    public boolean isSSL() {
        return delegate.isSSL();
    }

    @Override
    public DatabaseMetadata databaseMetadata() {
        return delegate.databaseMetadata();
    }

    // --- Statement wrappers ---

    private class GuardedQuery<T> implements Query<T> {
        private final Query<T> query;

        GuardedQuery(Query<T> query) {
            this.query = query;
        }

        @Override
        public Future<T> execute() {
            return guard(query::execute);
        }

        @Override
        public <R> Query<SqlResult<R>> collecting(Collector<Row, ?, R> collector) {
            return new GuardedQuery<>(query.collecting(collector));
        }

        @Override
        public <U> Query<RowSet<U>> mapping(Function<Row, U> mapper) {
            return new GuardedQuery<>(query.mapping(mapper));
        }
    }

    private final class GuardedPreparedQuery<T> extends GuardedQuery<T> implements PreparedQuery<T> {
        private final PreparedQuery<T> prepared;

        GuardedPreparedQuery(PreparedQuery<T> prepared) {
            super(prepared);
            this.prepared = prepared;
        }

        @Override
        public Future<T> execute(Tuple tuple) {
            return guard(() -> prepared.execute(tuple));
        }

        @Override
        public Future<T> executeBatch(List<Tuple> batch) {
            return guard(() -> prepared.executeBatch(batch));
        }

        @Override
        public <R> PreparedQuery<SqlResult<R>> collecting(Collector<Row, ?, R> collector) {
            return new GuardedPreparedQuery<>(prepared.collecting(collector));
        }

        @Override
        public <U> PreparedQuery<RowSet<U>> mapping(Function<Row, U> mapper) {
            return new GuardedPreparedQuery<>(prepared.mapping(mapper));
        }
    }
}
