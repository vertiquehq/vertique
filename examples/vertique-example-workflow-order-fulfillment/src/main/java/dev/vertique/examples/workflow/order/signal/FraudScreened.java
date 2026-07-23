// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.signal;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * Signal payload emitted by the fraud-screening service after an order has been evaluated.
 *
 * <p>Delivered to the {@code fraud} branch of the ALL_REQUIRED fan-out in
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition} via a
 * branch-targeted signal ({@code forkStepId="reserve-order", branchId="fraud"}).
 *
 * @param orderId the order identifier that correlates this signal to the workflow instance
 * @param screeningRef the identifier assigned to the fraud-screening decision record
 */
public record FraudScreened(String orderId, String screeningRef) implements SignalDedupKeyed {

    /**
     * Returns the signal dedup key derived from the order id.
     *
     * @return {@code "fraud-screened-" + orderId}
     */
    @Override
    public String dedupKey() {
        return "fraud-screened-" + orderId;
    }
}
