// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.signal;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * Signal payload emitted by the inventory service after a reservation succeeds.
 *
 * <p>Implements {@link SignalDedupKeyed} so the workflow proxy can extract the dedup key without
 * requiring a method parameter annotation.
 *
 * @param orderId the order identifier that correlates this signal to the workflow instance
 * @param reservationId the identifier assigned to the inventory reservation
 */
public record InventoryReserved(String orderId, String reservationId) implements SignalDedupKeyed {

    /**
     * Returns the signal dedup key derived from the order id.
     *
     * @return {@code "inv-reserved-" + orderId}
     */
    @Override
    public String dedupKey() {
        return "inv-reserved-" + orderId;
    }
}
