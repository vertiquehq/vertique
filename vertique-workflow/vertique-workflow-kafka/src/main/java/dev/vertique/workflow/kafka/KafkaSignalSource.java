// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.source.SignalSourceType;

/**
 * A Kafka signal source written as a class.
 *
 * <p>Implement it on a class annotated {@code @WorkflowSignalSource} with an {@code @Inject} constructor: generated
 * wiring registers the class, with no application {@code @Provides} method, and
 * {@link WorkflowKafkaSignalsModule} turns it into a {@code KafkaConsumerBinding} by calling {@link #define}. The
 * dependencies the class needs, a lookup for example, come by constructor injection, and {@link #define} uses them as
 * ordinary fields.
 *
 * <pre>{@code
 * @WorkflowSignalSource
 * final class ShipmentSignals implements KafkaSignalSource<ShipmentDelivered> {
 *     private final ShipmentLookup lookup;
 *
 *     @Inject
 *     ShipmentSignals(ShipmentLookup lookup) {
 *         this.lookup = lookup;
 *     }
 *
 *     @Override
 *     public void define(KafkaSignals<ShipmentDelivered> source) {
 *         source.topic("shipment.events")
 *                 .groupId("logistics")
 *                 .dedupBy(ShipmentDelivered::eventId)
 *                 .signal(OrdersContract.class, "shipment-delivered")
 *                 .businessKeyFrom(message -> lookup.orderNumberFor(message.body().carrierRef()));
 *     }
 * }
 * }</pre>
 *
 * <p>The message type {@code T} is read from the class's type argument, so the class states it: a raw
 * {@code KafkaSignalSource}, or a type variable the class leaves unresolved, fails startup naming the class.
 *
 * @param <T> the type of the decoded record value
 */
@SignalSourceType(module = WorkflowKafkaSignalsModule.class)
public interface KafkaSignalSource<T> {

    /**
     * Describes the source on the builder: its topic and group, the signals it records and how a record addresses
     * their instances. The framework calls {@code build()} on the builder after this method returns.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation; processing does not continue.
     *
     * @param source the builder to describe the source on
     */
    void define(KafkaSignals<T> source);
}
