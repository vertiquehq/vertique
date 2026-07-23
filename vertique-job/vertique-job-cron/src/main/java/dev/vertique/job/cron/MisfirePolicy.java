// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

/**
 * Policy for handling cron job fires that were missed (e.g., all nodes were down during the
 * scheduled fire time).
 *
 * <p>Misfire detection runs at scheduler startup for each {@link ExecutionMode#SINGLE_INSTANCE}
 * job with a persisted schedule. It compares {@code job_schedules.last_fired_at} with the most
 * recent expected fire time computed from the cron expression.
 *
 * <p>Misfire detection requires a {@link dev.vertique.job.JobRepository} binding. Jobs without
 * a repository or with {@code tracked=false} cannot detect misfires and always behave as
 * {@link #SKIP}.
 *
 * @see CronJob#misfirePolicy()
 * @see CronJobDefinition#misfirePolicy()
 */
public enum MisfirePolicy {

    /**
     * Execute the most recent missed fire immediately on startup. Only fires once even if multiple
     * ticks were missed. This is the default for {@link ExecutionMode#SINGLE_INSTANCE} jobs.
     */
    FIRE_NOW,

    /**
     * Ignore missed fires and wait for the next scheduled tick. This is the default for
     * {@link ExecutionMode#EVERY_INSTANCE} jobs, and the effective behaviour for any job that
     * lacks a {@link dev.vertique.job.JobRepository} or has {@code tracked=false}.
     */
    SKIP,

    /**
     * Execute every missed fire in sequence, from oldest to newest. Use for jobs where every tick
     * must be processed (e.g., hourly billing reconciliation). Capped at
     * {@value dev.vertique.job.cron.CronScheduler#MAX_MISFIRE_FIRES} fires to avoid overwhelming
     * the system after a prolonged outage.
     */
    FIRE_ALL
}
