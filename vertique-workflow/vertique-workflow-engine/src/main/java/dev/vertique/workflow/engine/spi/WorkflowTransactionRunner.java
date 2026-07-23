// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.db.IsolationLevel;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
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
}
