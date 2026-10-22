// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.examples.workflow.order.OrderFulfillmentDefinition;
import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.state.OrderState;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;

/**
 * Version 2 of the order-fulfillment saga: the same steps as version 1 under the next version number. It becomes the
 * current version when it is first published, unless it opts out of activation on deploy.
 */
public final class OrderFulfillmentDefinitionV2 implements WorkflowDefinition<OrderState, OrderFulfillmentWorkflow> {

    private final boolean activateOnDeploy;

    /** Creates version 2 with the default activation on deploy. */
    public OrderFulfillmentDefinitionV2() {
        this(true);
    }

    /**
     * Creates version 2.
     *
     * @param activateOnDeploy whether version 2 becomes the current version when it is first published
     */
    public OrderFulfillmentDefinitionV2(boolean activateOnDeploy) {
        this.activateOnDeploy = activateOnDeploy;
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
        return 2L;
    }

    @Override
    public boolean activateOnDeploy() {
        return activateOnDeploy;
    }

    @Override
    public void define(WorkflowBuilder<OrderState> wf) {
        new OrderFulfillmentDefinition().define(wf);
    }
}
