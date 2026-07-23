// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

import dev.vertique.workflow.contract.IdempotencyKeyed;
import java.util.List;

/**
 * Start command for the order-fulfillment saga.
 *
 * <p>Implements {@link IdempotencyKeyed} so the workflow proxy can extract the idempotency key
 * without requiring a parameter annotation.
 *
 * @param orderId the client-assigned order identifier; used as the idempotency key
 * @param customerId the customer placing the order
 * @param items line items in the order
 * @param totalCents total order amount in cents
 */
public record PlaceOrder(String orderId, String customerId, List<OrderItem> items, long totalCents)
        implements IdempotencyKeyed {

    /**
     * Returns the idempotency key derived from the order id.
     *
     * @return {@code "order-" + orderId}
     */
    @Override
    public String idempotencyKey() {
        return "order-" + orderId;
    }

    /**
     * A single line item in the order.
     *
     * @param sku the product SKU
     * @param quantity the quantity ordered
     * @param unitPriceCents the per-unit price in cents
     */
    public record OrderItem(String sku, int quantity, long unitPriceCents) {}
}
