// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.db.IsolationLevel;
import dev.vertique.db.exception.DataAccessException;
import dev.vertique.workflow.engine.WorkflowExceptionMapper;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.registry.WorkflowRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the branch-recovery sweep routes its transactional work through the
 * {@link WorkflowTransactionRunner}, so a DB-layer {@link DataAccessException} raised inside the
 * sweep surfaces to the caller as a {@link WorkflowException} (the two-stage mapper's stage-2
 * output) rather than leaking the raw data-access type.
 *
 * <p>This is a unit-level proof of the routing decision (PRD-WF-006 §13 recovery-sweep
 * asymmetry resolution). The runner used here applies the production
 * {@link WorkflowExceptionMapper} in the same outer-{@code recover} position as
 * {@link PgWorkflowTransactionRunner}, but skips the live {@code TransactionBuilder.execute(...)}
 * so no database is required. The end-to-end mapping inside a real PostgreSQL transaction is
 * covered by {@code WorkflowTransactionRunnerLayeredMappingIT}; the success path is covered by
 * {@code PgWorkflowBranchRecoveryServiceIT}.
 */
class PgWorkflowBranchRecoverySweepMappingTest {

    /**
     * Test runner mirroring {@link PgWorkflowTransactionRunner}'s outer mapping stage: it invokes
     * {@code work} with a {@code null} handle (the failing repo stub never touches it) and applies
     * the production {@link WorkflowExceptionMapper} on failure. It deliberately omits the live
     * {@code TransactionBuilder.execute(...)} so the test needs no database.
     */
    private static final class MappingOnlyRunner implements WorkflowTransactionRunner<SqlClient> {

        private final WorkflowExceptionMapper mapper = new WorkflowExceptionMapper();

        @Override
        public <R> Future<R> inTransaction(@Nullable IsolationLevel level, Function<SqlClient, Future<R>> work) {
            return work.apply(null).recover(t -> Future.failedFuture(mapper.translate(t, "workflow transaction")));
        }
    }

    @Test
    @DisplayName("sweep surfaces WorkflowException (not DataAccessException) when a repo call fails inside the runner")
    void sweepSurfacesWorkflowExceptionWhenRepoFails() {
        @SuppressWarnings("unchecked")
        BranchTokenRepository<SqlClient> branchTokens = mock(BranchTokenRepository.class);
        @SuppressWarnings("unchecked")
        WorkflowInstanceRepository<SqlClient> instances = mock(WorkflowInstanceRepository.class);
        WorkflowRegistry registry = mock(WorkflowRegistry.class);

        DataAccessException dbFailure = new DataAccessException("persistence boom", null, "08006");
        when(branchTokens.findRecoverable(any(Instant.class), anyInt(), isNull()))
                .thenReturn(Future.failedFuture(dbFailure));

        // The recovery bridge is never reached: the first transactional read fails, so the sweep
        // short-circuits before any per-branch processing — a null bridge is therefore fine here.
        PgWorkflowBranchRecoveryService recovery = new PgWorkflowBranchRecoveryService(
                new MappingOnlyRunner(), branchTokens, instances, null, registry, Clock.systemUTC());

        Future<Integer> result = recovery.sweepOnce(Duration.ofMinutes(5), 10);

        assertTrue(result.failed(), "the sweep must fail when its first transactional read fails");
        assertInstanceOf(
                WorkflowPersistenceException.class,
                result.cause(),
                "DB failure in the recovery sweep must surface as a WorkflowException, not a DataAccessException");
        assertFalse(
                result.cause() instanceof DataAccessException, "raw DataAccessException must not leak to the caller");
    }
}
