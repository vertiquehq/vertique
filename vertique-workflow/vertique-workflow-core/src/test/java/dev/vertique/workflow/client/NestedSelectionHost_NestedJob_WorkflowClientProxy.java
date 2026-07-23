// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Hand-written stand-in for the generated companion of the nested
 * {@link NestedSelectionHost.NestedJob} contract. Its name uses the flattened {@code Outer_Inner}
 * form (matching {@link dev.vertique.core.util.GeneratedNames#companionFqn}), so the factory only
 * selects it if it derives the lookup name via {@code GeneratedNames.companionFqn} rather than
 * {@code Class.getName() + suffix} (which would yield {@code Outer$Inner...} and miss this class).
 */
public final class NestedSelectionHost_NestedJob_WorkflowClientProxy implements NestedSelectionHost.NestedJob {

    /**
     * Matches the generated proxy constructor signature.
     *
     * @param ops the workflow operations (unused in this stand-in)
     */
    public NestedSelectionHost_NestedJob_WorkflowClientProxy(WorkflowOperations ops) {
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
