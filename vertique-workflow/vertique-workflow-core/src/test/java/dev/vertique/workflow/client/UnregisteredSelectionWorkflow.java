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
 * Workflow contract fixture that is structurally valid but is deliberately NOT registered in the
 * {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry} used by
 * {@link WorkflowClientFactorySelectionTest}.
 *
 * <p>Used to verify the "validation-runs-first" guarantee: because validation runs before the
 * generated-proxy lookup, {@code factory.create(...)} must throw
 * {@link dev.vertique.workflow.exception.WorkflowProxyContractException} even though no stand-in proxy
 * for this contract exists on the classpath. If selection ran first, the factory would reach the
 * JDK-proxy fallback and then attempt to pre-compile handlers — which would also fail — but via a
 * different code path. The validation-first guarantee means the thrown exception type is always
 * {@code WorkflowProxyContractException}, regardless of whether a generated proxy is present.
 *
 * <p>Targets definition id {@code "selection-saga-unregistered"} v1, which is never registered.
 */
@WorkflowContract(definitionId = "selection-saga-unregistered", definitionVersion = 1)
interface UnregisteredSelectionWorkflow {

    /**
     * Starts an unregistered-selection workflow instance.
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
