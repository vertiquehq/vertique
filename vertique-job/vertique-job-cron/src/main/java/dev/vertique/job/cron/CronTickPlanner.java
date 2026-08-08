// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Plans the next tick for a cron job: the nominal fire instant and the real timer delay to arm.
 *
 * <p>A planner is a pure function of its arguments — implementations are stateless, so the same
 * arguments always produce the same {@link Tick}. All per-job progression state the planner needs
 * is threaded in through {@code previousScheduledAt} by {@link CronScheduler}'s scheduling
 * recursion; the planner itself holds none.
 *
 * <p>The production implementation is {@link SystemCronTickPlanner}, which derives both values from
 * wall-clock time. The seam exists so tests can arm the scheduler's real
 * {@link io.vertx.core.Vertx#setTimer(long, io.vertx.core.Handler) setTimer} loop without paying the
 * whole-second alignment floor that {@link CronExpression#computeNextFireTime(Instant, java.time.ZoneId)}
 * imposes — timer, cancellation, and event-loop semantics stay real; only the
 * {@code (scheduledAt, delayMs)} computation is injected.
 *
 * <p>A planner that throws is treated exactly like a throwing {@link CronExpression}: the failure is
 * caught and logged by {@link CronScheduler}, and the job is <em>not</em> rescheduled.
 */
@FunctionalInterface
interface CronTickPlanner {

    /**
     * Plans the next tick for the given job.
     *
     * @param job                 the job being scheduled
     * @param previousScheduledAt the previous tick's nominal fire instant, or {@code null} on the
     *                            first plan after {@link CronScheduler#start()}
     * @param now                 the current instant, as read by the scheduler at the call site
     * @return the planned tick; never {@code null}
     */
    Tick plan(CronJobDefinition job, @Nullable Instant previousScheduledAt, Instant now);

    /**
     * A planned tick: the nominal fire instant reported to the job and the real delay armed on the
     * Vert.x timer.
     *
     * @param scheduledAt nominal fire instant (whole-second, strictly after its origin)
     * @param delayMs     real timer delay in milliseconds; must be {@code >= 1}
     */
    record Tick(Instant scheduledAt, long delayMs) {}
}
