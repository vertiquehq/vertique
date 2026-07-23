// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Cluster-singleton branch-recovery cron job for workflow fan-out/fan-in (PRD-WF-002 §A.4.3).
 *
 * <p>Contains {@link dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryContract}
 * + {@link dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryServiceImpl} (the
 * cluster-singleton {@code @CronJob} entry point) and
 * {@link dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryConfig} (its configuration
 * record). The reconcile logic itself lives in
 * {@code dev.vertique.workflow.postgresql.engine.PgWorkflowBranchRecoveryService}.
 */
package dev.vertique.workflow.postgresql.recovery;
