// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OutboxDestinationHandler} that delivers outbox entries to Kafka topics.
 *
 * <p>The handler uses the shared {@link KafkaProducerFactory} to send records. Each outbox
 * entry's {@linkplain OutboxEnvelope#destination() destination} is used as the Kafka topic,
 * the {@linkplain OutboxEnvelope#aggregateId() aggregate ID} is used as the record key (may
 * be {@code null} — the producer selects a partition automatically in that case), and the
 * entry's {@linkplain OutboxEnvelope#headers() headers} are converted with
 * {@link KafkaRecordHeaders#of(Map)} — one UTF-8 text header per entry, in the map's iteration
 * order — and forwarded as Kafka record headers.
 * The durable propagation context stored in {@link OutboxEnvelope#metadata()} is projected to
 * reserved {@code vertique-*} Kafka headers via the explicit-context send overload, so the
 * original producer's context (not the relay poller's ambient context) crosses the boundary. The
 * entry id is passed to that send as its origin reference, so a producer capture hook can tell
 * which outbox entry the wire bytes belong to.
 *
 * <p>The payload is serialized from the {@link io.vertx.core.json.JsonObject} via Jackson.
 * Serialization failures produce a {@link OutboxPublishResult#permanent(String, Throwable)
 * permanent failure} — the entry is moved to dead-letter immediately without retrying.
 * Kafka transport errors produce a {@link OutboxPublishResult#retryable(String, Throwable)
 * retryable failure} so the relay can back off and retry.
 *
 * <p>The handler notifies nobody itself. A publish attempt is observed at the relay, through
 * {@link dev.vertique.inboxoutbox.OutboxPublishObserver}; the wire bytes of a send are observed
 * through {@link dev.vertique.kafka.producer.KafkaProducerCaptureHook}, with origin
 * {@link dev.vertique.kafka.producer.KafkaSendOrigin#OUTBOX} and the entry id as the origin
 * reference.
 *
 * <p>Register via {@link TransactionalMessagingKafkaModule}.
 */
@Slf4j
@Singleton
public class KafkaOutboxDestinationHandler implements OutboxDestinationHandler {

    // --- Dependencies ---

    private final KafkaProducerFactory producerFactory;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Kafka outbox destination handler.
     *
     * @param producerFactory the shared Kafka producer factory used to send records
     * @param objectMapper    the Jackson object mapper used to serialize the outbox payload
     */
    @Inject
    KafkaOutboxDestinationHandler(KafkaProducerFactory producerFactory, ObjectMapper objectMapper) {
        this.producerFactory = producerFactory;
        this.objectMapper = objectMapper;
    }

    // --- OutboxDestinationHandler ---

    /**
     * Returns {@link DestinationType#KAFKA}, identifying this handler as the Kafka delivery adapter.
     *
     * @return {@link DestinationType#KAFKA}
     */
    @Override
    public DestinationType destinationType() {
        return DestinationType.KAFKA;
    }

    /**
     * Declares that this node may claim every {@code KAFKA} outbox row when this handler is
     * present.
     *
     * <p>Kafka topics are globally deliverable: any relay node with a Kafka producer can publish
     * to any topic. There is no node-local registration concept for topics, so
     * {@link ClaimScope#all()} is the correct scope — restricting by a resolver would prevent
     * healthy relay nodes from picking up rows they are fully capable of delivering.
     *
     * @return {@link ClaimScope#all()}, admitting every {@code KAFKA} row for claiming
     */
    @Override
    public ClaimScope claimScope() {
        return ClaimScope.all();
    }

    /**
     * Publishes the outbox envelope to the Kafka topic specified by
     * {@link OutboxEnvelope#destination()}.
     *
     * <p>The message key is set to {@link OutboxEnvelope#aggregateId()} (which may be
     * {@code null}). The payload is serialized to bytes via Jackson and the envelope headers
     * are converted with {@link KafkaRecordHeaders#of(Map)} and forwarded as Kafka record headers;
     * a {@code null} or empty header map gives a record without application headers.
     *
     * <p>A header map with a {@code null} key or value, and a serialization failure, each produce a
     * {@link OutboxPublishResult#permanent permanent} result so the entry is dead-lettered rather
     * than retried indefinitely. Kafka transport failures
     * produce a {@link OutboxPublishResult#retryable retryable} result so the relay backs off
     * and tries again later. A send rejected because an application header uses the reserved
     * framework prefix is {@link OutboxPublishResult#permanent permanent} too.
     *
     * @param envelope the outbox entry to deliver
     * @return a {@link Future} that always completes with a non-null {@link OutboxPublishResult}
     */
    @Override
    public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
        String topic = envelope.destination();
        String key = envelope.aggregateId();
        String entryId = String.valueOf(envelope.entryId());
        KafkaRecordHeaders headers;
        try {
            headers = recordHeaders(envelope);
        } catch (NullPointerException e) {
            // A null key or value in the stored header map is an authoring bug: the stored row cannot
            // change, so retrying is futile — fail permanently (dead-letter). Nothing is converted,
            // serialized or sent.
            log.warn(
                    "Outbox entry {} to topic {} has a header map with a null key or value; dead-lettering: {}",
                    envelope.entryId(),
                    topic,
                    e.getMessage());
            return Future.succeededFuture(
                    OutboxPublishResult.permanent("Outbox header map has a null key or value: " + e.getMessage(), e));
        }

        byte[] value;
        try {
            // Unwrap scalar payloads stored via PayloadCodec, then serialize via Jackson
            Object payload = (envelope.payload() instanceof io.vertx.core.json.JsonObject jo)
                    ? PayloadCodec.decode(jo)
                    : envelope.payload();
            Object serializablePayload = payload instanceof io.vertx.core.json.JsonObject jo2 ? jo2.getMap() : payload;
            value = objectMapper.writeValueAsBytes(serializablePayload);
        } catch (Exception e) {
            log.warn("Failed to serialize outbox payload for entry {}: {}", envelope.entryId(), e.getMessage());
            return Future.succeededFuture(
                    OutboxPublishResult.permanent("Failed to serialize outbox payload: " + e.getMessage(), e));
        }

        // Use the outbox-origin overload so the record's persisted durable context (not the
        // relay poller's ambient context) is projected to reserved vertique-* Kafka headers,
        // and so producer capture hooks see KafkaSendOrigin.OUTBOX and the entry id for this send.
        // The converted application headers are forwarded unchanged, followed by the context headers.
        return producerFactory
                .sendForOutbox(topic, key, value, headers, envelope.metadata().context(), entryId)
                .map(metadata -> (OutboxPublishResult) OutboxPublishResult.success())
                .recover(err -> {
                    // A reserved-prefix collision is an authoring bug in the application headers: the
                    // stored row cannot change, so retrying is futile — fail permanently (dead-letter).
                    if (err instanceof IllegalArgumentException) {
                        log.warn(
                                "Outbox entry {} to topic {} carries an application header using the reserved "
                                        + "framework prefix; dead-lettering: {}",
                                envelope.entryId(),
                                topic,
                                err.getMessage());
                        return Future.succeededFuture(OutboxPublishResult.permanent(
                                "Application header uses reserved framework prefix: " + err.getMessage(), err));
                    }
                    log.warn(
                            "Kafka publish failed for entry {} to topic {}: {}",
                            envelope.entryId(),
                            topic,
                            err.getMessage());
                    return Future.succeededFuture(
                            OutboxPublishResult.retryable("Kafka publish failed: " + err.getMessage(), err));
                });
    }

    // --- Internal ---

    /**
     * Converts the envelope's text header map to Kafka record headers: one UTF-8 text header per
     * entry, in the map's iteration order.
     *
     * @param envelope the outbox envelope; non-null
     * @return the converted headers; {@link KafkaRecordHeaders#empty()} when the envelope has a
     *     {@code null} or empty header map; never null
     * @throws NullPointerException if the header map contains a {@code null} key or value
     */
    private static KafkaRecordHeaders recordHeaders(OutboxEnvelope envelope) {
        Map<String, String> headers = envelope.headers();
        return headers == null || headers.isEmpty() ? KafkaRecordHeaders.empty() : KafkaRecordHeaders.of(headers);
    }
}
