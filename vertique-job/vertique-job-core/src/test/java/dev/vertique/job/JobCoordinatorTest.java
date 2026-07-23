// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobCoordinator}.
 *
 * <p>Verifies: node heartbeat registration on start, dead-node scan triggers orphan recovery,
 * DELAYED jobs with remaining retries are re-enqueued after ABANDONED, cron jobs stay ABANDONED,
 * clean shutdown removes the server heartbeat row, a disabled coordinator starts no timers, and
 * cancellation publishes an event-bus signal and updates state.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@DisplayName("JobCoordinator")
class JobCoordinatorTest {

    @Mock
    private JobRepository repository;

    private JobCoordinatorConfig defaultConfig;

    @BeforeEach
    void setUpConfig() {
        defaultConfig = JobCoordinatorConfig.builder().build();
    }

    // --- Helpers ---

    /**
     * Creates a minimal {@link JobExecution} for testing, with the given type, attempt, and max
     * attempts. All other fields are set to sensible defaults.
     *
     * @param type        the job type
     * @param attempt     the zero-based attempt number
     * @param maxAttempts the maximum number of allowed attempts
     * @return a test execution record
     */
    private static JobExecution execution(JobType type, int attempt, int maxAttempts) {
        return new JobExecution(
                UUID.randomUUID(),
                "test-job-" + UUID.randomUUID(),
                type,
                "test.handler",
                "default",
                JobState.PROCESSING,
                attempt,
                maxAttempts,
                null,
                0,
                "dead-node",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    // --- Start ---

    @Nested
    @DisplayName("start()")
    class Start {

        @Test
        @DisplayName("registers node heartbeat immediately on start")
        void registersNodeHeartbeatOnStart(Vertx vertx, VertxTestContext ctx) {
            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());

            JobCoordinator coordinator = new JobCoordinator(vertx, repository, defaultConfig);

            coordinator
                    .start()
                    .onSuccess(v -> ctx.verify(() -> {
                        verify(repository, atLeastOnce()).serverHeartbeat(anyString());
                        assertNotNull(coordinator.serverId(), "serverId must not be null");
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("disabled coordinator does not call serverHeartbeat or set timers")
        void disabledCoordinatorDoesNotStart(Vertx vertx, VertxTestContext ctx) {
            JobCoordinatorConfig disabled =
                    JobCoordinatorConfig.builder().enabled(false).build();
            JobCoordinator coordinator = new JobCoordinator(vertx, repository, disabled);

            coordinator
                    .start()
                    .onSuccess(v -> ctx.verify(() -> {
                        verify(repository, never()).serverHeartbeat(anyString());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // --- Stop ---

    @Nested
    @DisplayName("stop()")
    class Stop {

        @Test
        @DisplayName("clean stop removes the server heartbeat row")
        void cleanStopRemovesServerHeartbeat(Vertx vertx, VertxTestContext ctx) {
            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());
            when(repository.removeServer(anyString())).thenReturn(Future.succeededFuture());

            JobCoordinator coordinator = new JobCoordinator(vertx, repository, defaultConfig);

            coordinator
                    .start()
                    .compose(v -> coordinator.stop())
                    .onSuccess(v -> ctx.verify(() -> {
                        verify(repository, atLeastOnce()).removeServer(coordinator.serverId());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // --- Dead-node recovery ---

    @Nested
    @DisplayName("scanForDeadNodes()")
    class DeadNodeScan {

        @Test
        @DisplayName("scan finds dead servers and marks orphaned CRON executions ABANDONED")
        void scanFindsDeadServersAndMarksCronAbandoned(Vertx vertx, VertxTestContext ctx) {
            JobExecution orphan1 = execution(JobType.CRON, 0, 3);
            JobExecution orphan2 = execution(JobType.CRON, 0, 3);

            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());
            when(repository.findDeadServers(any(Duration.class)))
                    .thenReturn(Future.succeededFuture(List.of("dead-node-1")));
            when(repository.findByLockedBy("dead-node-1"))
                    .thenReturn(Future.succeededFuture(List.of(orphan1, orphan2)));
            when(repository.completeExecution(any(), eq(JobState.ABANDONED), anyString(), anyString(), any()))
                    .thenAnswer(inv -> Future.succeededFuture(Optional.of(orphan1)));
            when(repository.removeServer(anyString())).thenReturn(Future.succeededFuture());

            // Use a very short scan interval so the timer fires during the test
            JobCoordinatorConfig fastConfig = JobCoordinatorConfig.builder()
                    .scanIntervalMs(50)
                    .nodeHeartbeatIntervalMs(60_000)
                    .build();
            JobCoordinator coordinator = new JobCoordinator(vertx, repository, fastConfig);

            coordinator
                    .start()
                    .onSuccess(v -> {
                        // Wait for at least one scan cycle
                        vertx.setTimer(
                                200,
                                id -> ctx.verify(() -> {
                                    verify(repository, atLeastOnce()).findDeadServers(any(Duration.class));
                                    verify(repository, atLeastOnce()).findByLockedBy("dead-node-1");
                                    verify(repository, atLeastOnce())
                                            .completeExecution(
                                                    eq(orphan1.id()),
                                                    eq(JobState.ABANDONED),
                                                    anyString(),
                                                    anyString(),
                                                    any());
                                    verify(repository, atLeastOnce())
                                            .completeExecution(
                                                    eq(orphan2.id()),
                                                    eq(JobState.ABANDONED),
                                                    anyString(),
                                                    anyString(),
                                                    any());
                                    // abandonAndScheduleRetry must never be called for CRON jobs
                                    verify(repository, never())
                                            .abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
                                    coordinator.stop();
                                    ctx.completeNow();
                                }));
                    })
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("DELAYED job with retries remaining: calls abandonAndScheduleRetry atomically")
        void delayedJobWithRetriesCallsAbandonAndScheduleRetry(Vertx vertx, VertxTestContext ctx) {
            // attempt=0, maxAttempts=3 → attempt+1=1 < 3, so retry should fire
            JobExecution delayedJob = execution(JobType.DELAYED, 0, 3);

            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());
            when(repository.findDeadServers(any(Duration.class)))
                    .thenReturn(Future.succeededFuture(List.of("dead-node")));
            when(repository.findByLockedBy("dead-node")).thenReturn(Future.succeededFuture(List.of(delayedJob)));
            when(repository.abandonAndScheduleRetry(any(), any(), any(), any(), any(Instant.class), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.of(delayedJob)));
            when(repository.removeServer(anyString())).thenReturn(Future.succeededFuture());

            JobCoordinatorConfig fastConfig = JobCoordinatorConfig.builder()
                    .scanIntervalMs(50)
                    .nodeHeartbeatIntervalMs(60_000)
                    .build();
            JobCoordinator coordinator = new JobCoordinator(vertx, repository, fastConfig);

            coordinator
                    .start()
                    .onSuccess(v -> vertx.setTimer(
                            200,
                            id -> ctx.verify(() -> {
                                // abandonAndScheduleRetry is the atomic dead-node recovery op for DELAYED+retries
                                verify(repository, atLeastOnce())
                                        .abandonAndScheduleRetry(
                                                eq(delayedJob.id()),
                                                anyString(),
                                                anyString(),
                                                any(),
                                                any(Instant.class),
                                                eq(1));
                                // completeExecution must NOT be called — replaced by atomic op
                                verify(repository, never()).completeExecution(any(), any(), any(), any(), any());
                                // scheduleRetry must NOT be called separately — replaced by atomic op
                                verify(repository, never()).scheduleRetry(any(), any(), anyInt());
                                coordinator.stop();
                                ctx.completeNow();
                            })))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("heartbeat row is NOT removed when a per-execution recovery write fails")
        void heartbeatRetainedWhenRecoveryFails(Vertx vertx, VertxTestContext ctx) {
            JobExecution delayedJob = execution(JobType.DELAYED, 0, 3);

            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());
            when(repository.findDeadServers(any(Duration.class)))
                    .thenReturn(Future.succeededFuture(List.of("dead-node")));
            when(repository.findByLockedBy("dead-node")).thenReturn(Future.succeededFuture(List.of(delayedJob)));
            // The recovery write fails — the dead server's heartbeat must be left in place so the next
            // scan re-discovers it and retries, rather than orphaning the still-PROCESSING execution.
            when(repository.abandonAndScheduleRetry(any(), any(), any(), any(), any(Instant.class), anyInt()))
                    .thenReturn(Future.failedFuture(new RuntimeException("db down")));
            when(repository.removeServer(anyString())).thenReturn(Future.succeededFuture());

            JobCoordinatorConfig fastConfig = JobCoordinatorConfig.builder()
                    .scanIntervalMs(50)
                    .nodeHeartbeatIntervalMs(60_000)
                    .build();
            JobCoordinator coordinator = new JobCoordinator(vertx, repository, fastConfig);

            coordinator
                    .start()
                    .onSuccess(v -> vertx.setTimer(
                            200,
                            id -> ctx.verify(() -> {
                                verify(repository, atLeastOnce())
                                        .abandonAndScheduleRetry(
                                                eq(delayedJob.id()),
                                                anyString(),
                                                anyString(),
                                                any(),
                                                any(Instant.class),
                                                eq(1));
                                // removeServer must be skipped on a failed recovery
                                verify(repository, never()).removeServer("dead-node");
                                coordinator.stop();
                                ctx.completeNow();
                            })))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("CRON job stays ABANDONED — abandonAndScheduleRetry is NOT called")
        void cronJobStaysAbandoned(Vertx vertx, VertxTestContext ctx) {
            // CRON job — should NOT trigger abandonAndScheduleRetry regardless of attempt count
            JobExecution cronJob = execution(JobType.CRON, 0, 3);

            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());
            when(repository.findDeadServers(any(Duration.class)))
                    .thenReturn(Future.succeededFuture(List.of("dead-node")));
            when(repository.findByLockedBy("dead-node")).thenReturn(Future.succeededFuture(List.of(cronJob)));
            when(repository.completeExecution(any(), eq(JobState.ABANDONED), anyString(), anyString(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(cronJob)));
            when(repository.removeServer(anyString())).thenReturn(Future.succeededFuture());

            JobCoordinatorConfig fastConfig = JobCoordinatorConfig.builder()
                    .scanIntervalMs(50)
                    .nodeHeartbeatIntervalMs(60_000)
                    .build();
            JobCoordinator coordinator = new JobCoordinator(vertx, repository, fastConfig);

            coordinator
                    .start()
                    .onSuccess(v -> vertx.setTimer(
                            200,
                            id -> ctx.verify(() -> {
                                // CRON: completeExecution(ABANDONED) is the correct path
                                verify(repository, atLeastOnce())
                                        .completeExecution(
                                                eq(cronJob.id()),
                                                eq(JobState.ABANDONED),
                                                anyString(),
                                                anyString(),
                                                any());
                                // abandonAndScheduleRetry must NOT be called for CRON
                                verify(repository, never())
                                        .abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
                                // DEAD_LETTER must not be written for CRON
                                verify(repository, never())
                                        .completeExecution(any(), eq(JobState.DEAD_LETTER), any(), any(), any());
                                coordinator.stop();
                                ctx.completeNow();
                            })))
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("DELAYED job with exhausted retries is dead-lettered — abandonAndScheduleRetry is NOT called")
        void delayedJobWithNoRetriesRemainingIsDeadLettered(Vertx vertx, VertxTestContext ctx) {
            // attempt=2, maxAttempts=3 → attempt+1=3 is NOT < 3, so exhausted path applies
            JobExecution exhausted = execution(JobType.DELAYED, 2, 3);

            when(repository.serverHeartbeat(anyString())).thenReturn(Future.succeededFuture());
            when(repository.findDeadServers(any(Duration.class)))
                    .thenReturn(Future.succeededFuture(List.of("dead-node")));
            when(repository.findByLockedBy("dead-node")).thenReturn(Future.succeededFuture(List.of(exhausted)));
            when(repository.completeExecution(any(), eq(JobState.DEAD_LETTER), anyString(), anyString(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exhausted)));
            when(repository.removeServer(anyString())).thenReturn(Future.succeededFuture());

            JobCoordinatorConfig fastConfig = JobCoordinatorConfig.builder()
                    .scanIntervalMs(50)
                    .nodeHeartbeatIntervalMs(60_000)
                    .build();
            JobCoordinator coordinator = new JobCoordinator(vertx, repository, fastConfig);

            coordinator
                    .start()
                    .onSuccess(v -> vertx.setTimer(
                            200,
                            id -> ctx.verify(() -> {
                                // Exhausted DELAYED: completeExecution(DEAD_LETTER) is the correct path
                                verify(repository, atLeastOnce())
                                        .completeExecution(
                                                eq(exhausted.id()),
                                                eq(JobState.DEAD_LETTER),
                                                anyString(),
                                                anyString(),
                                                any());
                                // abandonAndScheduleRetry must NOT be called — exhausted
                                verify(repository, never())
                                        .abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
                                coordinator.stop();
                                ctx.completeNow();
                            })))
                    .onFailure(ctx::failNow);
        }
    }

    // --- Cancellation ---

    @Nested
    @DisplayName("cancelExecution()")
    class CancelExecution {

        @Test
        @DisplayName("cancelExecution publishes event-bus signal and marks execution CANCELLED")
        void cancelPublishesEventAndUpdatesState(Vertx vertx, VertxTestContext ctx) {
            UUID executionId = UUID.randomUUID();
            when(repository.completeExecution(eq(executionId), eq(JobState.CANCELLED), anyString(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            // Register a consumer to capture the cancel event
            vertx.eventBus()
                    .consumer(
                            "job.cancel." + executionId,
                            msg -> ctx.verify(() -> {
                                assertEquals("cancel", msg.body());
                                ctx.completeNow();
                            }));

            JobCoordinator coordinator = new JobCoordinator(vertx, repository, defaultConfig);
            coordinator.cancelExecution(executionId).onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("cancelExecution calls completeExecution with CANCELLED state")
        void cancelCallsCompleteExecution(Vertx vertx, VertxTestContext ctx) {
            UUID executionId = UUID.randomUUID();
            ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<JobState> stateCaptor = ArgumentCaptor.forClass(JobState.class);

            when(repository.completeExecution(any(), any(), anyString(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            JobCoordinator coordinator = new JobCoordinator(vertx, repository, defaultConfig);

            coordinator
                    .cancelExecution(executionId)
                    .onSuccess(v -> ctx.verify(() -> {
                        verify(repository)
                                .completeExecution(
                                        idCaptor.capture(), stateCaptor.capture(), anyString(), any(), any());
                        org.junit.jupiter.api.Assertions.assertEquals(executionId, idCaptor.getValue());
                        org.junit.jupiter.api.Assertions.assertEquals(JobState.CANCELLED, stateCaptor.getValue());
                        ctx.completeNow();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    // --- Static import helper (not a real import — uses fully-qualified for clarity) ---

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
