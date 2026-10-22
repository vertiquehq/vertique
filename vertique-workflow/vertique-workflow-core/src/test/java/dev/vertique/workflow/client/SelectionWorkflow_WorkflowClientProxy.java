// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Hand-written stand-in for the generated {@code SelectionWorkflow_WorkflowClientProxy}, used to
 * verify that {@link WorkflowClientFactory} selects a present generated proxy by name and that it
 * behaves like the reflective proxy. Mirrors the generated constructor signature {@code
 * (WorkflowOperations ops, WorkflowClientBinding binding)} and the generated bodies: the start
 * command is built only through {@link WorkflowClientBinding#startCommand}.
 */
public final class SelectionWorkflow_WorkflowClientProxy implements SelectionWorkflow {

    private final WorkflowOperations ops;
    private final WorkflowClientBinding binding;

    /**
     * Matches the generated proxy constructor signature.
     *
     * @param ops the workflow operations to delegate contract methods to
     * @param binding the binding that supplies the definition id, version and scopes of started instances
     */
    public SelectionWorkflow_WorkflowClientProxy(WorkflowOperations ops, WorkflowClientBinding binding) {
        this.ops = ops;
        this.binding = binding;
    }

    @Override
    public Future<WorkflowInstanceId> start(SelectionStartPayload payload) {
        return this.ops.start(this.binding.startCommand(payload, payload.idempotencyKey(), null, null));
    }

    @Override
    public Future<Void> confirm(WorkflowInstanceId instanceId, SelectionSignalPayload payload) {
        return this.ops.signal(instanceId, "order.confirmed", payload, payload.dedupKey());
    }

    @Override
    public Future<Void> ship(WorkflowInstanceId instanceId, SelectionSignalPayload payload) {
        return this.ops.signal(instanceId, "order.shipped", payload, payload.dedupKey());
    }

    @Override
    public Future<WorkflowView> getView(WorkflowInstanceId instanceId) {
        return this.ops.query(instanceId);
    }
}
