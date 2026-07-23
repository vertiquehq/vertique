// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import java.util.Objects;
import java.util.function.Function;

/**
 * Fluent builder for configuring and executing a database transaction with specific options.
 *
 * <p>Obtained via {@link SqlRepository#transaction()}. The builder is single-use — call
 * {@link #execute(Function)} once to run the transaction.
 *
 * <pre>{@code
 * // Serializable read-only transaction
 * repository.transaction()
 *     .serializable()
 *     .readOnly()
 *     .execute(conn -> queryWork(conn));
 *
 * // Repeatable read with savepoint
 * repository.transaction()
 *     .repeatableRead()
 *     .execute(conn ->
 *         doPartOne(conn)
 *             .compose(v -> Savepoint.execute(conn, "sp1", c -> doRiskyWork(c)))
 *             .recover(err -> fallback(conn))
 *     );
 * }</pre>
 *
 * <p>The transaction auto-commits on success and auto-rolls back on failure. Exceptions are
 * translated via the repository's {@link DbExceptionMapper}.
 */
public class TransactionBuilder {

    private final AbstractSqlRepository repository;
    private IsolationLevel isolationLevel;
    private boolean readOnly;

    /**
     * Creates a new builder for the given repository. Package-private: only
     * {@link AbstractSqlRepository#transaction()} creates instances.
     *
     * @param repository the repository that will execute the transaction
     */
    TransactionBuilder(AbstractSqlRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Sets the transaction isolation level.
     *
     * @param level the isolation level
     * @return this builder
     */
    public TransactionBuilder isolationLevel(IsolationLevel level) {
        this.isolationLevel = Objects.requireNonNull(level, "level");
        return this;
    }

    /**
     * Shorthand for {@code isolationLevel(IsolationLevel.SERIALIZABLE)}.
     *
     * @return this builder
     */
    public TransactionBuilder serializable() {
        return isolationLevel(IsolationLevel.SERIALIZABLE);
    }

    /**
     * Shorthand for {@code isolationLevel(IsolationLevel.REPEATABLE_READ)}.
     *
     * @return this builder
     */
    public TransactionBuilder repeatableRead() {
        return isolationLevel(IsolationLevel.REPEATABLE_READ);
    }

    /**
     * Marks the transaction as read-only. PostgreSQL enables query optimizations and prevents
     * accidental writes in read-only transactions.
     *
     * @return this builder
     */
    public TransactionBuilder readOnly() {
        this.readOnly = true;
        return this;
    }

    /**
     * Executes the given function within a transaction configured with the options set on this
     * builder. The transaction auto-commits on success and auto-rolls back on failure. Exceptions
     * are translated via the repository's exception mapper with the generic
     * {@code "Transaction failed"} context.
     *
     * @param fn  the function to execute with a transactional {@link SqlConnection}
     * @param <T> the result type
     * @return a future that completes with the function's result on success, or fails with the
     *         exception returned by the repository's {@link DbExceptionMapper} on failure (a
     *         {@link dev.vertique.db.exception.DataAccessException} subtype for the default mapper,
     *         but a custom mapper may return any {@link Throwable})
     */
    public <T> Future<T> execute(Function<SqlConnection, Future<T>> fn) {
        return repository.executeTransaction(new TransactionOptions(isolationLevel, readOnly), fn);
    }

    /**
     * Variant of {@link #execute(Function)} that uses {@code operationName} as the
     * exception-translation context on failure. Use this when the generic {@code "Transaction
     * failed"} message is not specific enough to diagnose failures (e.g., the repository runs
     * multiple distinct operations through the same code path).
     *
     * @param operationName a short identifier for the operation, included in any translated
     *                      exception messages (e.g., {@code "workflow_instances archiveBefore"})
     * @param fn            the function to execute with a transactional {@link SqlConnection}
     * @param <T>           the result type
     * @return a future that completes with the function's result on success, or fails with the
     *         exception returned by the repository's {@link DbExceptionMapper} on failure (a
     *         {@link dev.vertique.db.exception.DataAccessException} subtype for the default mapper,
     *         but a custom mapper may return any {@link Throwable})
     */
    public <T> Future<T> execute(String operationName, Function<SqlConnection, Future<T>> fn) {
        return repository.executeTransaction(new TransactionOptions(isolationLevel, readOnly), operationName, fn);
    }
}
