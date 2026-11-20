// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;

/**
 * Production {@link CronTickPlanner}: derives the next tick from wall-clock time through the job's
 * own {@link CronExpression}.
 *
 * <p>The nominal fire instant is {@code cronExpression().computeNextFireTime(now, timezone())} —
 * whole-second and strictly after {@code now} — and the delay is the millisecond distance between
 * the two, clamped to at least {@code 1} so a timer is always armed even when the next occurrence
 * rounds to less than a millisecond away.
 *
 * <p>Production progression is derived from wall clock, so instants that elapsed while a fire was
 * in flight are skipped rather than replayed. {@code previousScheduledAt} is consulted in one case
 * only: the timer of a tick can run a moment before its nominal instant on the wall clock (the
 * delay is truncated to whole milliseconds, and the timer runs on the monotonic clock), and
 * planning from {@code now} then returns the instant that is firing. The planner then takes the
 * occurrence after it instead, so the tick fires once. Any other previous instant, one already
 * passed or one left ahead of {@code now} by a backward clock step, does not change the tick.
 */
final class SystemCronTickPlanner implements CronTickPlanner {

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the job's expression has no next fire time (propagated from
     *     {@link CronExpression#computeNextFireTime(Instant, java.time.ZoneId)})
     */
    @Override
    public Tick plan(CronJobDefinition job, @Nullable Instant previousScheduledAt, Instant now) {
        Instant scheduledAt = job.cronExpression().computeNextFireTime(now, job.timezone());
        if (scheduledAt.equals(previousScheduledAt)) {
            // the timer of the firing tick ran early: plan the following occurrence, not this one again
            scheduledAt = job.cronExpression().computeNextFireTime(previousScheduledAt, job.timezone());
        }
        long delayMs = Math.max(1L, Duration.between(now, scheduledAt).toMillis());
        return new Tick(scheduledAt, delayMs);
    }
}
