// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowQuery;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Workflow contract fixture with NO generated proxy companion on the test classpath, used to
 * verify that {@link WorkflowClientFactory} falls back to the JDK dynamic proxy when the companion
 * class is absent ({@code ClassNotFoundException}).
 *
 * <p>Targets the {@code selection-saga-fallback} definition (v1).
 */
@WorkflowContract(definitionId = "selection-saga-fallback", definitionVersion = 1)
interface FallbackSelectionWorkflow {

    /**
     * Starts a fallback-selection workflow instance.
     *
     * @param payload the start payload; must not be null
     * @return the new workflow instance id
     */
    @WorkflowStart
    Future<WorkflowInstanceId> start(SelectionStartPayload payload);

    /**
     * Signals that the order was confirmed.
     *
     * @param instanceId the workflow instance to signal; must not be null
     * @param payload the signal payload; must not be null
     * @return a future that completes when the signal is accepted
     */
    @WorkflowSignal("order.confirmed")
    Future<Void> confirm(WorkflowInstanceId instanceId, SelectionSignalPayload payload);

    /**
     * Signals that the order was shipped.
     *
     * @param instanceId the workflow instance to signal; must not be null
     * @param payload the signal payload; must not be null
     * @return a future that completes when the signal is accepted
     */
    @WorkflowSignal("order.shipped")
    Future<Void> ship(WorkflowInstanceId instanceId, SelectionSignalPayload payload);

    /**
     * Queries the current view of a workflow instance.
     *
     * @param instanceId the workflow instance to query; must not be null
     * @return the workflow view
     */
    @WorkflowQuery("view")
    Future<WorkflowView> getView(WorkflowInstanceId instanceId);
}
