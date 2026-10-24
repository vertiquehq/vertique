// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.WorkflowRecoveryBridge;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Verifies the branch-recovery single-fresh-read restructure (Contract Appendix C3): each
 * recoverable row locks the branch-token row first ({@code findByIdForUpdate}), then reads the
 * parent instance exactly once (a plain, non-locking {@code findById}) — no separate pre-load
 * transaction runs ahead of the row's own CAS transaction, and the instance is never read twice.
 * This preserves the timers→tasks→branches→instances lock-order invariant: the instance read
 * stays non-locking, and no {@code FOR UPDATE} read is introduced ahead of the branch lock.
 * The instance's execution lock comes before all of them: the sweep takes it for the branch's
 * instance before it locks the branch row, on the resume path and on the demote path.
 *
 * <p>Uses {@link org.mockito.Mockito#mock} collaborators (mirroring
 * {@link PgWorkflowBranchRecoverySweepMappingTest}'s pattern) and an {@link InOrder} verification
 * across the {@link WorkflowInstanceRepository} and {@link BranchTokenRepository} mocks.
 */
class PgWorkflowBranchRecoveryLockOrderTest {

    /** Runs {@code work} inline against a {@code null} handle — no database required. */
    private static final class InlineRunner implements WorkflowTransactionRunner<SqlClient> {
        @Override
        public <R> Future<R> inTransaction(
                dev.vertique.db.IsolationLevel level, java.util.function.Function<SqlClient, Future<R>> work) {
            return work.apply(null);
        }
    }

    @Test
    @DisplayName("resumeOne locks the branch row (findByIdForUpdate) before its single, "
            + "non-locking instance read (findById), and reads the instance exactly once")
    void singleInstanceReadPerRow_afterTokenLock_noLockOrderRegression() {
        @SuppressWarnings("unchecked")
        BranchTokenRepository<SqlClient> branchTokens = mock(BranchTokenRepository.class);
        @SuppressWarnings("unchecked")
        WorkflowInstanceRepository<SqlClient> instances = mock(WorkflowInstanceRepository.class);
        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> commands = mock(WorkflowCommandRepository.class);
        WorkflowRegistry registry = mock(WorkflowRegistry.class);
        WorkflowRecoveryBridge recoveryBridge = mock(WorkflowRecoveryBridge.class, RETURNS_DEEP_STUBS);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        when(commands.lockInstance(eq(workflowId), isNull())).thenReturn(Future.succeededFuture());
        BranchToken due = dueRetryScheduledToken(workflowId, clock.instant());
        WorkflowInstance inst = minimalInstance(workflowId);

        when(branchTokens.findRecoverable(any(Instant.class), anyInt(), isNull()))
                .thenReturn(Future.succeededFuture(List.of(due)));
        // sweepOnce also queries the stale-RUNNING side; no rows to keep this test focused on the
        // due-branch ordering claim.
        when(branchTokens.findStaleRunning(any(Instant.class), anyInt(), isNull()))
                .thenReturn(Future.succeededFuture(List.of()));
        when(instances.findById(eq(workflowId), isNull())).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(branchTokens.findByIdForUpdate(eq(due.id()), isNull()))
                .thenReturn(Future.succeededFuture(Optional.of(due)));
        // Fail the CAS so resumeOne short-circuits right after the reads under test — no need to
        // stub the drive itself.
        when(branchTokens.updateOptimistic(any(BranchToken.class), org.mockito.ArgumentMatchers.anyLong(), isNull()))
                .thenReturn(Future.succeededFuture(0));

        PgWorkflowBranchRecoveryService recovery = new PgWorkflowBranchRecoveryService(
                new InlineRunner(), commands, branchTokens, instances, recoveryBridge, registry, clock);

        Future<Integer> result = recovery.sweepOnce(Instant.now(clock), 10);

        assertTrue(result.succeeded(), "sweep must succeed: " + result.cause());

        InOrder order = inOrder(commands, branchTokens, instances);
        order.verify(commands).lockInstance(eq(workflowId), isNull());
        order.verify(branchTokens).findByIdForUpdate(eq(due.id()), isNull());
        order.verify(instances).findById(eq(workflowId), isNull());
        // Single fresh read: the restructure computes the effective durable-context document from
        // this same read — no separate pre-load transaction re-reads the instance beforehand or
        // afterward.
        verify(instances, times(1)).findById(eq(workflowId), isNull());
    }

    @Test
    @DisplayName("demoteOne takes the instance's execution lock before it locks the branch row")
    void demotingAStaleBranchTakesTheExecutionLockBeforeTheBranchRowLock() {
        @SuppressWarnings("unchecked")
        BranchTokenRepository<SqlClient> branchTokens = mock(BranchTokenRepository.class);
        @SuppressWarnings("unchecked")
        WorkflowInstanceRepository<SqlClient> instances = mock(WorkflowInstanceRepository.class);
        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> commands = mock(WorkflowCommandRepository.class);
        WorkflowRegistry registry = mock(WorkflowRegistry.class);
        WorkflowRecoveryBridge recoveryBridge = mock(WorkflowRecoveryBridge.class, RETURNS_DEEP_STUBS);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        when(commands.lockInstance(eq(workflowId), isNull())).thenReturn(Future.succeededFuture());
        BranchToken stale = staleRunningToken(workflowId, clock.instant());

        when(branchTokens.findRecoverable(any(Instant.class), anyInt(), isNull()))
                .thenReturn(Future.succeededFuture(List.of()));
        when(branchTokens.findStaleRunning(any(Instant.class), anyInt(), isNull()))
                .thenReturn(Future.succeededFuture(List.of(stale)));
        when(branchTokens.findByIdForUpdate(eq(stale.id()), isNull()))
                .thenReturn(Future.succeededFuture(Optional.of(stale)));
        // Fail the CAS so demoteOne short-circuits right after the reads under test.
        when(branchTokens.updateOptimistic(any(BranchToken.class), org.mockito.ArgumentMatchers.anyLong(), isNull()))
                .thenReturn(Future.succeededFuture(0));

        PgWorkflowBranchRecoveryService recovery = new PgWorkflowBranchRecoveryService(
                new InlineRunner(), commands, branchTokens, instances, recoveryBridge, registry, clock);

        Future<Integer> result = recovery.sweepOnce(Instant.now(clock), 10);

        assertTrue(result.succeeded(), "sweep must succeed: " + result.cause());

        InOrder order = inOrder(commands, branchTokens);
        order.verify(commands).lockInstance(eq(workflowId), isNull());
        order.verify(branchTokens).findByIdForUpdate(eq(stale.id()), isNull());
    }

    private static BranchToken dueRetryScheduledToken(WorkflowInstanceId workflowId, Instant now) {
        return tokenWithStatus(workflowId, BranchStatus.RETRY_SCHEDULED, now);
    }

    private static BranchToken staleRunningToken(WorkflowInstanceId workflowId, Instant now) {
        return tokenWithStatus(workflowId, BranchStatus.RUNNING, now);
    }

    private static BranchToken tokenWithStatus(WorkflowInstanceId workflowId, BranchStatus status, Instant now) {
        return new BranchToken(
                UUID.randomUUID(),
                workflowId,
                "fork",
                "a",
                "dispatch-a",
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                now.minusSeconds(60),
                null,
                null,
                null,
                0L,
                now,
                now,
                null);
    }

    private static WorkflowInstance minimalInstance(WorkflowInstanceId id) {
        return new WorkflowInstance(
                id,
                "lock-order-def",
                1L,
                "hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "dispatch-a",
                null,
                null,
                null,
                "{}",
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null);
    }
}
