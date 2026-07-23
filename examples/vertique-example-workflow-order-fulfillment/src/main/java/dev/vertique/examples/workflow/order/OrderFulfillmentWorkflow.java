// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.signal.InventoryReserved;
import dev.vertique.examples.workflow.order.signal.PaymentCaptured;
import dev.vertique.examples.workflow.order.signal.ShipmentCreated;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowQuery;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Typed contract interface for the order-fulfillment durable saga.
 *
 * <p>This interface is the canonical API surface for starting and advancing the saga. All methods
 * are non-blocking and return {@link Future}. Callers obtain a proxy via
 * {@link dev.vertique.workflow.client.WorkflowClientFactory#create(Class)}.
 *
 * <p>The saga follows these forward steps:
 * <ol>
 *   <li>Start: dispatch {@code inventory.reserve} (compensable).</li>
 *   <li>Wait for {@code inventory.reserved} signal.</li>
 *   <li>Dispatch {@code payment.authorize} (compensable).</li>
 *   <li>Wait for {@code payment.captured} signal.</li>
 *   <li>Dispatch {@code shipping.create-shipment}.</li>
 *   <li>Wait for {@code shipment.created} signal.</li>
 *   <li>Complete.</li>
 * </ol>
 *
 * <p>Compensation (LIFO): if payment authorization fails, the engine voids the authorization (if
 * attempted) then releases the inventory reservation.
 */
@WorkflowContract(definitionId = "order-fulfillment", definitionVersion = 1)
public interface OrderFulfillmentWorkflow {

    /**
     * Starts a new order-fulfillment saga.
     *
     * <p>The idempotency key is sourced from {@link PlaceOrder#idempotencyKey()}. Duplicate calls
     * with the same order id return the existing workflow instance id.
     *
     * @param cmd the place-order command; must not be null
     * @return a {@link Future} that resolves to the workflow instance id
     */
    @WorkflowStart
    Future<WorkflowInstanceId> start(PlaceOrder cmd);

    /**
     * Delivers the {@code inventory.reserved} signal to a waiting saga instance.
     *
     * <p>The dedup key is sourced from {@link InventoryReserved#dedupKey()}. Duplicate deliveries
     * with the same order id are idempotently ignored.
     *
     * @param id the workflow instance id to signal
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("inventory.reserved")
    Future<Void> inventoryReserved(WorkflowInstanceId id, InventoryReserved signal);

    /**
     * Delivers the {@code payment.captured} signal to a waiting saga instance.
     *
     * <p>The dedup key is sourced from {@link PaymentCaptured#dedupKey()}. Duplicate deliveries
     * with the same order id are idempotently ignored.
     *
     * @param id the workflow instance id to signal
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("payment.captured")
    Future<Void> paymentCaptured(WorkflowInstanceId id, PaymentCaptured signal);

    /**
     * Delivers the {@code shipment.created} signal to a waiting saga instance.
     *
     * <p>The dedup key is sourced from {@link ShipmentCreated#dedupKey()}. Duplicate deliveries
     * with the same order id are idempotently ignored.
     *
     * @param id the workflow instance id to signal
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("shipment.created")
    Future<Void> shipmentCreated(WorkflowInstanceId id, ShipmentCreated signal);

    /**
     * Queries the current state of an order-fulfillment saga instance.
     *
     * @param id the workflow instance id to query
     * @return a {@link Future} that resolves to a {@link WorkflowView} containing the instance
     *     snapshot and recent history
     */
    @WorkflowQuery("status")
    Future<WorkflowView> status(WorkflowInstanceId id);
}
