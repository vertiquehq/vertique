// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recovery;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for cluster-singleton workflow timer recovery.
 *
 * <p>The single operation {@link #reconcile()} runs one orphan / dead-letter recovery pass. It is
 * scheduled by the framework's cron infrastructure as a {@code SINGLE_INSTANCE} job — exactly one
 * node in the cluster executes a given fire — and protected by {@code OverlapPolicy.SKIP} so a
 * slow pass does not pile up.
 *
 * <p>The {@code @ServiceOperation} stable id is required by the cron registrar (see
 * {@code CronJobRegistrar}); without it a cron job on this contract cannot resolve to a durable
 * service target.
 *
 * <p><b>Internal contract:</b> this interface exists to satisfy the cron registrar's
 * stable-target-id requirement. Applications should not invoke {@link #reconcile()} directly
 * from their own code — fires are owned by the cron dispatcher on the leader node.
 */
@ServiceContract(namespace = "workflow", value = "timer-recovery")
public interface WorkflowTimerRecoveryContract {

    /**
     * Runs one orphan / dead-letter recovery pass.
     *
     * <p>Per-row failures are isolated inside the implementation. The outer scan still propagates
     * failure if the transaction cannot be opened or {@code findRecoverableScheduled} fails (e.g.
     * DB outage); the cron dispatcher will then record the execution as failed, which is the
     * intended signal for operators.
     *
     * @return a future that completes when the pass finishes; may fail on full-scan errors
     */
    @ServiceOperation("reconcile")
    Future<Void> reconcile();
}
