// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recovery;

import java.time.Duration;

/**
 * Configuration for the {@link WorkflowTimerRecoveryService} orphan and dead-letter recovery scan.
 *
 * <p>The recovery service runs as a {@code SINGLE_INSTANCE} cron job (see
 * {@link WorkflowTimerRecoveryServiceImpl}) and detects timers whose underlying delayed jobs may
 * have been lost (e.g., after a JVM restart) or have moved to terminal states
 * ({@code DEAD_LETTER}, {@code SUCCEEDED}) without having properly fired.
 *
 * <p>Default values are conservative and appropriate for most production deployments:
 * <ul>
 *   <li>{@code batchSize=1000} — at most 1000 timers per scan cycle.</li>
 *   <li>{@code gracePeriod=60s} — only recover timers whose {@code fire_at} is at least 60
 *       seconds in the past, to avoid interfering with timers that are in-flight.</li>
 * </ul>
 *
 * <p>The scan cadence itself is owned by the cron expression on
 * {@link WorkflowTimerRecoveryServiceImpl#reconcile()} (default {@code "*}{@code /30 * * * * *"});
 * operators tune via the {@code cron.jobs.workflow-timer-recovery.*} config subtree, not here.
 *
 * @param batchSize    the maximum number of recoverable timers to process per scan cycle
 * @param gracePeriod  how far in the past a timer's {@code fire_at} must be before it is
 *                     considered eligible for recovery; timers within the grace period are left
 *                     to the normal delayed-job polling path
 */
public record WorkflowTimerRecoveryConfig(int batchSize, Duration gracePeriod) {

    /**
     * Returns the default configuration for the timer recovery service.
     *
     * <p>Defaults: 1000-row batch, 60-second grace period.
     *
     * @return the default recovery configuration
     */
    public static WorkflowTimerRecoveryConfig defaults() {
        return new WorkflowTimerRecoveryConfig(1000, Duration.ofSeconds(60));
    }
}
