// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.IdempotencyKeyed;

/**
 * Start payload for the document-defined order-fulfillment integration tests.
 *
 * @param orderId the order identifier used as the idempotency key
 */
public record PlaceOrderDoc(String orderId) implements IdempotencyKeyed {

    /**
     * {@inheritDoc}
     *
     * @return {@code "doc-order-" + orderId}
     */
    @Override
    public String idempotencyKey() {
        return "doc-order-" + orderId;
    }
}
