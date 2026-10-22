// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario.moved;

import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.scenario.OrderApproval;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;

/**
 * The review-task order definition over the moved state class: the same steps, definition id and logical state name as
 * the definition over the shipped state class.
 */
public final class MovedReviewedOrderDefinition
        implements WorkflowDefinition<MovedOrderState, OrderFulfillmentWorkflow> {

    private final long version;

    /**
     * Creates the definition.
     *
     * @param version the definition version
     */
    public MovedReviewedOrderDefinition(long version) {
        this.version = version;
    }

    @Override
    public Class<OrderFulfillmentWorkflow> contract() {
        return OrderFulfillmentWorkflow.class;
    }

    @Override
    public Class<MovedOrderState> stateType() {
        return MovedOrderState.class;
    }

    @Override
    public String definitionId() {
        return "order-fulfillment";
    }

    @Override
    public long definitionVersion() {
        return version;
    }

    @Override
    public void define(WorkflowBuilder<MovedOrderState> wf) {
        wf.init(PlaceOrder.class, MovedOrderState::fromCommand)
                .initialStep("review")
                .task("review")
                .assignToRole("reviewers")
                .decision("approve", OrderApproval.class)
                .onDecision((state, approval) -> state)
                .toStep("done")
                .build()
                .complete("done");
    }
}
