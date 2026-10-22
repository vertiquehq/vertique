// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.state.OrderState;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;

/**
 * An order-fulfillment definition whose only step is a review task: the order waits for an approval decision and then
 * completes. It keeps the state class {@link OrderState}, which carries the logical name {@code order}.
 */
public final class ReviewedOrderDefinition implements WorkflowDefinition<OrderState, OrderFulfillmentWorkflow> {

    private final long version;

    /**
     * Creates the definition.
     *
     * @param version the definition version
     */
    public ReviewedOrderDefinition(long version) {
        this.version = version;
    }

    @Override
    public Class<OrderFulfillmentWorkflow> contract() {
        return OrderFulfillmentWorkflow.class;
    }

    @Override
    public Class<OrderState> stateType() {
        return OrderState.class;
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
    public void define(WorkflowBuilder<OrderState> wf) {
        wf.init(PlaceOrder.class, OrderState::fromCommand)
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
