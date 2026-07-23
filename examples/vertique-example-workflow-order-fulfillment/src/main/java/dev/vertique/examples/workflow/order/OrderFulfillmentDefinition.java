// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.examples.workflow.order.command.AuthorizePayment;
import dev.vertique.examples.workflow.order.command.CreateShipment;
import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.command.ReleaseInventory;
import dev.vertique.examples.workflow.order.command.ReserveInventory;
import dev.vertique.examples.workflow.order.command.VoidAuthorization;
import dev.vertique.examples.workflow.order.signal.InventoryReserved;
import dev.vertique.examples.workflow.order.signal.PaymentCaptured;
import dev.vertique.examples.workflow.order.signal.ShipmentCreated;
import dev.vertique.examples.workflow.order.state.OrderState;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Workflow definition for the order-fulfillment durable saga.
 *
 * <p>The saga plan:
 * <pre>
 *   start
 *     → reserve-inventory (SERVICE, compensable: release-inventory)
 *     → wait-inventory (waits for inventory.reserved)
 *     → authorize-payment (SERVICE, compensable: void-authorization)
 *     → wait-payment (waits for payment.captured)
 *     → create-shipment (SERVICE)
 *     → wait-shipment (waits for shipment.created)
 *     → completed (CompleteNode)
 *
 *   Compensation nodes:
 *     release-inventory (compensates reserve-inventory)
 *     void-authorization (compensates authorize-payment)
 * </pre>
 *
 * <p>Compensation fires in LIFO order. If payment authorization fails after inventory is reserved,
 * the engine records a {@code release-inventory} intent before transitioning to COMPENSATED.
 * If the saga fails before any service dispatch completes, no compensation is needed.
 */
@Singleton
public final class OrderFulfillmentDefinition implements WorkflowDefinition<OrderState, OrderFulfillmentWorkflow> {

    /**
     * Creates a new {@code OrderFulfillmentDefinition}.
     */
    @Inject
    public OrderFulfillmentDefinition() {}

    @Override
    public Class<OrderFulfillmentWorkflow> contract() {
        return OrderFulfillmentWorkflow.class;
    }

    @Override
    public Class<OrderState> stateType() {
        return OrderState.class;
    }

    @Override
    public String definitionId() {
        return "order-fulfillment";
    }

    @Override
    public long definitionVersion() {
        return 1L;
    }

    @Override
    public void define(WorkflowBuilder<OrderState> wf) {
        wf
                // --- Init ---
                .init(PlaceOrder.class, OrderState::fromCommand)
                .initialStep("reserve-inventory")

                // --- Forward steps ---
                .dispatchWithCompensation(
                        "reserve-inventory",
                        "inventory.reserve",
                        state -> new ReserveInventory(state.orderId(), state.items()),
                        "release-inventory",
                        "wait-inventory")
                .waitFor(
                        "wait-inventory",
                        "inventory.reserved",
                        InventoryReserved.class,
                        (s, sig) -> s.withReservation(sig.reservationId()),
                        "authorize-payment")
                .dispatchWithCompensation(
                        "authorize-payment",
                        "payment.authorize",
                        state -> new AuthorizePayment(state.orderId(), state.customerId(), state.totalCents()),
                        "void-authorization",
                        "wait-payment")
                .waitFor(
                        "wait-payment",
                        "payment.captured",
                        PaymentCaptured.class,
                        (s, sig) -> s.withPayment(sig.chargeId()),
                        "create-shipment")
                .dispatch(
                        "create-shipment",
                        "shipping.create-shipment",
                        state -> new CreateShipment(state.orderId(), state.customerId(), state.items()),
                        "wait-shipment")
                .waitFor(
                        "wait-shipment",
                        "shipment.created",
                        ShipmentCreated.class,
                        (s, sig) -> s.withShipment(sig.trackingNumber()),
                        "completed")
                .complete("completed")

                // --- Compensation nodes ---
                .compensate(
                        "release-inventory",
                        "reserve-inventory",
                        "inventory.release",
                        state -> new ReleaseInventory(state.orderId(), state.reservationId()))
                .compensate(
                        "void-authorization",
                        "authorize-payment",
                        "payment.void-authorization",
                        state -> new VoidAuthorization(state.orderId(), state.authorizationId()));
    }
}
