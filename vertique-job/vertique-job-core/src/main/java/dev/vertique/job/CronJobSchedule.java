// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;

/**
 * Immutable record representing a cron job schedule definition persisted in the
 * {@code job_schedules} table for dashboard visibility.
 *
 * <p>Schedule definitions are written by {@link dev.vertique.job.JobRepository#saveSchedule} at
 * application startup using an UPSERT — code (annotations + config) is always the source of
 * truth. The dashboard reads this table to display which jobs are configured, when they last
 * fired, and when they will fire next.
 *
 * @param jobId          unique job identifier
 * @param cronExpression cron expression string (e.g. {@code "0 0 8 * * *"})
 * @param handler        event bus address of the handler service method; kept for backward
 *                       compatibility with rows written before the {@code target} field was added;
 *                       may be {@code null} for {@code service:} targets
 * @param target         canonical target reference string (e.g. {@code "service:my.op"} or
 *                       {@code "eventbus:some/address"}); {@code null} for legacy rows written
 *                       before this field was introduced (callers should derive the target as
 *                       {@code "eventbus:" + handler} in that case)
 * @param executionMode  execution mode name: {@code "EVERY_INSTANCE"} or {@code "SINGLE_INSTANCE"}
 * @param timezone       timezone ID used to evaluate the cron expression (e.g. {@code "UTC"})
 * @param enabled        whether the schedule is currently active
 * @param overlapPolicy  overlap policy name: {@code "SKIP"} or {@code "QUEUE_ONE"}
 * @param maxAttempts    maximum number of attempts per execution
 * @param tracked        whether individual executions are persisted to {@code job_executions}
 * @param lastFiredAt    the instant this job last fired, or {@code null} if it has never fired
 * @param nextFireAt     the computed next fire instant, or {@code null} if not yet computed
 */
public record CronJobSchedule(
        String jobId,
        String cronExpression,
        String handler,
        String target,
        String executionMode,
        String timezone,
        boolean enabled,
        String overlapPolicy,
        int maxAttempts,
        boolean tracked,
        Instant lastFiredAt,
        Instant nextFireAt) {}
