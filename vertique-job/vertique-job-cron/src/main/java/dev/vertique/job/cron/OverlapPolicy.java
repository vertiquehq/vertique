// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

/**
 * Policy for handling cron job fires that overlap with a still-running previous execution.
 *
 * <p>The policy is applied per-job and is configured via the {@link CronJob#overlapPolicy()}
 * annotation attribute or a {@code "overlapPolicy"} config override.
 *
 * @see CronJob#overlapPolicy()
 * @see CronJobDefinition#overlapPolicy()
 */
public enum OverlapPolicy {

    /**
     * Skip the fire if the previous execution is still in progress.
     *
     * <p>This is the default policy. A warning is logged when a fire is skipped. Suitable for
     * idempotent jobs where missing a fire is acceptable.
     */
    SKIP,

    /**
     * Queue one missed fire to run immediately after the current execution completes.
     *
     * <p>If multiple fires are missed while the current execution is in progress, only the most
     * recent scheduled time is queued — earlier queued fires are replaced with a warning log.
     *
     * <p><strong>Restriction:</strong> Only supported with {@link ExecutionMode#EVERY_INSTANCE}.
     * {@link ExecutionMode#SINGLE_INSTANCE} is not currently supported; when it is, it will
     * require {@link #SKIP} because {@code QUEUE_ONE} needs cross-node coordination via a
     * database (planned for Phase 2).
     */
    QUEUE_ONE
}
