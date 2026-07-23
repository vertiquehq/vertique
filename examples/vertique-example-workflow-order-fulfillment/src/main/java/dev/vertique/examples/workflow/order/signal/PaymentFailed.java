// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.signal;

import dev.vertique.workflow.contract.SignalDedupKeyed;

/**
 * Signal payload emitted by the payment service when authorization fails.
 *
 * <p>This signal drives the workflow engine to a {@code FailNode} so that LIFO compensation of
 * completed compensable steps (e.g., inventory reservation) is triggered.
 *
 * <p>Implements {@link SignalDedupKeyed} so the workflow proxy can extract the dedup key without
 * requiring a method parameter annotation.
 *
 * @param orderId the order identifier that correlates this signal to the workflow instance
 * @param reason human-readable failure reason
 */
public record PaymentFailed(String orderId, String reason) implements SignalDedupKeyed {

    /**
     * Returns the signal dedup key derived from the order id.
     *
     * @return {@code "pay-failed-" + orderId}
     */
    @Override
    public String dedupKey() {
        return "pay-failed-" + orderId;
    }
}
