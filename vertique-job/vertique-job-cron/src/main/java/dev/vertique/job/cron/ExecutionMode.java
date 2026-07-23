// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

/**
 * Controls whether a cron job fires on every application instance or is restricted to a single
 * instance per cluster per fire time.
 *
 * <p>Choose the appropriate mode based on whether the job's side effects are idempotent across
 * multiple concurrent executions:
 * <ul>
 *   <li>{@code EVERY_INSTANCE} is appropriate for local cache warm-up, per-instance health
 *       checks, or any work that is scoped to the individual instance.</li>
 *   <li>{@code SINGLE_INSTANCE} is appropriate for cluster-wide jobs that must run exactly once
 *       (e.g., database maintenance, report generation, scheduled notifications). Requires a
 *       {@link dev.vertique.job.JobRepository} binding for INSERT ON CONFLICT leader election.</li>
 * </ul>
 */
public enum ExecutionMode {

    /**
     * The job fires on every application instance independently.
     * No distributed locking is used; all instances fire at approximately the same time.
     */
    EVERY_INSTANCE,

    /**
     * Runs on exactly one instance cluster-wide per fire time. Requires a
     * {@link dev.vertique.job.JobRepository} binding for INSERT ON CONFLICT leader election.
     * Only {@link OverlapPolicy#SKIP} is supported.
     */
    SINGLE_INSTANCE
}
