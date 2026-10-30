// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.time.Duration;

/**
 * The code form of {@code purchase-routing-acme.workflow.yaml}: a rush purchase skips the finance approval, and every
 * purchase is then held for a second and then waits for the audit signal.
 */
public final class PurchaseRoutingDefinition implements WorkflowDefinition<PurchaseState, PurchaseApprovalWorkflow> {

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
        return "purchase-routing";
    }

    @Override
    public long definitionVersion() {
        return 1L;
    }

    @Override
    public void define(WorkflowBuilder<PurchaseState> wf) {
        wf.init(StartPurchase.class, PurchaseState::from)
                .initialStep("route-purchase")
                .decide(
                        "route-purchase",
                        s -> s.purchaseId().startsWith("rush-") ? "hold-shipment" : "finance-approval")
                .task("finance-approval")
                .assignToRole("finance")
                .decision("approve", FinanceDecision.class)
                .onDecision((s, decision) -> s)
                .toStep("hold-shipment")
                .build()
                .timer("hold-shipment", Duration.ofSeconds(1))
                .toStep("await-audit")
                .waitForSignal("await-audit", "po.audited", PoAudited.class)
                .onSignal((s, audit) -> s.withNote("audited by " + audit.by()))
                .toStepOnSignal("done")
                .build()
                .complete("done");
    }
}
