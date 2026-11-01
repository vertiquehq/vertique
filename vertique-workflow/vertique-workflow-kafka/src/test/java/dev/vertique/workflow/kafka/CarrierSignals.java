// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.kafka.CarrierSignalTypes.OrdersContract;
import dev.vertique.workflow.kafka.CarrierSignalTypes.ShipmentDelivered;
import dev.vertique.workflow.source.WorkflowSignalTarget;

/**
 * The annotated listener of the test sources: an interface with no Dagger code and no handler method, which the code
 * generator turns into a signal source and the generated registrations module registers. It is the only
 * listener here, because the generated registrations module collects every listener of the test sources.
 */
@KafkaSignalListener(name = CarrierSignals.NAME, topic = CarrierSignals.TOPIC, groupId = CarrierSignals.GROUP)
interface CarrierSignals {

    /** The topic the listener reads. */
    String TOPIC = "carrier.events";

    /** The consumer group of the listener. */
    String GROUP = "logistics";

    /** The name of the listener, which is also its consumer's configuration key. */
    String NAME = "carrier-events";

    @WorkflowSignalTarget(
            contract = OrdersContract.class,
            signal = CarrierSignalTypes.SHIPMENT_DELIVERED,
            key = "carrierRef",
            dedupBy = "eventId")
    void delivered(ShipmentDelivered event);
}
