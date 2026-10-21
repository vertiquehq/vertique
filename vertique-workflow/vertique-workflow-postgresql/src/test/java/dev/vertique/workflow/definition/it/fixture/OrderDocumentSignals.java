// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;

/**
 * The signals the packaged {@code order-fulfillment} document waits for.
 *
 * <p>A definition that waits for a signal must declare it on its contract, so each contract that the
 * integration tests name in a copy of that document extends this interface.
 */
public interface OrderDocumentSignals {

    /**
     * Delivers the {@code inventory.reserved} signal.
     *
     * @param id the instance to signal
     * @param payload the signal payload
     * @param dedupKey the deduplication key
     * @return a future that completes when the signal is applied
     */
    @WorkflowSignal("inventory.reserved")
    Future<Void> inventoryReserved(
            WorkflowInstanceId id, InventoryReservedDoc payload, @SignalDedupKey String dedupKey);

    /**
     * Delivers the {@code payment.captured} signal.
     *
     * @param id the instance to signal
     * @param payload the signal payload
     * @param dedupKey the deduplication key
     * @return a future that completes when the signal is applied
     */
    @WorkflowSignal("payment.captured")
    Future<Void> paymentCaptured(WorkflowInstanceId id, PaymentCapturedDoc payload, @SignalDedupKey String dedupKey);

    /**
     * Delivers the {@code shipment.created} signal.
     *
     * @param id the instance to signal
     * @param payload the signal payload
     * @param dedupKey the deduplication key
     * @return a future that completes when the signal is applied
     */
    @WorkflowSignal("shipment.created")
    Future<Void> shipmentCreated(WorkflowInstanceId id, ShipmentCreatedDoc payload, @SignalDedupKey String dedupKey);
}
