// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Generated-proxy stand-in whose constructor throws, used to verify that
 * {@link WorkflowClientFactory} fails loudly with
 * {@link dev.vertique.workflow.exception.WorkflowClientProxyLinkageException} (rather than silently
 * falling back to the JDK proxy) when a generated proxy is present but cannot be instantiated.
 */
public final class BrokenSelectionWorkflow_WorkflowClientProxy implements BrokenSelectionWorkflow {

    /**
     * Always throws to simulate a broken generated class.
     *
     * @param ops the workflow operations (unused — constructor throws before any field is set)
     * @throws IllegalStateException unconditionally, to trigger the loud-fail path
     */
    public BrokenSelectionWorkflow_WorkflowClientProxy(WorkflowOperations ops) {
        throw new IllegalStateException("intentionally broken generated proxy");
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
