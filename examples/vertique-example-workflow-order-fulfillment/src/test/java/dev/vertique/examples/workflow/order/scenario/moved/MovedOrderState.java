// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario.moved;

import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.state.OrderStatus;
import dev.vertique.workflow.types.WorkflowType;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * The order state after its class moved to another package: the same logical name and the same fields as the shipped
 * order state, under another binary name.
 */
@WorkflowType("order")
public record MovedOrderState(
        String orderId,
        String customerId,
        List<PlaceOrder.OrderItem> items,
        long totalCents,
        OrderStatus status,
        @Nullable String reservationId,
        @Nullable String authorizationId,
        @Nullable String trackingNumber) {

    /**
     * Derives the initial state from a start command.
     *
     * @param cmd the start command
     * @return the initial state
     */
    public static MovedOrderState fromCommand(PlaceOrder cmd) {
        return new MovedOrderState(
                cmd.orderId(), cmd.customerId(), cmd.items(), cmd.totalCents(), OrderStatus.PENDING, null, null, null);
    }
}
