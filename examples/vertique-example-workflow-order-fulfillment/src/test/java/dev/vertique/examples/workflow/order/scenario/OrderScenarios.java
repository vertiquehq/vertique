// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;

import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.signal.InventoryReserved;
import dev.vertique.examples.workflow.order.signal.PaymentCaptured;
import dev.vertique.examples.workflow.order.signal.ShipmentCreated;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.List;

/** Orders to start and the three signals that carry an order-fulfillment instance from its first wait to the end. */
final class OrderScenarios {

    private OrderScenarios() {}

    static PlaceOrder order(String orderId) {
        return new PlaceOrder(orderId, "cust-" + orderId, List.of(new PlaceOrder.OrderItem("SKU-1", 1, 500L)), 500L);
    }

    /** Delivers the reservation, payment and shipment signals in the order the saga waits for them. */
    static void finish(OrderFulfillmentWorkflow client, WorkflowInstanceId instance, String orderId) {
        await(client.inventoryReserved(instance, new InventoryReserved(orderId, "res-" + orderId)));
        await(client.paymentCaptured(instance, new PaymentCaptured(orderId, "chg-" + orderId)));
        await(client.shipmentCreated(instance, new ShipmentCreated(orderId, "trk-" + orderId)));
    }
}
