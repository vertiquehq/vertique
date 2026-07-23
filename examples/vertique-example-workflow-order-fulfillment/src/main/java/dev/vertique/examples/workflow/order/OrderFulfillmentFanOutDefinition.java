// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.examples.workflow.order.command.AuthorizePayment;
import dev.vertique.examples.workflow.order.command.CreateShipment;
import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.command.ReserveInventory;
import dev.vertique.examples.workflow.order.command.ScreenFraud;
import dev.vertique.examples.workflow.order.signal.FraudScreened;
import dev.vertique.examples.workflow.order.signal.InventoryReserved;
import dev.vertique.examples.workflow.order.signal.PaymentCaptured;
import dev.vertique.examples.workflow.order.signal.ShipmentCreated;
import dev.vertique.examples.workflow.order.state.FanOutOrderState;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * ALL_REQUIRED fan-out order-fulfillment workflow definition (PRD-WF-002 §A.5.1).
 *
 * <p>Demonstrates parallel fan-out/fan-in: inventory reservation, payment authorization, and
 * fraud screening run as independent durable branches. Each branch dispatches a service call,
 * waits for a confirmation signal, then completes. The join advances to shipment only after all
 * three branches complete.
 *
 * <p>Workflow shape:
 * <pre>
 *   start
 *     → fork "reserve-order"
 *         branch "inventory"  → dispatch inventory.reserve → wait inventory.reserved → complete
 *         branch "payment"    → dispatch payment.authorize → wait payment.captured   → complete
 *         branch "fraud"      → dispatch fraud.screen      → wait fraud.screened     → complete
 *     → join "all-reservations" (ALL_REQUIRED)
 *     → dispatch "ship-order" (shipping.create-shipment)
 *     → wait  "wait-shipment" (shipment.created)
 *     → complete "done"
 * </pre>
 *
 * <p>Branch signals are routed via the branch-aware signal path:
 * {@code WorkflowSignalRequest.branch(id, signalName, payload, dedupKey, "reserve-order", branchId)}.
 *
 * <p>Branch state notes: each branch's stateUpdater is called with the parent instance's
 * {@code state_json}. The three updaters accumulate reservation id, charge id, and screening ref
 * into the shared {@link FanOutOrderState}. Because branches execute concurrently, later
 * stateUpdater calls may clobber earlier ones if they write overlapping fields — each updater here
 * writes to a distinct field ({@code reservationId}, {@code chargeId}, {@code screeningRef}), so
 * there is no collision. See PRD-WF-002 §NOT-IMPLEMENTED: branch state propagation for details.
 */
@Singleton
public final class OrderFulfillmentFanOutDefinition
        implements WorkflowDefinition<FanOutOrderState, OrderFulfillmentFanOutWorkflow> {

    /**
     * Creates a new {@code OrderFulfillmentFanOutDefinition}.
     */
    @Inject
    public OrderFulfillmentFanOutDefinition() {}

    @Override
    public Class<OrderFulfillmentFanOutWorkflow> contract() {
        return OrderFulfillmentFanOutWorkflow.class;
    }

    @Override
    public Class<FanOutOrderState> stateType() {
        return FanOutOrderState.class;
    }

    @Override
    public String definitionId() {
        return "order-fulfillment-fanout";
    }

    @Override
    public long definitionVersion() {
        return 1L;
    }

    @Override
    public void define(WorkflowBuilder<FanOutOrderState> wf) {
        wf
                // --- Init ---
                .init(PlaceOrder.class, FanOutOrderState::fromCommand)
                .initialStep("reserve-order")

                // --- Fork: all three branches run in parallel ---
                .fork("reserve-order")
                .branch("inventory", "dispatch-inventory")
                .branch("payment", "dispatch-payment")
                .branch("fraud", "dispatch-fraud")
                .join("all-reservations")

                // --- Branch: inventory reservation ---
                .dispatch(
                        "dispatch-inventory",
                        "inventory.reserve",
                        state -> new ReserveInventory(state.orderId(), state.items()),
                        "wait-inventory-reserved")
                .waitForSignal("wait-inventory-reserved", "inventory.reserved", InventoryReserved.class)
                .onSignal((s, sig) -> s.withReservation(sig.reservationId()))
                .toStepOnSignal("inventory-done")
                .build()
                .complete("inventory-done")

                // --- Branch: payment authorization ---
                .dispatch(
                        "dispatch-payment",
                        "payment.authorize",
                        state -> new AuthorizePayment(state.orderId(), state.customerId(), state.totalCents()),
                        "wait-payment-captured")
                .waitForSignal("wait-payment-captured", "payment.captured", PaymentCaptured.class)
                .onSignal((s, sig) -> s.withPayment(sig.chargeId()))
                .toStepOnSignal("payment-done")
                .build()
                .complete("payment-done")

                // --- Branch: fraud screening ---
                .dispatch(
                        "dispatch-fraud",
                        "fraud.screen",
                        state ->
                                new ScreenFraud(state.orderId(), state.customerId(), state.totalCents(), state.items()),
                        "wait-fraud-screened")
                .waitForSignal("wait-fraud-screened", "fraud.screened", FraudScreened.class)
                .onSignal((s, sig) -> s.withFraudScreening(sig.screeningRef()))
                .toStepOnSignal("fraud-done")
                .build()
                .complete("fraud-done")

                // --- Join: all branches must complete ---
                .join("all-reservations")
                .allRequired(FanOutOrderState::mergeBranchResults)
                .toStep("ship-order")
                .onFailure("order-failed")
                .endJoin()

                // --- Post-join: create shipment ---
                .dispatch(
                        "ship-order",
                        "shipping.create-shipment",
                        state -> new CreateShipment(state.orderId(), state.customerId(), state.items()),
                        "wait-shipment")
                .waitForSignal("wait-shipment", "shipment.created", ShipmentCreated.class)
                .onSignal((s, sig) -> s.withShipment(sig.trackingNumber()))
                .toStepOnSignal("done")
                .build()
                .complete("done")

                // --- Failure terminal (join failure route) ---
                .fail(
                        "order-failed",
                        "PARALLEL_BRANCH_FAILURE",
                        state -> "Order fulfillment failed during parallel pre-shipment verification");
    }
}
