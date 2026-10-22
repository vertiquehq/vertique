// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.examples.workflow.order.signal.FraudScreened;
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
 * Typed contract interface for the ALL_REQUIRED fan-out order-fulfillment workflow.
 *
 * <p>This contract corresponds to {@link OrderFulfillmentFanOutDefinition} (definition id
 * {@code "order-fulfillment-fanout"}, version 1). It demonstrates parallel fan-out: inventory
 * reservation, payment authorization, and fraud screening run as independent branches; the
 * workflow advances to shipment only after all three complete.
 *
 * <p>The contract declares every signal the definition waits for, as the registry requires. The
 * integration test drives the branch signals directly via
 * {@link dev.vertique.workflow.ops.TransactionalWorkflowOperations}.
 */
@WorkflowContract("order-fulfillment-fanout")
public interface OrderFulfillmentFanOutWorkflow {
    /**
     * Delivers the fraud-screening result.
     *
     * @param id the workflow instance id
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("fraud.screened")
    Future<Void> fraudScreened(WorkflowInstanceId id, FraudScreened signal);

    /**
     * Delivers the inventory-reservation result.
     *
     * @param id the workflow instance id
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("inventory.reserved")
    Future<Void> inventoryReserved(WorkflowInstanceId id, InventoryReserved signal);

    /**
     * Delivers the payment-capture result.
     *
     * @param id the workflow instance id
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("payment.captured")
    Future<Void> paymentCaptured(WorkflowInstanceId id, PaymentCaptured signal);

    /**
     * Delivers the shipment-creation result.
     *
     * @param id the workflow instance id
     * @param signal the signal payload; must not be null
     * @return a {@link Future} that completes when the signal has been applied
     */
    @WorkflowSignal("shipment.created")
    Future<Void> shipmentCreated(WorkflowInstanceId id, ShipmentCreated signal);

    /**
     * Starts a new fan-out order-fulfillment workflow instance.
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
     * Queries the current state of a fan-out order-fulfillment workflow instance.
     *
     * @param id the workflow instance id to query
     * @return a {@link Future} that resolves to a {@link WorkflowView} containing the instance
     *     snapshot and recent history
     */
    @WorkflowQuery("status")
    Future<WorkflowView> status(WorkflowInstanceId id);
}
