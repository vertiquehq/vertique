// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;

/**
 * The default scope's purchase approval: after the start it waits for the {@code po.approved} signal and completes.
 */
public final class PurchaseApprovalDefinition implements WorkflowDefinition<PurchaseState, PurchaseApprovalWorkflow> {

    @Override
    public Class<PurchaseApprovalWorkflow> contract() {
        return PurchaseApprovalWorkflow.class;
    }

    @Override
    public Class<PurchaseState> stateType() {
        return PurchaseState.class;
    }

    @Override
    public String definitionId() {
        return "purchase-approval";
    }

    @Override
    public long definitionVersion() {
        return 1L;
    }

    @Override
    public void define(WorkflowBuilder<PurchaseState> wf) {
        wf.init(StartPurchase.class, PurchaseState::from)
                .initialStep("await-approval")
                .waitFor(
                        "await-approval",
                        "po.approved",
                        PoApproved.class,
                        (state, approval) -> state.withNote("approved by " + approval.by()),
                        "done")
                .complete("done");
    }
}
