// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.BackoffStrategy;
import io.vertx.core.Future;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * Reusable utility that handles job completion: persists terminal state, schedules retries
 * with backoff, and transitions exhausted executions to dead-letter.
 *
 * <p>Used by job triggers (cron, delayed-job) in their per-execution reply handlers to
 * delegate DB updates without duplicating retry/dead-letter logic.
 *
 * <p>If no {@link JobRepository} is provided ({@code null}), completion handling is a no-op —
 * this supports the in-memory-only mode used by cron Phase 1.
 */
@Slf4j
public class JobCompletionHandler {

    private final JobRepository repository;

    /**
     * Creates a new completion handler.
     *
     * @param repository the job repository for persistence, or {@code null} for in-memory mode
     */
    public JobCompletionHandler(JobRepository repository) {
        this.repository = repository;
    }

    /**
     * Handles the completion of a job execution.
     *
     * <p>On success, transitions to {@code SUCCEEDED}. On failure, checks if retries remain
     * and either schedules a retry with the given backoff strategy or transitions to
     * {@code DEAD_LETTER}.
     *
     * @param execution       the execution that completed
     * @param result          the execution result (success or failure); {@code null} is treated
     *                        as success
     * @param backoffStrategy the backoff strategy for computing retry delays
     * @return a future that completes when persistence is done (or immediately if no repository)
     */
    public Future<Void> handleCompletion(JobExecution execution, Result<?> result, BackoffStrategy backoffStrategy) {
        if (repository == null) {
            return Future.succeededFuture();
        }

        if (result == null || result.isSuccess()) {
            return repository
                    .completeExecution(execution.id(), JobState.SUCCEEDED, null, null, execution.progress())
                    .mapEmpty();
        }

        // --- Failure path ---

        Throwable cause = result.cause();
        String errorMessage = cause != null ? cause.getMessage() : "Unknown error";
        String errorType = cause != null ? cause.getClass().getName() : null;

        if (execution.attemptNumber() + 1 < execution.maxAttempts()) {
            // Retry: compute next scheduled time using the provided backoff strategy
            int nextAttempt = execution.attemptNumber() + 1;
            long delayMs = backoffStrategy.delay(nextAttempt);
            Instant nextScheduledAt = Instant.now().plusMillis(delayMs);

            log.info(
                    "Scheduling retry for job '{}' execution {} — attempt {}/{}, delay {}ms",
                    execution.jobId(),
                    execution.id(),
                    nextAttempt + 1,
                    execution.maxAttempts(),
                    delayMs);

            // Atomically record the PROCESSING -> FAILED attempt-failure (observable to state-transition
            // listeners) and re-enqueue the row in one transaction, so a crash can never strand the
            // execution in FAILED. A no-op (already terminal) returns empty and schedules nothing.
            return repository
                    .failAndScheduleRetry(
                            execution.id(), errorMessage, errorType, execution.progress(), nextScheduledAt, nextAttempt)
                    .mapEmpty();
        }

        // Exhausted: transition to dead-letter
        log.warn(
                "Job '{}' execution {} exhausted all {} attempts — transitioning to DEAD_LETTER",
                execution.jobId(),
                execution.id(),
                execution.maxAttempts());

        return repository
                .completeExecution(execution.id(), JobState.DEAD_LETTER, errorMessage, errorType, execution.progress())
                .mapEmpty();
    }
}
