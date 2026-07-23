// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import java.time.Duration;

/**
 * Configuration for the branch-recovery scan (PRD-WF-002 §A.4.3).
 *
 * <p>The recovery service runs as a {@code SINGLE_INSTANCE} cron job (see
 * {@link WorkflowBranchRecoveryServiceImpl}) and reconciles two classes of branch tokens:
 * <ul>
 *   <li>Due {@code RETRY_SCHEDULED} branches (whose {@code next_retry_at} has passed) — resumed
 *       through {@code BranchTransitionEngine}, with the owning join re-evaluated on terminal
 *       advance.</li>
 *   <li>Stale {@code RUNNING} branches (whose {@code updated_at} is older than
 *       {@link #staleThreshold()}) — demoted to {@code RETRY_SCHEDULED} (if attempts remain) or
 *       {@code FAILED} (otherwise).</li>
 * </ul>
 *
 * <p>Default values are conservative and appropriate for most production deployments:
 * <ul>
 *   <li>{@code batchSize=1000} — at most 1000 rows per status per scan cycle.</li>
 *   <li>{@code staleThreshold=5m} — only demote {@code RUNNING} branches whose
 *       {@code updated_at} is at least 5 minutes in the past, leaving real in-flight work
 *       alone.</li>
 * </ul>
 *
 * <p>The scan cadence itself is owned by the cron expression on
 * {@link WorkflowBranchRecoveryServiceImpl#reconcile()} (default {@code "*}{@code /30 * * * * *"});
 * operators tune via the {@code cron.jobs.workflow-branch-recovery.*} config subtree, not here.
 *
 * @param batchSize       the maximum number of recoverable branches to process per status per
 *                        scan cycle
 * @param staleThreshold  how long a {@code RUNNING} branch must be untouched before recovery
 *                        considers it stale; branches updated within the threshold are left to
 *                        normal execution
 */
public record WorkflowBranchRecoveryConfig(int batchSize, Duration staleThreshold) {

    /**
     * Returns the default configuration for the branch-recovery service.
     *
     * <p>Defaults: 1000-row batch, 5-minute stale threshold.
     *
     * @return the default recovery configuration
     */
    public static WorkflowBranchRecoveryConfig defaults() {
        return new WorkflowBranchRecoveryConfig(1000, Duration.ofMinutes(5));
    }
}
