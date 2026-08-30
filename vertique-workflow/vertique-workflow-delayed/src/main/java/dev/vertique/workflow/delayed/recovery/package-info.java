// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Orphan and dead-letter recovery for workflow timers.
 *
 * <p>Contains {@link dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryService}
 * (the reconcile logic), {@link dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryContract}
 * + {@link dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryCron} (the
 * cluster-singleton {@code @CronJob} entry point), and
 * {@link dev.vertique.workflow.delayed.recovery.WorkflowTimerRecoveryConfig} (its configuration
 * record).
 */
package dev.vertique.workflow.delayed.recovery;
