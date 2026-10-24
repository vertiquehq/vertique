// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** Verifies that the probe bounds how long it waits and how fast it re-reads the database. */
class DefaultWorkflowExecutionProbeTest {

    /** A repository that stays busy for a number of reads and then reports idle. */
    @SuppressWarnings("unchecked")
    private static WorkflowCommandRepository<SqlClient> busyFor(int busyReads) {
        AtomicInteger reads = new AtomicInteger();
        WorkflowCommandRepository<SqlClient> commands = Mockito.mock(WorkflowCommandRepository.class);
        Mockito.when(commands.countRunnable(ArgumentMatchers.any()))
                .thenAnswer(invocation -> Future.succeededFuture(reads.incrementAndGet() > busyReads ? 0 : 1));
        Mockito.when(commands.findDeferred(ArgumentMatchers.any())).thenReturn(Future.succeededFuture(List.of()));
        Mockito.when(commands.findParked(ArgumentMatchers.any())).thenReturn(Future.succeededFuture(List.of()));
        Mockito.when(commands.findNotYetAvailable(ArgumentMatchers.any()))
                .thenReturn(Future.succeededFuture(List.of()));
        return commands;
    }

    @Test
    @DisplayName("the pause between two reads doubles from 25 ms up to 250 ms")
    void pauseBetweenReadsDoublesUpToItsCeiling() {
        // Given a repository that is busy for seven reads and a probe that records its pauses
        List<Long> pauses = new ArrayList<>();
        DefaultWorkflowExecutionProbe probe = new DefaultWorkflowExecutionProbe(
                busyFor(7),
                delayMs -> {
                    pauses.add(delayMs);
                    return Future.succeededFuture();
                },
                Duration.ofMinutes(10));

        // When the probe waits for the instance to be idle
        probe.awaitIdle(new WorkflowInstanceId(UUID.randomUUID()), Duration.ofMinutes(1))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        // Then the pauses grow from the shortest to the longest and stay there
        assertThat(pauses).containsExactly(25L, 50L, 100L, 200L, 250L, 250L, 250L);
    }

    @Test
    @DisplayName("a timeout above the cap is shortened to the cap")
    void timeoutAboveTheCapIsShortenedToTheCap() {
        // Given a repository that is never idle and a probe whose cap is 150 ms, paced by real timers
        Vertx vertx = Vertx.vertx();
        try {
            DefaultWorkflowExecutionProbe probe = new DefaultWorkflowExecutionProbe(
                    busyFor(Integer.MAX_VALUE),
                    delayMs -> {
                        Promise<Void> promise = Promise.promise();
                        vertx.setTimer(delayMs, id -> promise.complete());
                        return promise.future();
                    },
                    Duration.ofMillis(150));

            // When the probe is asked to wait for an hour
            long started = System.nanoTime();
            Throwable failure = probe.awaitIdle(new WorkflowInstanceId(UUID.randomUUID()), Duration.ofHours(1))
                    .transform(result -> Future.succeededFuture(result.cause()))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            // Then it gives up at the cap, naming the capped timeout
            assertThat(failure).isInstanceOf(TimeoutException.class).hasMessageContaining("PT0.15S");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        } finally {
            vertx.close();
        }
    }
}
