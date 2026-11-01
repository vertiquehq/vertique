// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaConsumerRegistry;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.json.JsonSerdeProvider;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.workflow.contract.IdempotencyKey;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.services.source.WorkflowSignalSourceDelivery;
import dev.vertique.workflow.source.SignalSourceEvent;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared types and builders of the Kafka source proofs: a contract that declares one signal, the
 * message type of the carriers' topic, a delivery double that records the events it is told about,
 * and a registry call over the public {@link KafkaConsumerRegistry}. Nothing here connects to a broker.
 */
final class SourceFixture {

    static final String TOPIC = "carrier.events";
    static final String GROUP = "logistics";
    static final String SHIPMENT_DELIVERED = "shipment-delivered";

    /** The name a source gets when no {@code .name} replaces it: group, a dash, then topic. */
    static final String DEFAULT_NAME = GROUP + "-" + TOPIC;

    private SourceFixture() {}

    /** A contract whose one signal takes {@link DeliveryConfirmed}. */
    @WorkflowContract("orders")
    interface OrdersContract {
        @WorkflowStart
        Future<WorkflowInstanceId> start(StartShipping payload, @IdempotencyKey String idempotencyKey);

        @WorkflowSignal(SHIPMENT_DELIVERED)
        Future<Void> shipmentDelivered(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
    }

    /** What starts a workflow of the contract. */
    record StartShipping(String carrierRef) {}

    /** What the contract's signal takes. */
    record DeliveryConfirmed(String carrierRef, String note) {}

    /** The message type of the carriers' topic. */
    record ShipmentDelivered(String carrierRef, String orderNumber, String note) {}

    /**
     * A delivery double and the events it was told about through {@code report}.
     *
     * @param delivery the double, to hand to {@link WorkflowKafkaSignals}
     * @param events the events reported to it, in order
     */
    record Carriers(WorkflowSignalSourceDelivery delivery, List<SignalSourceEvent> events) {

        /**
         * Starts a source over the carriers' topic and group.
         *
         * @return the source, with no signal yet
         */
        KafkaSignals<ShipmentDelivered> source() {
            return signals().on(TOPIC, ShipmentDelivered.class).groupId(GROUP);
        }

        /**
         * Creates the entry point over the double and a correlation factory with the default id
         * generator.
         *
         * @return the entry point
         */
        WorkflowKafkaSignals signals() {
            return new WorkflowKafkaSignals(delivery, new CorrelationContextFactory(Optional.empty()));
        }
    }

    /**
     * Supplies the delivery double of one source and the list of events it records.
     *
     * @return the double and its events
     */
    static Carriers carriers() {
        return recordingDelivery();
    }

    /**
     * Creates a delivery whose {@code report} records each event.
     *
     * @return the double and its events
     */
    static Carriers recordingDelivery() {
        WorkflowSignalSourceDelivery delivery = mock(WorkflowSignalSourceDelivery.class);
        List<SignalSourceEvent> events = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
                    events.add(invocation.getArgument(0));
                    return null;
                })
                .when(delivery)
                .report(any(SignalSourceEvent.class));
        return new Carriers(delivery, events);
    }

    /**
     * Completes a source with the one signal of the contract, keyed by the carrier reference.
     *
     * @param source the source, with its own stages already set
     * @return the binding the source builds
     */
    static KafkaConsumerBinding<ShipmentDelivered> keyedByCarrier(KafkaSignals<ShipmentDelivered> source) {
        return source.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(message -> new DeliveryConfirmed(message.carrierRef(), message.note()))
                .build();
    }

    /**
     * Registers one binding over a configuration of its consumer, with an empty service registry and
     * the JSON serde registry.
     *
     * @param binding the binding
     * @param consumerConfig the block under {@code kafka.consumers.{binding name}}; empty for none
     * @return the registry
     * @throws dev.vertique.kafka.KafkaRegistrationException when the registration is refused
     */
    static KafkaConsumerRegistry register(KafkaConsumerBinding<?> binding, JsonObject consumerConfig) {
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());
        JsonObject config = new JsonObject()
                .put("kafka", new JsonObject().put("consumers", new JsonObject().put(binding.name(), consumerConfig)));
        ServiceContractRegistry services = ServiceContractRegistry.build(Set.of(), parser);
        KafkaSerdeRegistry serdes = new KafkaSerdeRegistry(
                Set.of(new JsonSerdeProvider(new DefaultJsonMapperProfileRegistry(Set.of()), JsonConfig.defaults())));
        return KafkaConsumerRegistry.build(
                Set.of(binding),
                Set.of(),
                services,
                ServiceTargetResolver.of(services),
                serdes,
                KafkaConfig.fromConfig(config, parser));
    }
}
