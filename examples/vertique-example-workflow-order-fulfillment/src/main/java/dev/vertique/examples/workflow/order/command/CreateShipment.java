// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

import java.util.List;

/**
 * Command dispatched to the shipping service to create a shipment for a fulfilled order.
 *
 * @param orderId the order identifier used to correlate with the {@code shipment.created} signal
 * @param customerId the customer to ship to
 * @param items the line items to include in the shipment
 */
public record CreateShipment(String orderId, String customerId, List<PlaceOrder.OrderItem> items) {}
