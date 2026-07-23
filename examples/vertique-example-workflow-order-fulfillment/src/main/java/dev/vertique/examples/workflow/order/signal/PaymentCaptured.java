// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.signal;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * Signal payload emitted by the payment service after a charge is captured.
 *
 * <p>Implements {@link SignalDedupKeyed} so the workflow proxy can extract the dedup key without
 * requiring a method parameter annotation.
 *
 * @param orderId the order identifier that correlates this signal to the workflow instance
 * @param chargeId the identifier assigned to the captured payment charge
 */
public record PaymentCaptured(String orderId, String chargeId) implements SignalDedupKeyed {

    /**
     * Returns the signal dedup key derived from the order id.
     *
     * @return {@code "pay-captured-" + orderId}
     */
    @Override
    public String dedupKey() {
        return "pay-captured-" + orderId;
    }
}
