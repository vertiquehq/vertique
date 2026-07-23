// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Delayed job queue: persistent single-task deferred execution with retry and dead-letter.
 *
 * <p>Provides {@link dev.vertique.job.delayed.DelayedJobService} for enqueueing jobs and
 * {@link dev.vertique.job.delayed.DelayedJobPoller} for claiming and dispatching ready jobs
 * via fire-and-report through the event bus.
 */
package dev.vertique.job.delayed;
