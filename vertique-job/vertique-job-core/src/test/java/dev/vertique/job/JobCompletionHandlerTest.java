// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.BackoffStrategy;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.Map;
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
 * Unit tests for {@link JobCompletionHandler}: success path, retry scheduling,
 * dead-letter transition, null-result handling, and no-op without a repository.
 */
@DisplayName("JobCompletionHandler")
@ExtendWith(MockitoExtension.class)
class JobCompletionHandlerTest {

    @Mock
    private JobRepository repository;

    private JobCompletionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new JobCompletionHandler(repository);
        // Use lenient stubs so tests that only exercise one branch don't fail with
        // UnnecessaryStubbingException — both branches (completeExecution / scheduleRetry)
        // are set up here for convenience, but not every test invokes both.
        lenient()
                .when(repository.completeExecution(any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(Optional.empty()));
        // Stub for the atomic retry path: failAndScheduleRetry returns the FAILED snapshot so the
        // retry branch proceeds. A present Optional means the transition happened; empty is no-op.
        lenient()
                .when(repository.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Future.succeededFuture(Optional.of(mock(JobExecution.class))));
        lenient().when(repository.scheduleRetry(any(), any(), anyInt())).thenReturn(Future.succeededFuture());
    }

    /** Builds a {@link JobExecution} with the given attempt number and max attempts. */
    private JobExecution buildExecution(int attemptNumber, int maxAttempts) {
        return new JobExecution(
                UUID.randomUUID(),
                "test-job",
                JobType.CRON,
                "handler.address",
                "default",
                JobState.PROCESSING,
                attemptNumber,
                maxAttempts,
                null,
                0,
                "node-1",
                Instant.now(),
                null,
                Instant.now(),
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());
    }

    // --- Success path ---

    @Nested
    @DisplayName("success result")
    class SuccessResult {

        @Test
        @DisplayName("success result completes execution as SUCCEEDED")
        void successResultCompletesAsSucceeded() {
            JobExecution exec = buildExecution(0, 3);
            Result<String> result = Result.success("ok");

            Future<Void> future = handler.handleCompletion(exec, result, BackoffStrategy.none());

            assertTrue(future.succeeded());
            verify(repository).completeExecution(exec.id(), JobState.SUCCEEDED, null, null, exec.progress());
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
            verify(repository, never()).failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("null result treated as success")
        void nullResultTreatedAsSuccess() {
            JobExecution exec = buildExecution(0, 3);

            Future<Void> future = handler.handleCompletion(exec, null, BackoffStrategy.none());

            assertTrue(future.succeeded());
            verify(repository).completeExecution(exec.id(), JobState.SUCCEEDED, null, null, exec.progress());
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
            verify(repository, never()).failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
        }
    }

    // --- Retry path ---

    @Nested
    @DisplayName("failure with attempts remaining")
    class FailureWithAttemptsRemaining {

        @Test
        @DisplayName("failure with attempts remaining calls failAndScheduleRetry atomically")
        void failureSchedulesRetry() {
            JobExecution exec = buildExecution(0, 3);
            Result<Void> result = Result.failure(new RuntimeException("transient error"));

            Future<Void> future = handler.handleCompletion(exec, result, BackoffStrategy.none());

            assertTrue(future.succeeded());
            // The handler now delegates to the atomic failAndScheduleRetry — not the two-step pair
            verify(repository)
                    .failAndScheduleRetry(eq(exec.id()), any(), any(), eq(exec.progress()), any(Instant.class), eq(1));
            // scheduleRetry must NOT be called directly — the atomic operation handles it
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
        }

        @Test
        @DisplayName("backoff strategy computes delay for retry")
        void backoffStrategyComputesDelay() {
            JobExecution exec = buildExecution(0, 3);
            Result<Void> result = Result.failure(new RuntimeException("error"));

            long fixedDelayMs = 5_000L;
            BackoffStrategy strategy = BackoffStrategy.fixed(fixedDelayMs);

            Instant before = Instant.now();
            handler.handleCompletion(exec, result, strategy);
            Instant after = Instant.now();

            // Capture the nextScheduledAt argument passed to failAndScheduleRetry
            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(repository)
                    .failAndScheduleRetry(eq(exec.id()), any(), any(), eq(exec.progress()), captor.capture(), eq(1));

            Instant scheduledAt = captor.getValue();
            assertNotNull(scheduledAt);
            // next scheduled time should be approximately now + fixedDelayMs
            assertTrue(
                    scheduledAt.isAfter(before.plusMillis(fixedDelayMs - 500)),
                    "scheduledAt should be at least fixedDelayMs after before");
            assertTrue(
                    scheduledAt.isBefore(after.plusMillis(fixedDelayMs + 500)),
                    "scheduledAt should not be more than fixedDelayMs + 500ms after after");
        }

        @Test
        @DisplayName("increments attempt counter passed to failAndScheduleRetry")
        void incrementsAttemptCounter() {
            // Second attempt (attemptNumber=1), maxAttempts=5 — still retryable
            JobExecution exec = buildExecution(1, 5);
            Result<Void> result = Result.failure(new RuntimeException("err"));

            handler.handleCompletion(exec, result, BackoffStrategy.none());

            verify(repository)
                    .failAndScheduleRetry(eq(exec.id()), any(), any(), eq(exec.progress()), any(Instant.class), eq(2));
        }

        @Test
        @DisplayName("retry is skipped when the failAndScheduleRetry transition is a no-op")
        void retryIsSkippedWhenFailedTransitionIsNoOp() {
            // Override the failAndScheduleRetry stub to return empty — simulates a concurrent
            // terminal transition that pre-empted this execution
            lenient()
                    .when(repository.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            JobExecution exec = buildExecution(0, 3);
            Result<Void> result = Result.failure(new RuntimeException("x"));

            Future<Void> future = handler.handleCompletion(exec, result, BackoffStrategy.none());

            assertTrue(future.succeeded());
            // scheduleRetry is never called because failAndScheduleRetry is the atomic operation
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
        }
    }

    // --- Dead-letter path ---

    @Nested
    @DisplayName("failure with exhausted attempts")
    class FailureWithExhaustedAttempts {

        @Test
        @DisplayName("failure with exhausted attempts transitions to DEAD_LETTER")
        void exhaustedAttemptsTransitionsToDeadLetter() {
            // Third attempt (attemptNumber=2), maxAttempts=3 — no more retries
            JobExecution exec = buildExecution(2, 3);
            RuntimeException cause = new RuntimeException("fatal error");
            Result<Void> result = Result.failure(cause);

            Future<Void> future = handler.handleCompletion(exec, result, BackoffStrategy.none());

            assertTrue(future.succeeded());
            verify(repository)
                    .completeExecution(
                            exec.id(),
                            JobState.DEAD_LETTER,
                            "fatal error",
                            RuntimeException.class.getName(),
                            exec.progress());
            verify(repository, never()).scheduleRetry(any(), any(), any(int.class));
        }

        @Test
        @DisplayName("null cause message is propagated as-is")
        void nullCauseMessagePropagatedAsIs() {
            // Use an exception with null message — the handler should store null for errorMessage
            JobExecution exec = buildExecution(2, 3);
            RuntimeException cause = new RuntimeException((String) null);
            Result<Void> result = Result.failure(cause);

            handler.handleCompletion(exec, result, BackoffStrategy.none());

            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository)
                    .completeExecution(
                            eq(exec.id()), eq(JobState.DEAD_LETTER), msgCaptor.capture(), typeCaptor.capture(), any());

            assertNull(msgCaptor.getValue());
            assertEquals(RuntimeException.class.getName(), typeCaptor.getValue());
        }
    }

    // --- No-op without repository ---

    @Nested
    @DisplayName("null repository (in-memory mode)")
    class NullRepository {

        @Test
        @DisplayName("null repository is no-op and returns succeeded future")
        void nullRepositoryIsNoOp() {
            JobCompletionHandler noRepoHandler = new JobCompletionHandler(null);
            JobExecution exec = buildExecution(0, 3);
            Result<String> result = Result.success("ok");

            Future<Void> future = noRepoHandler.handleCompletion(exec, result, BackoffStrategy.none());

            assertTrue(future.succeeded());
            // No interactions on the mocked repository
            verify(repository, never()).completeExecution(any(), any(), any(), any(), any());
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
            verify(repository, never()).failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("null repository is no-op even on failure")
        void nullRepositoryIsNoOpOnFailure() {
            JobCompletionHandler noRepoHandler = new JobCompletionHandler(null);
            JobExecution exec = buildExecution(2, 3);
            Result<Void> result = Result.failure(new RuntimeException("fatal"));

            Future<Void> future = noRepoHandler.handleCompletion(exec, result, BackoffStrategy.none());

            assertTrue(future.succeeded());
        }
    }

    // --- Edge case: maxAttempts == 1 ---

    @Nested
    @DisplayName("single attempt job")
    class SingleAttemptJob {

        @Test
        @DisplayName("failure on single-attempt job goes straight to DEAD_LETTER")
        void singleAttemptFailureGoesToDeadLetter() {
            JobExecution exec = buildExecution(0, 1);
            Result<Void> result = Result.failure(new RuntimeException("instant fail"));

            handler.handleCompletion(exec, result, BackoffStrategy.none());

            verify(repository).completeExecution(eq(exec.id()), eq(JobState.DEAD_LETTER), any(), any(), any());
            verify(repository, never()).scheduleRetry(any(), any(), anyInt());
            verify(repository, never()).failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
        }
    }
}
