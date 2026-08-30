// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.db.postgresql.PgSqlRepository;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Dedicated {@link PgSqlRepository} used only by {@link PgWorkflowTransactionRunner} to obtain a
 * {@code TransactionBuilder} ({@link #transaction()}) bound to the workflow stage-1 exception mapper.
 *
 * <p>{@code TransactionBuilder} cannot be instantiated directly — it is created by
 * {@link dev.vertique.db.AbstractSqlRepository#transaction()} bound to <em>that repository's</em>
 * exception mapper. This repository carries the {@link PgWorkflowExceptionMapper} (stage 1) so that
 * transactions executed through the runner apply the DB-boundary mapping inside
 * {@code TransactionBuilder.execute(...)}. It performs no queries of its own; only its
 * {@code transaction()} factory is used.
 */
@Singleton
class WorkflowTxRunnerRepository extends PgSqlRepository {

    /**
     * Creates the runner repository.
     *
     * @param pool          the PostgreSQL connection pool
     * @param exceptionMapper the workflow stage-1 (DB-boundary) exception mapper
     */
    @Inject
    WorkflowTxRunnerRepository(Pool pool, PgWorkflowExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }
}
