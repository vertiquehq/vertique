// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.context.DurableMetadata;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * Curated, safe-by-type fact describing a persisted job state transition, delivered to
 * {@link JobExecutionStateTransitionListener}s by the repository decorator.
 *
 * <p>This event deliberately carries only low-cardinality identifiers, the new state, attempt counts,
 * timing, the exception class name, and the persisted {@link DurableMetadata} context document. It
 * <strong>never</strong> carries the job payload, the raw error message, or the {@code parameters} /
 * {@code attributes} maps (PRD-AUD-002 §10.3). {@code metadata} is the durable context carrier
 * (correlation / localization namespaces only — no payload or credentials); consumers that need
 * correlation decode it through the correlation runtime, keeping this module free of that dependency.
 *
 * @param executionId   the unique execution identifier
 * @param jobId         the logical job identifier
 * @param jobType       the scheduling mechanism that created the execution
 * @param queue         the queue the execution ran on
 * @param newState      the state the execution transitioned <em>to</em> (note: {@code ABANDONED} is
 *                      non-terminal, so this is "new state", not "terminal state")
 * @param attemptNumber the zero-based attempt number for this execution
 * @param maxAttempts   the configured maximum attempts
 * @param errorType     the persisted (fully-qualified) exception class name, or {@code null} on success
 * @param scheduledAt   when the execution was scheduled
 * @param enqueuedAt    when the execution was enqueued
 * @param startedAt     when processing began, or {@code null} if the job never ran
 * @param completedAt   when the execution reached {@code newState}
 * @param metadata      the persisted durable context document (never {@code null})
 */
public record JobExecutionStateTransitionEvent(
        UUID executionId,
        String jobId,
        JobType jobType,
        String queue,
        JobState newState,
        int attemptNumber,
        int maxAttempts,
        @Nullable String errorType,
        Instant scheduledAt,
        Instant enqueuedAt,
        Instant startedAt,
        Instant completedAt,
        DurableMetadata metadata) {}
