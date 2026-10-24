// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.db.IsolationLevel;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.function.Function;

/**
 * Runs a unit of workflow work inside a database transaction, decoupling orchestration from the
 * concrete connection-pool and transaction-handle types.
 *
 * <p>The runner owns the transaction boundary: it opens a transaction, applies the requested
 * isolation level (if any), invokes the supplied {@code work} with the transaction handle, and
 * commits on success / rolls back on failure. It also owns the workflow-layer exception mapping —
 * failures from the transaction body are translated to workflow-domain exceptions before the
 * returned {@link Future} fails (see {@code WorkflowExceptionMapper} for the policy).
 *
 * <p>The orchestration code depends only on this interface, parameterised over the
 * transaction-handle type {@code TX}; a SQL backend implements it with a dialect-specific
 * transaction handle (e.g. {@code io.vertx.sqlclient.SqlClient}).
 *
 * <p>For the existing Vert.x SQL engine binding the handle passed to {@code work} must be a
 * {@code io.vertx.sqlclient.SqlConnection} whose {@code transaction()} reports the underlying pending Vert.x
 * transaction that was begun through Vert.x, for example by {@code Pool.withTransaction}. The engine's public
 * transaction-aware entrypoints reject any other client, namely a pool, a connection without a Vert.x
 * transaction, a connection whose transaction has completed, a connection on which a textual {@code BEGIN} was
 * issued, or a wrapper that does not report the underlying transaction, by failing the returned {@link Future}
 * with an {@link IllegalArgumentException} before any SQL is issued. The deadline-guarded connection of the
 * database module meets the criterion, because it delegates {@code transaction()}. A runner must therefore keep
 * the transaction pending until the {@link Future} returned by {@code work} completes; callers of the engine
 * must await the operation's {@link Future} before committing or rolling back.
 *
 * <p>With a deadline the transaction may end by a deadline rollback while repository futures started by
 * {@code work} are still pending. That is safe only because the guarded connection refuses every statement
 * executed after the deadline, so a continuation that resumes late writes nothing, neither in the rolled-back
 * transaction nor in the next holder of the pooled connection.
 *
 * @param <TX> the transaction-handle type passed to the work function
 */
public interface WorkflowTransactionRunner<TX> {

    /**
     * Executes {@code work} within a transaction.
     *
     * <p>When {@code level} is {@code null} no isolation level is set on the transaction (it runs at
     * the connection's default), so no {@code SET TRANSACTION ISOLATION LEVEL} statement is issued.
     * When {@code level} is non-null, that isolation level is applied before {@code work} runs.
     *
     * @param level the isolation level to apply, or {@code null} to use the connection default
     * @param work  the function to execute with the transaction handle; its returned {@link Future}
     *              determines commit (success) or rollback (failure)
     * @param <R>   the result type produced by the work function
     * @return a future that completes with the work's result on success, or fails with the
     *         workflow-mapped exception on failure
     */
    <R> Future<R> inTransaction(@Nullable IsolationLevel level, Function<TX, Future<R>> work);

    /**
     * Executes {@code work} within a transaction that must have completed by {@code deadline}.
     *
     * <p>INTERNAL: framework wiring for the executor's segment transaction. If the work's future has not completed
     * when the deadline passes, the returned future fails with the database module's
     * {@code TransactionDeadlineExceededException}, returned unchanged, and the transaction rolls back. Work that
     * completed in time commits, even when the commit ends after the deadline.
     *
     * <p>The default fails the returned future with {@link UnsupportedOperationException} without running the work,
     * so a runner that does not implement the deadline never silently drops it.
     *
     * @param level    the isolation level to apply, or {@code null} to use the connection default
     * @param deadline the positive time budget, measured from this call
     * @param work     the function to execute with the transaction handle
     * @param <R>      the result type produced by the work function
     * @return a future that completes with the work's result on success, or fails
     */
    default <R> Future<R> inTransaction(
            @Nullable IsolationLevel level, Duration deadline, Function<TX, Future<R>> work) {
        return Future.failedFuture(
                new UnsupportedOperationException("this transaction runner does not support a transaction deadline"));
    }
}
