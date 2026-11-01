// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.workflow;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;

/**
 * Typed contract of the load workflow.
 *
 * <p>The ingress of a node starts instances through a client built from {@link LoadDefinition}. The signal is
 * declared because the registry requires a definition's contract to name every signal the definition waits for; the
 * dispatch handler records it through the engine's relayed-signal form and never through this method.
 */
@WorkflowContract("load")
public interface LoadWorkflow {

    /**
     * Starts one load workflow instance.
     *
     * @param cmd the start payload; its key is the idempotency key
     * @return the id of the created instance, or of the existing one when the key was used before
     */
    @WorkflowStart
    Future<WorkflowInstanceId> start(LoadStart cmd);

    /**
     * Delivers the callback signal to a waiting instance.
     *
     * @param id the instance id
     * @param signal the signal payload; its dedup key is derived from the instance key
     * @return a future that completes when the signal is recorded
     */
    @WorkflowSignal(LoadCallback.SIGNAL_NAME)
    Future<Void> callback(WorkflowInstanceId id, LoadCallback signal);
}
