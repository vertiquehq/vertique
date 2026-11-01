// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.SHIPMENT_DELIVERED;

import dev.vertique.workflow.kafka.SourceScenario.OrdersContract;
import dev.vertique.workflow.kafka.SourceScenario.ShipmentDelivered;
import dev.vertique.workflow.source.WorkflowSignalSource;
import jakarta.inject.Inject;

/**
 * The one signal source class of the test sources, which generated wiring registers; no {@code @Provides} method
 * names it. It resolves the business key of a delivery with the lookup it received by constructor injection. It is the
 * only class marked {@code @WorkflowSignalSource} here, because the generated registrations module collects every such
 * class of the test sources.
 */
@WorkflowSignalSource
final class ShipmentDeliveredSource implements KafkaSignalSource<ShipmentDelivered> {

    /** The topic the source reads. */
    static final String TOPIC = "shipment.events";

    /** The consumer group of the source. */
    static final String GROUP = "shipment-source-class";

    /** The name of the source, which is also its consumer's configuration key. */
    static final String NAME = "shipment-source";

    private final ShipmentLookup lookup;

    @Inject
    ShipmentDeliveredSource(ShipmentLookup lookup) {
        this.lookup = lookup;
    }

    /** The lookup the component handed to the constructor. */
    ShipmentLookup lookup() {
        return lookup;
    }

    @Override
    public void define(KafkaSignals<ShipmentDelivered> source) {
        source.topic(TOPIC)
                .groupId(GROUP)
                .name(NAME)
                .dedupBy(ShipmentDelivered::eventId)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .businessKeyFrom(message -> lookup.orderNumberFor(message.body().carrierRef()))
                .payload(SourceScenario::confirmation);
    }
}
