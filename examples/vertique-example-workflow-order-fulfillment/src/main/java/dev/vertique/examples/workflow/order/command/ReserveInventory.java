// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

import java.util.List;

/**
 * Command dispatched to the inventory service to reserve stock for an order.
 *
 * @param orderId the order identifier used to correlate with the {@code inventory.reserved} signal
 * @param items the line items whose stock must be reserved
 */
public record ReserveInventory(String orderId, List<PlaceOrder.OrderItem> items) {}
