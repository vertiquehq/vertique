// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.context.DurableMetadata;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable record representing a job execution.
 *
 * <p>A delayed job reuses a single {@code JobExecution} row across its retry attempts — the
 * {@code id} is stable and {@code attemptNumber} increments on each retry (a specific attempt is
 * identified by {@code id} + {@code attemptNumber}). Each cron fire, by contrast, creates a distinct
 * execution. One logical job (identified by {@code jobId}) thus maps to one reused row for a delayed
 * job, or many rows for a recurring cron job.
 *
 * @param id            unique execution identifier; stable across retries for a delayed job
 *                      ({@code attemptNumber} distinguishes attempts), distinct per cron fire
 * @param jobId         logical job identifier (stable across retries)
 * @param jobType       scheduling mechanism that triggered this execution
 * @param handler       event bus address of the handler processing this job
 * @param queue         logical queue or group this execution belongs to
 * @param state         current lifecycle state
 * @param attemptNumber zero-based attempt counter (0 = first attempt)
 * @param maxAttempts   maximum number of attempts allowed (inclusive)
 * @param payload       the typed job data passed to the handler (serialized as JSONB in DB), or
 *                      {@code null} if no payload was provided
 * @param priority      priority for claiming order — higher values are claimed first; default is 0
 * @param lockedBy      node identity of the worker currently processing this execution, or
 *                      {@code null} when not in progress
 * @param scheduledAt   when this execution was scheduled
 * @param enqueuedAt    when this execution was placed on the work queue, or {@code null}
 * @param startedAt     when processing began, or {@code null}
 * @param completedAt   when processing finished (success or failure), or {@code null}
 * @param errorMessage  human-readable error message on failure, or {@code null}
 * @param errorType     the exception class name on failure, or {@code null}
 * @param progress      latest progress snapshot, or {@code ProgressSnapshot#EMPTY}
 * @param parameters    static parameters provided at scheduling time (immutable copy)
 * @param attributes    runtime attributes set by interceptors or handlers (immutable copy)
 * @param metadata      durable context-propagation metadata (FR-CTX-177) persisted as
 *                      {@code {"context":{...}}} JSONB. Captured at enqueue via
 *                      {@code DurableContextPropagator.mergeCaptured(..., DELAYED_JOB)} and
 *                      decoded on poll via
 *                      {@code DurableContextPropagator.decodeToDispatchContext(..., DELAYED_JOB)}.
 *                      Never {@code null} — defaults to {@link DurableMetadata#empty()}.
 */
public record JobExecution(
        UUID id,
        String jobId,
        JobType jobType,
        String handler,
        String queue,
        JobState state,
        int attemptNumber,
        int maxAttempts,
        Object payload,
        int priority,
        String lockedBy,
        Instant scheduledAt,
        Instant enqueuedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        String errorType,
        ProgressSnapshot progress,
        Map<String, Object> parameters,
        Map<String, Object> attributes,
        DurableMetadata metadata) {

    /**
     * Compact constructor that defensively copies the maps to ensure immutability.
     * The {@code payload} field is left as-is because it is an opaque reference.
     *
     * <p>{@code metadata} carries durable context-propagation metadata (FR-CTX-177) as a
     * {@link DurableMetadata} namespaced document. Persisted into
     * {@code job_executions.metadata JSONB} via {@link DurableMetadata#toCarrier()} so the
     * original ambient durable context survives the delayed-job persistence hop. On poll,
     * {@code DelayedJobPoller.dispatch} decodes the column via
     * {@code DurableContextPropagator.decodeToDispatchContext(..., DispatchBoundary.DELAYED_JOB)}
     * and merges the result into the outgoing service envelope's caller-overrides.
     */
    public JobExecution {
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        metadata = metadata != null ? metadata : DurableMetadata.empty();
        progress = progress != null ? progress : ProgressSnapshot.EMPTY;
    }

    /**
     * Returns a copy of this execution with the given state.
     *
     * @param newState the new lifecycle state
     * @return updated execution record
     */
    public JobExecution withState(JobState newState) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                newState,
                attemptNumber,
                maxAttempts,
                payload,
                priority,
                lockedBy,
                scheduledAt,
                enqueuedAt,
                startedAt,
                completedAt,
                errorMessage,
                errorType,
                progress,
                parameters,
                attributes,
                metadata);
    }

    /**
     * Returns a copy of this execution with the given progress snapshot.
     *
     * @param newProgress the updated progress snapshot
     * @return updated execution record
     */
    public JobExecution withProgress(ProgressSnapshot newProgress) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                state,
                attemptNumber,
                maxAttempts,
                payload,
                priority,
                lockedBy,
                scheduledAt,
                enqueuedAt,
                startedAt,
                completedAt,
                errorMessage,
                errorType,
                newProgress,
                parameters,
                attributes,
                metadata);
    }

    /**
     * Returns a copy of this execution with the given payload.
     *
     * @param newPayload the new job payload
     * @return updated execution record
     */
    public JobExecution withPayload(Object newPayload) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                state,
                attemptNumber,
                maxAttempts,
                newPayload,
                priority,
                lockedBy,
                scheduledAt,
                enqueuedAt,
                startedAt,
                completedAt,
                errorMessage,
                errorType,
                progress,
                parameters,
                attributes,
                metadata);
    }

    /**
     * Returns a copy of this execution with the given node identity recorded as the lock holder.
     *
     * @param newLockedBy the node identity of the worker claiming this execution, or {@code null}
     *                    to release the lock
     * @return updated execution record
     */
    public JobExecution withLockedBy(String newLockedBy) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                state,
                attemptNumber,
                maxAttempts,
                payload,
                priority,
                newLockedBy,
                scheduledAt,
                enqueuedAt,
                startedAt,
                completedAt,
                errorMessage,
                errorType,
                progress,
                parameters,
                attributes,
                metadata);
    }

    /**
     * Returns a copy of this execution with the given error information set.
     *
     * @param message the human-readable error message
     * @param type    the exception class name
     * @return updated execution record
     */
    public JobExecution withError(String message, String type) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                state,
                attemptNumber,
                maxAttempts,
                payload,
                priority,
                lockedBy,
                scheduledAt,
                enqueuedAt,
                startedAt,
                completedAt,
                message,
                type,
                progress,
                parameters,
                attributes,
                metadata);
    }

    /**
     * Returns a copy of this execution with the given start time and lock holder recorded.
     *
     * <p>Typically called when a worker claims the execution and begins processing.
     *
     * @param startTime the time at which processing began
     * @param nodeId    the node identity of the worker starting this execution
     * @return updated execution record
     */
    public JobExecution withStarted(Instant startTime, String nodeId) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                state,
                attemptNumber,
                maxAttempts,
                payload,
                priority,
                nodeId,
                scheduledAt,
                enqueuedAt,
                startTime,
                completedAt,
                errorMessage,
                errorType,
                progress,
                parameters,
                attributes,
                metadata);
    }

    /**
     * Returns a copy of this execution with the given completion time recorded.
     *
     * <p>Typically called after processing finishes, regardless of outcome.
     *
     * @param completionTime the time at which processing finished
     * @return updated execution record
     */
    public JobExecution withCompleted(Instant completionTime) {
        return new JobExecution(
                id,
                jobId,
                jobType,
                handler,
                queue,
                state,
                attemptNumber,
                maxAttempts,
                payload,
                priority,
                lockedBy,
                scheduledAt,
                enqueuedAt,
                startedAt,
                completionTime,
                errorMessage,
                errorType,
                progress,
                parameters,
                attributes,
                metadata);
    }
}
