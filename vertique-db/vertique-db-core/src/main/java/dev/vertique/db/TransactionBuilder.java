// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import java.time.Duration;
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
 * translated via the repository's {@link DbExceptionMapper}, except
 * {@link dev.vertique.db.exception.TransactionDeadlineExceededException}, which is returned
 * unchanged.
 */
public class TransactionBuilder {

    private final AbstractSqlRepository repository;
    private IsolationLevel isolationLevel;
    private boolean readOnly;
    private Duration deadline;

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
     * Bounds this transaction. The deadline is measured on a monotonic clock from the
     * {@code execute} call, including any wait for a pooled connection. If the work's future has not
     * completed by then, the transaction fails with
     * {@link dev.vertique.db.exception.TransactionDeadlineExceededException} and is rolled back; a
     * budget already spent before the work starts fails the transaction without running the work.
     * Work that completed before the deadline commits, even when the commit ends after it.
     *
     * <p>The work receives a connection that refuses every statement executed after the deadline
     * with {@link dev.vertique.db.exception.TransactionDeadlineExceededException}, so a
     * continuation that resumes late writes nothing, neither in this transaction nor in the pool's
     * next holder. That connection always refuses {@code prepare()}, {@code begin()} and
     * {@code close()} with {@link dev.vertique.db.exception.InvalidDataAccessUsageException}; a
     * consequence is that {@code Query.stream(...)}, which prepares its statement, is unavailable in a
     * deadline-bounded transaction.
     *
     * <p>A statement already running in the database at the deadline finishes or fails first; the
     * rollback queues behind it. No database timeout is set and no cancel request is sent.
     *
     * @param duration the time budget; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code duration} is zero or negative
     */
    public TransactionBuilder deadline(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive: " + duration);
        }
        this.deadline = duration;
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
        return repository.executeTransaction(
                new TransactionOptions(isolationLevel, readOnly), "Transaction failed", deadline, fn);
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
        return repository.executeTransaction(
                new TransactionOptions(isolationLevel, readOnly), operationName, deadline, fn);
    }
}
