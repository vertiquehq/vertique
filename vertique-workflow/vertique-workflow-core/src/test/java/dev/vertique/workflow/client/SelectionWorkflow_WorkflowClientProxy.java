// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Hand-written stand-in for the generated {@code SelectionWorkflow_WorkflowClientProxy}, used to
 * verify that {@link WorkflowClientFactory} selects a present generated proxy by name. Mirrors the
 * generated constructor signature {@code (WorkflowOperations ops)}.
 */
public final class SelectionWorkflow_WorkflowClientProxy implements SelectionWorkflow {

    /**
     * Matches the generated proxy constructor signature.
     *
     * @param ops the workflow operations (unused in this stand-in)
     */
    public SelectionWorkflow_WorkflowClientProxy(WorkflowOperations ops) {
        // No-op stand-in.
    }

    @Override
    public Future<WorkflowInstanceId> start(SelectionStartPayload payload) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> confirm(WorkflowInstanceId instanceId, SelectionSignalPayload payload) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> ship(WorkflowInstanceId instanceId, SelectionSignalPayload payload) {
        return Future.succeededFuture();
    }

    @Override
    public Future<WorkflowView> getView(WorkflowInstanceId instanceId) {
        return Future.succeededFuture();
    }
}
