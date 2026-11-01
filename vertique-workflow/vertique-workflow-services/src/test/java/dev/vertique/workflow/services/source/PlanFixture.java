// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalNoMatch;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared types and builders of the plan proofs: two workflow contracts that each declare one signal,
 * two message types (one that matches the signals' parameter type and one that does not), a message
 * that carries headers, and an envelope for a Kafka source.
 */
final class PlanFixture {

    static final String SHIPMENT_DELIVERED = "shipment-delivered";
    static final String INVOICE_SETTLED = "invoice-settled";

    /** The dedup key the envelope offers when a plan names none. */
    static final String DEFAULT_DEDUP_KEY = "carriers:3:42";

    static final SignalProvenance KAFKA_PROVENANCE = SignalProvenance.kafka("carriers", "carrier.events", 3, 42);

    /** A contract whose one signal takes {@link DeliveryConfirmed}. */
    @WorkflowContract("orders")
    interface OrdersContract {
        @WorkflowSignal(SHIPMENT_DELIVERED)
        Future<Void> shipmentDelivered(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
    }

    /** A second contract whose one signal also takes {@link DeliveryConfirmed}. */
    @WorkflowContract("billing")
    interface BillingContract {
        @WorkflowSignal(INVOICE_SETTLED)
        Future<Void> invoiceSettled(
                WorkflowInstanceId instanceId, @SignalDedupKey String dedupKey, DeliveryConfirmed payload);
    }

    /** What the contracts' signals take. */
    record DeliveryConfirmed(String carrierRef, String note) {}

    /** A message type that differs from what the signals take. */
    record ShipmentDelivered(String carrierRef, String orderNo, String note) {}

    /** A message with a body, headers and no record key. */
    static final class TestMessage<T> implements SignalMessage<T> {
        private final T body;
        private final Map<String, String> headers;

        TestMessage(T body, Map<String, String> headers) {
            this.body = body;
            this.headers = Map.copyOf(headers);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name));
        }

        @Override
        public Optional<String> key() {
            return Optional.empty();
        }
    }

    private PlanFixture() {}

    /** A builder for source {@code carriers} over {@link ShipmentDelivered}. */
    static SignalSourcePlan.Builder<ShipmentDelivered> shipments() {
        return SignalSourcePlan.builder("carriers", ShipmentDelivered.class);
    }

    /** A builder for source {@code carriers} over the type the signals take. */
    static SignalSourcePlan.Builder<DeliveryConfirmed> confirmations() {
        return SignalSourcePlan.builder("carriers", DeliveryConfirmed.class);
    }

    static DeliveryConfirmed confirmation(ShipmentDelivered shipment) {
        return new DeliveryConfirmed(shipment.carrierRef(), shipment.note());
    }

    /** A plan over {@link ShipmentDelivered} with one signal addressed by the carrier reference. */
    static SignalSourcePlan<ShipmentDelivered> keyedPlan() {
        return ordersSignal(shipments());
    }

    /** Adds the orders signal, addressed by the carrier reference with a payload mapping, and builds. */
    static SignalSourcePlan<ShipmentDelivered> ordersSignal(SignalSourcePlan.Builder<ShipmentDelivered> builder) {
        return builder.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .build();
    }

    /**
     * A plan over {@link ShipmentDelivered} with two signals on two contracts, both addressed by the
     * carrier reference, with the dedup key {@code D-<reference>} and the given source default.
     */
    static SignalSourcePlan<ShipmentDelivered> twoSignalPlan(SignalNoMatch sourceDefault) {
        return shipments()
                .dedupBy(PlanFixture::dedupKeyOf)
                .onNoMatch(sourceDefault)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(ShipmentDelivered::carrierRef)
                .payload(PlanFixture::confirmation)
                .build();
    }

    static String dedupKeyOf(ShipmentDelivered shipment) {
        return "D-" + shipment.carrierRef();
    }

    /** The message every delivery proof sends unless it needs something else: carrier reference {@code PAY-77}. */
    static SignalMessage<ShipmentDelivered> shipment() {
        return shipment("PAY-77");
    }

    static SignalMessage<ShipmentDelivered> shipment(String carrierRef) {
        return new TestMessage<>(new ShipmentDelivered(carrierRef, "ORDER-5", "delivered"), Map.of());
    }

    static <T> SignalMessage<T> message(T body) {
        return new TestMessage<>(body, Map.of());
    }

    static <T> SignalMessage<T> message(T body, Map<String, String> headers) {
        return new TestMessage<>(body, headers);
    }

    // --- envelopes ---

    /** The envelope of a Kafka source: a system identity, correlation {@code C-1} and the Kafka origin. */
    static SourceEnvelope envelope() {
        return envelope(dedupKey -> KAFKA_PROVENANCE);
    }

    static SourceEnvelope envelope(Function<String, SignalProvenance> provenance) {
        return new SourceEnvelope(
                "kafka",
                DeliveryFixture.security("kafka source: carriers"),
                false,
                CorrelationContext.generated("C-1"),
                InvocationOrigin.of("kafka"),
                DEFAULT_DEDUP_KEY,
                provenance);
    }

    /** The same envelope, with the identity marked as already contributed by the transport. */
    static SourceEnvelope contributedEnvelope() {
        SourceEnvelope base = envelope();
        return new SourceEnvelope(
                base.transport(),
                base.security(),
                true,
                base.correlation(),
                base.origin(),
                base.defaultDedupKey(),
                base.provenance());
    }

    // --- identities ---

    static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    /** A stage function that throws the given exception whatever it is given. */
    static <A, B> Function<A, B> throwing(RuntimeException failure) {
        return ignored -> {
            throw failure;
        };
    }
}
