// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import java.time.Instant;

/**
 * The types a signal source and the workflow it feeds share: the contract, the message of the carriers' topic and the
 * payload the contract's signal takes. They are ordinary test classes, visible to every node, because a node that only
 * hosts a source needs them; the workflow definition and its state are not here, they live in
 * {@link SignalNodesFixture}. The types are public so that the isolated definition can name them.
 */
public final class ShipmentSignals {

    /** The name of the signal of {@link OrdersContract}. */
    public static final String SHIPMENT_DELIVERED = "shipment-delivered";

    private ShipmentSignals() {}

    /** A contract whose one signal takes {@link DeliveryConfirmed}. */
    @WorkflowContract("orders")
    public interface OrdersContract {
        @WorkflowSignal(SHIPMENT_DELIVERED)
        Future<Void> shipmentDelivered(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
    }

    /**
     * What the signal carries.
     *
     * @param deliveredAt when the parcel was delivered
     */
    public record DeliveryConfirmed(Instant deliveredAt) {}

    /**
     * The message type of the carriers' topic.
     *
     * @param eventId the id of the event
     * @param carrierRef the reference the carrier delivered under
     * @param deliveredAt when the parcel was delivered
     */
    public record ShipmentDelivered(String eventId, String carrierRef, Instant deliveredAt) {}
}
