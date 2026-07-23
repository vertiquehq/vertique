// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.signal;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * Signal payload emitted by the shipping service after a shipment is created.
 *
 * <p>Implements {@link SignalDedupKeyed} so the workflow proxy can extract the dedup key without
 * requiring a method parameter annotation.
 *
 * @param orderId the order identifier that correlates this signal to the workflow instance
 * @param trackingNumber the carrier tracking number assigned to the shipment
 */
public record ShipmentCreated(String orderId, String trackingNumber) implements SignalDedupKeyed {

    /**
     * Returns the signal dedup key derived from the order id.
     *
     * @return {@code "ship-created-" + orderId}
     */
    @Override
    public String dedupKey() {
        return "ship-created-" + orderId;
    }
}
