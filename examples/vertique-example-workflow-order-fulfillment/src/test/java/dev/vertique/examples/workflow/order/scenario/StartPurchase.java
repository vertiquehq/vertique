// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.contract.BusinessKeyed;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.types.WorkflowType;

/**
 * Start payload of the purchase-approval workflow.
 *
 * @param purchaseId identifies the start request
 * @param poNumber the purchase order number, used as the business key
 */
@WorkflowType("purchase-start")
public record StartPurchase(String purchaseId, String poNumber) implements IdempotencyKeyed, BusinessKeyed {

    @Override
    public String idempotencyKey() {
        return "purchase-" + purchaseId;
    }

    @Override
    public String businessKey() {
        return poNumber;
    }
}
