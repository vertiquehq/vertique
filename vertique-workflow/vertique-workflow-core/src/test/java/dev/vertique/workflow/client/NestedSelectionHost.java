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
 * Host for a nested {@code @WorkflowContract} fixture exercising flattened companion-name
 * selection.
 *
 * <p>The nested {@link NestedJob} contract's generated companion is named
 * {@code NestedSelectionHost_NestedJob_WorkflowClientProxy} (using {@code _} as the separator),
 * which matches what {@link dev.vertique.core.util.GeneratedNames#companionFqn} produces. A
 * regression to {@code Class.getName() + suffix} would yield
 * {@code NestedSelectionHost$NestedJob_WorkflowClientProxy} and would miss the stand-in, silently
 * falling back to a JDK proxy instead.
 */
interface NestedSelectionHost {

    /**
     * Nested contract whose generated companion flattens to
     * {@code NestedSelectionHost_NestedJob_WorkflowClientProxy}.
     *
     * <p>Targets the {@code selection-saga-nested} definition (v1) registered in
     * {@link WorkflowClientFactorySelectionTest}.
     */
    @WorkflowContract(definitionId = "selection-saga-nested", definitionVersion = 1)
    interface NestedJob {

        /**
         * Starts a nested-selection workflow instance.
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
}
