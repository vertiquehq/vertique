// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * The types the annotated carriers' listener and a builder source over the same topic shape share: the contract whose
 * signal takes the carriers' message itself, so that an annotated listener, which maps no payload, can feed it; the
 * message; and a workflow that waits for the signal on its carrier reference.
 */
final class CarrierSignalTypes {

    /** The name of the signal of {@link OrdersContract}. */
    static final String SHIPMENT_DELIVERED = "shipment-delivered";

    private CarrierSignalTypes() {}

    /** A contract whose one signal takes {@link ShipmentDelivered}, the message of the carriers' topic. */
    @WorkflowContract("carrier-orders")
    interface OrdersContract {
        @WorkflowSignal(SHIPMENT_DELIVERED)
        Future<Void> shipmentDelivered(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, ShipmentDelivered payload);
    }

    /**
     * The message of the carriers' topic, and the payload of the signal.
     *
     * @param eventId the id of the event
     * @param carrierRef the reference the carrier delivered under
     */
    record ShipmentDelivered(String eventId, String carrierRef) {}

    /**
     * The start payload of the workflow.
     *
     * @param carrierRef the reference the workflow waits for
     */
    record StartShipping(String carrierRef) {}

    /**
     * The state of the workflow.
     *
     * @param carrierRef the reference the workflow waits for
     * @param eventId the id of the event that resumed the workflow, once the signal applied
     */
    record State(String carrierRef, @Nullable String eventId) {}

    /** Waits for {@code shipment-delivered} on its carrier reference and keeps the id of the event that resumed it. */
    static final class OrderShipping implements WorkflowDefinition<State, OrdersContract> {

        static final String ID = "carrier-order-shipping";

        @Override
        public Class<OrdersContract> contract() {
            return OrdersContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return ID;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartShipping.class, start -> new State(start.carrierRef(), null));
            wf.initialStep("wait-delivery");
            wf.waitForSignal("wait-delivery", SHIPMENT_DELIVERED, ShipmentDelivered.class)
                    .correlateBy(State::carrierRef)
                    .onSignal((state, delivered) -> new State(state.carrierRef(), delivered.eventId()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        }
    }
}
