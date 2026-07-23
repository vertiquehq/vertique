// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.db.IsolationLevel;
import dev.vertique.db.TransactionBuilder;
import dev.vertique.workflow.engine.WorkflowExceptionMapper;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.function.Function;

/**
 * PostgreSQL implementation of {@link WorkflowTransactionRunner} over {@link SqlClient}.
 *
 * <p>Runs the transaction through a dedicated {@link WorkflowTxRunnerRepository}'s
 * {@code transaction()} builder (so the stage-1 {@link WorkflowPgExceptionMapper} applies inside
 * {@code execute}), then applies the stage-2 {@link WorkflowExceptionMapper} in the outer
 * {@code recover}. The two stages implement the layered DB-to-workflow exception mapping:
 *
 * <ol>
 *   <li><b>Stage 1</b> (inside {@code execute}, via the runner repo's mapper): DB driver exceptions
 *       → {@code DataAccessException} subtypes; {@code WorkflowException} passthrough; unknown
 *       throwables passthrough (the mapper's overridden {@code fallback}).</li>
 *   <li><b>Stage 2</b> (the outer {@code recover}): {@code DataAccessException} → a workflow-semantic
 *       exception rooted in the core hierarchy (e.g. {@code WorkflowConflictException},
 *       {@code WorkflowPersistenceException}); already-semantic workflow exceptions and unknown
 *       throwables pass through.</li>
 * </ol>
 *
 * <p>The isolation level is applied only when non-null, preserving the setup-statement budget: a
 * write transaction ({@code level == null}) issues zero {@code SET} statements; a
 * {@link IsolationLevel#REPEATABLE_READ} query issues exactly one. The runner never marks a
 * transaction {@code READ ONLY}.
 */
@Singleton
class PgWorkflowTransactionRunner implements WorkflowTransactionRunner<SqlClient> {

    private final WorkflowTxRunnerRepository repository;
    private final WorkflowExceptionMapper workflowExceptionMapper;

    /**
     * Creates the runner.
     *
     * @param repository              the dedicated repository providing the stage-1 transaction
     *                                builder
     * @param workflowExceptionMapper the stage-2 workflow-boundary exception mapper
     */
    @Inject
    PgWorkflowTransactionRunner(
            WorkflowTxRunnerRepository repository, WorkflowExceptionMapper workflowExceptionMapper) {
        this.repository = repository;
        this.workflowExceptionMapper = workflowExceptionMapper;
    }

    /** {@inheritDoc} */
    @Override
    public <R> Future<R> inTransaction(@Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
        TransactionBuilder tx = repository.transaction();
        if (level != null) {
            tx = tx.isolationLevel(level);
        }
        return tx.<R>execute(conn -> work.apply(conn))
                .recover(t -> Future.failedFuture(workflowExceptionMapper.translate(t, "workflow transaction")));
    }
}
