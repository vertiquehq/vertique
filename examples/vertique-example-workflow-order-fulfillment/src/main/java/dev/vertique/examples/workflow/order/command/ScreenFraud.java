// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

import java.util.List;

/**
 * Command dispatched to the fraud-screening service to evaluate an order for fraud risk.
 *
 * <p>This command is dispatched in parallel with {@link ReserveInventory} and
 * {@link AuthorizePayment} as part of the ALL_REQUIRED fan-out in
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition}.
 *
 * @param orderId the order identifier used to correlate with the {@code fraud.screened} signal
 * @param customerId the customer placing the order
 * @param amountCents the total order amount in cents
 * @param items the line items whose fraud risk is being assessed
 */
public record ScreenFraud(String orderId, String customerId, long amountCents, List<PlaceOrder.OrderItem> items) {}
