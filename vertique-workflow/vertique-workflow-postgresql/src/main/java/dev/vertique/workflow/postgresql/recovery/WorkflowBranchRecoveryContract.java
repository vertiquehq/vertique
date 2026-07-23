// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for cluster-singleton workflow branch recovery (PRD-WF-002 §A.4.3,
 * FR-WF-PAR-039).
 *
 * <p>The single operation {@link #reconcile()} runs one branch-token recovery pass: due
 * {@code RETRY_SCHEDULED} branches are resumed and stale {@code RUNNING} branches are demoted.
 * It is scheduled by the framework's cron infrastructure as a {@code SINGLE_INSTANCE} job —
 * exactly one node in the cluster executes a given fire — and protected by
 * {@code OverlapPolicy.SKIP} so a slow pass does not pile up.
 *
 * <p>The {@code @ServiceOperation} stable id is required by the cron registrar (see
 * {@code CronJobRegistrar}); without it a cron job on this contract cannot resolve to a durable
 * service target.
 *
 * <p><b>Internal contract:</b> this interface exists to satisfy the cron registrar's
 * stable-target-id requirement. Fires are owned by the cron dispatcher on the leader node.
 * The {@code @ServiceContract} annotation deliberately exposes the operation on the in-process
 * Vert.x event bus so operators can trigger an out-of-cycle reconcile via a services dispatch
 * (e.g. for an admin diagnostic), but the operation has no auth boundary of its own — guard
 * any operator-facing entry point at the layer above the dispatch fabric. Routine application
 * code should not invoke {@link #reconcile()}.
 */
@ServiceContract(namespace = "workflow", value = "branch-recovery")
public interface WorkflowBranchRecoveryContract {

    /**
     * Runs one branch-recovery pass.
     *
     * <p>Per-branch failures are isolated inside
     * {@link dev.vertique.workflow.postgresql.engine.PgWorkflowBranchRecoveryService}; the outer
     * scan still propagates failure if the transaction cannot be opened or the underlying queries
     * fail (e.g. DB outage). The cron dispatcher then records the execution as failed, which is
     * the intended signal for operators.
     *
     * @return a future that completes when the pass finishes; may fail on full-scan errors
     */
    @ServiceOperation("reconcile")
    Future<Void> reconcile();
}
