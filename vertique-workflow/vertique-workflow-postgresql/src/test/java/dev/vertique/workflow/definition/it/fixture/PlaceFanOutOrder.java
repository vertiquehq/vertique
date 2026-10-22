// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.IdempotencyKeyed;

/**
 * Start payload for the document-defined fan-out order-fulfillment integration test.
 *
 * <p>Used by {@link dev.vertique.workflow.definition.it.DocumentDefinitionFanOutIT} to start a
 * fan-out workflow instance. The {@link #orderId()} doubles as the idempotency key base.
 *
 * @param orderId the order identifier; used as the idempotency key base and stored in the
 *     initial workflow state
 */
public record PlaceFanOutOrder(String orderId) implements IdempotencyKeyed {

    /**
     * {@inheritDoc}
     *
     * @return {@code "fanout-doc-order-" + orderId}
     */
    @Override
    public String idempotencyKey() {
        return "fanout-doc-order-" + orderId;
    }
}
