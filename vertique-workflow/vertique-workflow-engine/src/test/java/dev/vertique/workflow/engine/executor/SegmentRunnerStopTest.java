// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DispositionResult;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that once the executor is stopping, a failure, reschedule or release write that fails is not tried again:
 * the database pool is closing, so the write cannot succeed, and the claim lapses with its lease. A write that finds
 * the execution lock busy is still tried again.
 */
class SegmentRunnerStopTest {

    /** The lease is far longer than any test, so that only the stop can end a retry. */
    private static final int LEASE_SECONDS = 60;

    private final CommandHead head = new CommandHead(
            1L, new WorkflowInstanceId(UUID.randomUUID()), "START", "default", "def", 1, "hash", false, null, null);
    private final ClaimedCommand claim = new ClaimedCommand(
            1L, head.workflowId(), "START", UUID.randomUUID(), 1, ClaimedCommand.Result.CLAIMED, false);

    private Vertx vertx;
    private WorkflowCommandRepository<SqlClient> commands;
    private WorkflowTransactionRunner<SqlClient> runner;
    private SegmentRunner segmentRunner;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        commands = mock(WorkflowCommandRepository.class);
        runner = mock(WorkflowTransactionRunner.class);
        segmentRunner = new SegmentRunner(
                vertx,
                new WorkflowExecutorConfig(1, 1, 100, LEASE_SECONDS),
                commands,
                runner,
                mock(WorkflowSegments.class),
                ExecutorEvents.to(Set.of()));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a failure write that fails is tried again until the executor stops, then abandoned")
    void failureWriteIsAbandonedWhenTheExecutorStops() throws Exception {
        // Given a segment that failed for good and a failure write that always fails, as on a closed pool
        segmentFails(new IllegalStateException("the segment failed"));
        when(commands.failCommand(any(), anyString(), anyString(), any(InstanceFailureListener.class)))
                .thenReturn(Future.failedFuture(new IllegalStateException("Connection is not active")));
        Future<Void> run = segmentRunner.run(head, claim, System.nanoTime(), null);

        // Then the write is tried again and the segment is still in flight
        verify(commands, timeout(5_000).atLeast(3)).failCommand(any(), anyString(), anyString(), any());
        assertThat(run.isComplete()).isFalse();

        // When the executor stops
        segmentRunner.stop();

        // Then the segment ends without the write having succeeded, and no further write follows
        run.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int writes = Mockito.mockingDetails(commands).getInvocations().size();
        Thread.sleep(500);
        assertThat(Mockito.mockingDetails(commands).getInvocations()).hasSize(writes);
    }

    @Test
    @DisplayName("a reschedule write that fails is abandoned when the executor stops")
    void rescheduleWriteIsAbandonedWhenTheExecutorStops() throws Exception {
        // Given a segment that failed transiently and a reschedule write that always fails
        segmentFails(new WorkflowPersistenceException("transient", new IllegalStateException(), true));
        when(commands.retryTransient(any(), any(), anyString()))
                .thenReturn(Future.failedFuture(new IllegalStateException("Connection is not active")));
        Future<Void> run = segmentRunner.run(head, claim, System.nanoTime(), null);
        verify(commands, timeout(5_000).atLeast(2)).retryTransient(any(), any(), anyString());

        // When the executor stops
        segmentRunner.stop();

        // Then the segment ends without the write having succeeded
        run.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int writes = Mockito.mockingDetails(commands).getInvocations().size();
        Thread.sleep(500);
        assertThat(Mockito.mockingDetails(commands).getInvocations()).hasSize(writes);
    }

    @Test
    @DisplayName("a release write that fails is abandoned when the executor stops")
    void releaseWriteIsAbandonedWhenTheExecutorStops() throws Exception {
        // Given a claim whose busy-lock give-up has passed, so that its segment is not run but its claim is released,
        // and a release write that always fails
        when(commands.release(any())).thenReturn(Future.failedFuture(new IllegalStateException("not active")));
        WorkflowExecutorConfig config = new WorkflowExecutorConfig(1, 1, 100, LEASE_SECONDS);
        long claimedAt = System.nanoTime()
                - TimeUnit.SECONDS.toNanos(LEASE_SECONDS)
                + TimeUnit.MILLISECONDS.toNanos(config.marginMs())
                - TimeUnit.SECONDS.toNanos(1);
        Future<Void> run = segmentRunner.run(head, claim, claimedAt, null);
        verify(commands, timeout(5_000).atLeast(2)).release(any());
        assertThat(run.isComplete()).isFalse();

        // When the executor stops
        segmentRunner.stop();

        // Then the segment ends without the release having succeeded
        run.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int writes = Mockito.mockingDetails(commands).getInvocations().size();
        Thread.sleep(500);
        assertThat(Mockito.mockingDetails(commands).getInvocations()).hasSize(writes);
    }

    @Test
    @DisplayName("a failure write that finds the execution lock busy is still tried again after the executor stops")
    void busyFailureWriteIsStillTriedAfterTheExecutorStops() throws Exception {
        // Given a segment that failed for good and a failure write that always finds the lock busy
        segmentFails(new IllegalStateException("the segment failed"));
        when(commands.failCommand(any(), anyString(), anyString(), any(InstanceFailureListener.class)))
                .thenReturn(Future.succeededFuture(DispositionResult.BUSY));
        Future<Void> run = segmentRunner.run(head, claim, System.nanoTime(), null);
        verify(commands, timeout(5_000).atLeast(2)).failCommand(any(), anyString(), anyString(), any());

        // When the executor stops
        segmentRunner.stop();
        int writes = failureWrites();

        // Then the write is still tried again, and the segment is still in flight
        verify(commands, timeout(5_000).atLeast(writes + 2)).failCommand(any(), anyString(), anyString(), any());
        assertThat(run.isComplete()).isFalse();
    }

    private int failureWrites() {
        return (int) Mockito.mockingDetails(commands).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("failCommand"))
                .count();
    }

    private void segmentFails(Throwable failure) {
        when(runner.<Object>inTransaction(isNull(), any(Duration.class), any()))
                .thenReturn(Future.failedFuture(failure));
    }
}
