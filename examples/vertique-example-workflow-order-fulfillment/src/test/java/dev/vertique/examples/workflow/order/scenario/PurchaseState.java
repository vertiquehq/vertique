// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.types.WorkflowType;

/**
 * State of the purchase-approval workflow.
 *
 * @param purchaseId the request that started the instance
 * @param note the last step that touched the state
 */
@WorkflowType("purchase")
public record PurchaseState(String purchaseId, String note) {

    static PurchaseState from(StartPurchase start) {
        return new PurchaseState(start.purchaseId(), "started");
    }

    PurchaseState withNote(String note) {
        return new PurchaseState(purchaseId, note);
    }
}
