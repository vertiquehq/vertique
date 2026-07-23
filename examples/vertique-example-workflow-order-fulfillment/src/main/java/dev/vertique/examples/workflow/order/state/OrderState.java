// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.state;

import dev.vertique.examples.workflow.order.command.PlaceOrder;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Durable state of the order-fulfillment workflow instance.
 *
 * <p>Serialized to/from JSONB by the workflow engine. All fields are nullable except
 * {@code orderId}, {@code customerId}, {@code items}, and {@code totalCents}.
 *
 * @param orderId the client-assigned order identifier
 * @param customerId the customer who placed the order
 * @param items line items in the order
 * @param totalCents total order amount in cents
 * @param status current application-level status of the order
 * @param reservationId inventory reservation id; set after inventory is reserved
 * @param authorizationId payment authorization id; set after payment is authorized
 * @param trackingNumber shipment tracking number; set after shipment is created
 */
public record OrderState(
        String orderId,
        String customerId,
        List<PlaceOrder.OrderItem> items,
        long totalCents,
        OrderStatus status,
        @Nullable String reservationId,
        @Nullable String authorizationId,
        @Nullable String trackingNumber) {

    /**
     * Derives the initial {@link OrderState} from a {@link PlaceOrder} start command.
     *
     * @param cmd the start command
     * @return initial state with {@link OrderStatus#PENDING} and no reservation, payment, or
     *     shipment ids
     */
    public static OrderState fromCommand(PlaceOrder cmd) {
        return new OrderState(
                cmd.orderId(), cmd.customerId(), cmd.items(), cmd.totalCents(), OrderStatus.PENDING, null, null, null);
    }

    /**
     * Transitions the state after inventory is reserved.
     *
     * @param reservationId the reservation id returned by the inventory service
     * @return updated state with {@link OrderStatus#INVENTORY_RESERVED}
     */
    public OrderState withReservation(String reservationId) {
        return new OrderState(
                orderId, customerId, items, totalCents, OrderStatus.INVENTORY_RESERVED, reservationId, null, null);
    }

    /**
     * Transitions the state after payment is authorized.
     *
     * @param authorizationId the authorization id returned by the payment service
     * @return updated state with {@link OrderStatus#PAYMENT_AUTHORIZED}
     */
    public OrderState withPayment(String authorizationId) {
        return new OrderState(
                orderId,
                customerId,
                items,
                totalCents,
                OrderStatus.PAYMENT_AUTHORIZED,
                reservationId,
                authorizationId,
                null);
    }

    /**
     * Transitions the state after a shipment is created.
     *
     * @param trackingNumber the tracking number returned by the shipping service
     * @return updated state with {@link OrderStatus#SHIPPED}
     */
    public OrderState withShipment(String trackingNumber) {
        return new OrderState(
                orderId,
                customerId,
                items,
                totalCents,
                OrderStatus.SHIPPED,
                reservationId,
                authorizationId,
                trackingNumber);
    }
}
