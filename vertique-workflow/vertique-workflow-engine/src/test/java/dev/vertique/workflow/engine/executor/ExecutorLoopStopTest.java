// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Marks;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.QueueCounts;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that stopping an executor instance waits for the loop's own database work outside segments (the peek of a
 * pass and the mark writes that follow a segment) for at most one poll interval, so that work does not outlive the
 * instance and race the close of the pool.
 */
class ExecutorLoopStopTest {

    /** A poll interval far longer than any test, so that only the bound under test can end a wait. */
    private static final long LONG_POLL_MS = 60_000;

    private static final long SHORTEST_POLL_MS = 100;

    private final CommandHead head = new CommandHead(
            1L, new WorkflowInstanceId(UUID.randomUUID()), "START", "default", "def", 1, "hash", false, null, null);
    private final AtomicInteger markWrites = new AtomicInteger();

    private Vertx vertx;
    private ContextInternal context;
    private WorkflowCommandRepository<SqlClient> commands;
    private WorkflowSegments segments;
    private ExecutorLoop loop;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        context = (ContextInternal) vertx.getOrCreateContext();
        commands = mock(WorkflowCommandRepository.class);
        segments = mock(WorkflowSegments.class);
        when(commands.findEarliestAvailabilityDelayMs()).thenReturn(Future.succeededFuture(Optional.empty()));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("stopping waits for the peek of a pass in flight and completes once it ends")
    void stopWaitsForThePeekInFlight() throws Exception {
        // Given a loop whose pass is peeking and whose peek has not ended
        Promise<List<CommandHead>> peek = Promise.promise();
        when(commands.peek(anyInt())).thenReturn(peek.future());
        loop = loop(LONG_POLL_MS);
        context.runOnContext(v -> {
            loop.start();
            loop.wake(0);
        });
        verify(commands, timeout(5_000)).peek(anyInt());

        // When the loop is stopped
        CompletableFuture<Void> stopped = stop();

        // Then the stop waits while the peek is in flight and completes once it ends
        Thread.sleep(3 * SHORTEST_POLL_MS);
        assertThat(stopped).isNotDone();
        peek.complete(List.of());
        stopped.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("stopping waits at most one poll interval for a peek that never ends")
    void stopWaitsAtMostOnePollIntervalForAHungPeek() throws Exception {
        // Given a loop whose first pass peeks and never gets an answer
        when(commands.peek(anyInt()))
                .thenReturn(Promise.<List<CommandHead>>promise().future());
        loop = loop(SHORTEST_POLL_MS);
        context.runOnContext(v -> loop.start());
        verify(commands, timeout(5_000)).peek(anyInt());

        // When the loop is stopped
        long started = System.nanoTime();
        stop().get(5, TimeUnit.SECONDS);

        // Then the stop completed once one poll interval had passed
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(SHORTEST_POLL_MS);
    }

    @Test
    @DisplayName("stopping waits for the mark write that follows a segment, although the write does not hold the slot")
    void stopWaitsForTheMarkWriteOfAnEndedSegment() throws Exception {
        // Given a loop that claimed one command, whose segment has ended and whose mark write has not
        Promise<Void> markWrite = Promise.promise();
        Marks marks = new Marks(null, () -> {
            markWrites.incrementAndGet();
            return markWrite.future();
        });
        when(commands.peek(anyInt()))
                .thenReturn(Future.succeededFuture(List.of(head)))
                .thenReturn(Future.succeededFuture(List.of()));
        when(commands.claim(any(), anyString(), anyInt(), anyInt(), any(InstanceFailureListener.class)))
                .thenReturn(Future.succeededFuture(Optional.of(new ClaimedCommand(
                        1L, head.workflowId(), "START", UUID.randomUUID(), 1, ClaimedCommand.Result.CLAIMED, false))));
        when(segments.warm(any())).thenReturn(Future.succeededFuture(Warm.of(Warm.Status.RESIDENT, marks)));
        loop = loop(LONG_POLL_MS);
        context.runOnContext(v -> {
            loop.start();
            loop.wake(0);
        });
        verify(commands, timeout(5_000).times(2)).peek(anyInt());
        assertThat(markWrites).hasValue(1);

        // When the loop is stopped
        CompletableFuture<Void> stopped = stop();

        // Then the stop waits while the mark write is in flight and completes once it ends
        Thread.sleep(3 * SHORTEST_POLL_MS);
        assertThat(stopped).isNotDone();
        markWrite.complete();
        stopped.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("stopping while a segment runs also waits for the mark write that starts when the segment ends")
    void stopDuringASegmentWaitsForItsMarkWrite() throws Exception {
        // Given a loop whose one segment is running and whose mark write will not end at once
        Promise<Void> segment = Promise.promise();
        Promise<Void> markWrite = Promise.promise();
        claimOneHeadWith(new Marks(null, () -> {
            markWrites.incrementAndGet();
            return markWrite.future();
        }));
        SegmentRunner runner = mock(SegmentRunner.class);
        when(runner.run(any(), any(), anyLong(), any())).thenReturn(segment.future());
        ExecutorEvents events = ExecutorEvents.to(Set.of());
        loop = loop(LONG_POLL_MS, runner, events, mock(StuckReporter.class));
        context.runOnContext(v -> {
            loop.start();
            loop.wake(0);
        });
        verify(runner, timeout(5_000)).run(any(), any(), anyLong(), any());

        // When the loop is stopped and the segment then ends
        CompletableFuture<Void> stopped = stop();
        Thread.sleep(SHORTEST_POLL_MS);
        assertThat(stopped).isNotDone();
        context.runOnContext(v -> segment.complete());

        // Then the stop waits for the mark write the segment's end started, and completes once it ends
        verifyMarkWriteStarted();
        Thread.sleep(3 * SHORTEST_POLL_MS);
        assertThat(stopped).isNotDone();
        markWrite.complete();
        stopped.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("no queue snapshot starts after the stop has completed")
    void noQueueSnapshotStartsAfterTheStop() throws Exception {
        // Given a loop with an observer, whose pass claims a head and waits for the claim's answer
        AtomicInteger snapshots = new AtomicInteger();
        when(commands.countQueue(anyLong())).thenAnswer(invocation -> {
            snapshots.incrementAndGet();
            return Future.succeededFuture(new QueueCounts(0, 0, 0, 0));
        });
        Promise<Optional<ClaimedCommand>> claim = Promise.promise();
        when(commands.peek(anyInt()))
                .thenReturn(Future.succeededFuture(List.of(head)))
                .thenReturn(Future.succeededFuture(List.of()));
        when(commands.claim(any(), anyString(), anyInt(), anyInt(), any(InstanceFailureListener.class)))
                .thenReturn(claim.future());
        when(segments.warm(any())).thenReturn(Future.succeededFuture(Warm.of(Warm.Status.RESIDENT, Marks.NONE)));
        WorkflowExecutorObserver observer = event -> {};
        ExecutorEvents events = ExecutorEvents.to(Set.of(observer));
        SegmentRunner runner = mock(SegmentRunner.class);
        loop = loop(LONG_POLL_MS, runner, events, new StuckReporter(commands, 60, events));
        context.runOnContext(v -> {
            loop.start();
            loop.wake(0);
        });
        verify(commands, timeout(5_000))
                .claim(any(), anyString(), anyInt(), anyInt(), any(InstanceFailureListener.class));

        // When the loop is stopped and the claim then comes back empty, which completes the stop
        CompletableFuture<Void> stopped = stop();
        context.runOnContext(v -> claim.complete(Optional.empty()));
        stopped.get(5, TimeUnit.SECONDS);

        // Then the pass that ended with it starts no queue snapshot
        Thread.sleep(3 * SHORTEST_POLL_MS);
        assertThat(snapshots).hasValue(0);
        verify(commands, never()).countQueue(anyLong());
    }

    @Test
    @DisplayName("stopping waits for the earliest-delay read in flight and completes once it ends")
    void stopWaitsForTheEarliestDelayRead() throws Exception {
        // Given a loop woken by a hint whose earliest-delay read has not ended
        Promise<Optional<Long>> earliest = Promise.promise();
        when(commands.findEarliestAvailabilityDelayMs()).thenReturn(earliest.future());
        when(commands.peek(anyInt())).thenReturn(Future.succeededFuture(List.of()));
        loop = loop(LONG_POLL_MS);
        context.runOnContext(v -> {
            loop.start();
            loop.wake(0);
        });
        verify(commands, timeout(5_000)).findEarliestAvailabilityDelayMs();

        // When the loop is stopped
        CompletableFuture<Void> stopped = stop();

        // Then the stop waits while the read is in flight and completes once it ends
        Thread.sleep(3 * SHORTEST_POLL_MS);
        assertThat(stopped).isNotDone();
        earliest.complete(Optional.empty());
        stopped.get(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void claimOneHeadWith(Marks marks) {
        when(commands.peek(anyInt()))
                .thenReturn(Future.succeededFuture(List.of(head)))
                .thenReturn(Future.succeededFuture(List.of()));
        when(commands.claim(any(), anyString(), anyInt(), anyInt(), any(InstanceFailureListener.class)))
                .thenReturn(Future.succeededFuture(Optional.of(new ClaimedCommand(
                        1L, head.workflowId(), "START", UUID.randomUUID(), 1, ClaimedCommand.Result.CLAIMED, false))));
        when(segments.warm(any())).thenReturn(Future.succeededFuture(Warm.of(Warm.Status.RESIDENT, marks)));
    }

    private void verifyMarkWriteStarted() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (markWrites.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(markWrites).hasValue(1);
    }

    private ExecutorLoop loop(long pollIntervalMs) {
        SegmentRunner runner = mock(SegmentRunner.class);
        when(runner.run(any(), any(), anyLong(), any())).thenReturn(Future.succeededFuture());
        return loop(pollIntervalMs, runner, ExecutorEvents.to(Set.of()), mock(StuckReporter.class));
    }

    private ExecutorLoop loop(
            long pollIntervalMs, SegmentRunner runner, ExecutorEvents events, StuckReporter reporter) {
        return new ExecutorLoop(
                vertx,
                context,
                new WorkflowExecutorConfig(1, 1, pollIntervalMs, 60),
                "test-instance",
                new CommandPeek(commands, segments, new Deferral(commands, events)),
                commands,
                runner,
                segments,
                events,
                reporter);
    }

    /** Stops the loop on its own context, as the executor verticle does. */
    private CompletableFuture<Void> stop() {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        context.runOnContext(v -> loop.stop().onComplete(result -> stopped.complete(null)));
        return stopped;
    }
}
