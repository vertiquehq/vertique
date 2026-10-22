// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.scenario.moved.MovedOrderState;
import dev.vertique.examples.workflow.order.state.OrderState;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import java.util.List;

/** The hand-written type names the scenarios list, by the logical names the classes carry. */
final class ScenarioCatalogs {

    private ScenarioCatalogs() {}

    /** The purchase-approval contract and its state, start, decision and signal payload types. */
    static List<WorkflowTypeCatalogEntry> purchase() {
        return List.of(
                new WorkflowTypeCatalogEntry(Kind.CONTRACT, "purchase-approval", PurchaseApprovalWorkflow.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "purchase", PurchaseState.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "purchase-start", StartPurchase.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "finance-decision", FinanceDecision.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "po-approved", PoApproved.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "po-audited", PoAudited.class));
    }

    /** The order contract, the shipped order state under its logical name, and the approval payload by binary name. */
    static List<WorkflowTypeCatalogEntry> annotatedOrder() {
        return List.of(
                new WorkflowTypeCatalogEntry(Kind.CONTRACT, "order-fulfillment", OrderFulfillmentWorkflow.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "order", OrderState.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, OrderApproval.class.getName(), OrderApproval.class));
    }

    /** The order contract, the moved order state under the same logical name, and the approval payload. */
    static List<WorkflowTypeCatalogEntry> movedOrder() {
        return List.of(
                new WorkflowTypeCatalogEntry(Kind.CONTRACT, "order-fulfillment", OrderFulfillmentWorkflow.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, "order", MovedOrderState.class),
                new WorkflowTypeCatalogEntry(Kind.TYPE, OrderApproval.class.getName(), OrderApproval.class));
    }
}
