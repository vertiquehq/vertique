// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OutboxDestinationHandler} that delivers outbox entries to Kafka topics.
 *
 * <p>The handler uses the shared {@link KafkaProducerFactory} to send records. Each outbox
 * entry's {@linkplain OutboxEnvelope#destination() destination} is used as the Kafka topic,
 * the {@linkplain OutboxEnvelope#aggregateId() aggregate ID} is used as the record key (may
 * be {@code null} — the producer selects a partition automatically in that case), and the
 * entry's {@linkplain OutboxEnvelope#headers() headers} are forwarded as Kafka record headers.
 * The durable propagation context stored in {@link OutboxEnvelope#metadata()} is projected to
 * reserved {@code vertique-*} Kafka headers via the explicit-context send overload, so the
 * original producer's context (not the relay poller's ambient context) crosses the boundary.
 *
 * <p>The payload is serialized from the {@link io.vertx.core.json.JsonObject} via Jackson.
 * Serialization failures produce a {@link OutboxPublishResult#permanent(String, Throwable)
 * permanent failure} — the entry is moved to dead-letter immediately without retrying.
 * Kafka transport errors produce a {@link OutboxPublishResult#retryable(String, Throwable)
 * retryable failure} so the relay can back off and retry.
 *
 * <p>After the {@link OutboxPublishResult} is determined (success or any failure classification),
 * all registered {@link KafkaOutboxCaptureHook} instances are invoked in
 * {@link OrderedExtension#comparator()} order as observer-only side effects. A throwing hook
 * does not change the result returned to the relay.
 *
 * <p>Register via {@link TransactionalMessagingKafkaModule}.
 */
@Slf4j
@Singleton
public class KafkaOutboxDestinationHandler implements OutboxDestinationHandler {

    // --- Dependencies ---

    private final KafkaProducerFactory producerFactory;
    private final ObjectMapper objectMapper;

    /** Sorted list of registered outbox capture hooks (observer-only). */
    private final List<KafkaOutboxCaptureHook> captureHooks;

    /**
     * Creates a new Kafka outbox destination handler with no capture hooks.
     *
     * @param producerFactory the shared Kafka producer factory used to send records
     * @param objectMapper    the Jackson object mapper used to serialize the outbox payload
     */
    @Inject
    KafkaOutboxDestinationHandler(KafkaProducerFactory producerFactory, ObjectMapper objectMapper) {
        this(producerFactory, objectMapper, Set.of());
    }

    /**
     * Creates a new Kafka outbox destination handler with the supplied capture hooks.
     *
     * @param producerFactory the shared Kafka producer factory used to send records
     * @param objectMapper    the Jackson object mapper used to serialize the outbox payload
     * @param captureHooks    the set of outbox capture hooks; sorted by
     *                        {@link OrderedExtension#comparator()} at construction time
     */
    KafkaOutboxDestinationHandler(
            KafkaProducerFactory producerFactory, ObjectMapper objectMapper, Set<KafkaOutboxCaptureHook> captureHooks) {
        this.producerFactory = producerFactory;
        this.objectMapper = objectMapper;
        List<KafkaOutboxCaptureHook> sorted = new ArrayList<>(captureHooks);
        sorted.sort(OrderedExtension.comparator());
        this.captureHooks = Collections.unmodifiableList(sorted);
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
     * are forwarded as Kafka record headers.
     *
     * <p>Serialization failures produce a {@link OutboxPublishResult#permanent permanent} result
     * so the entry is dead-lettered rather than retried indefinitely. Kafka transport failures
     * produce a {@link OutboxPublishResult#retryable retryable} result so the relay backs off
     * and tries again later.
     *
     * <p>After the result is determined, all registered {@link KafkaOutboxCaptureHook} instances
     * are invoked as observer-only side effects. Hook exceptions are swallowed and do not change
     * the returned result.
     *
     * @param envelope the outbox entry to deliver
     * @return a {@link Future} that always completes with a non-null {@link OutboxPublishResult}
     */
    @Override
    public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
        String topic = envelope.destination();
        String key = envelope.aggregateId();
        String entryId = String.valueOf(envelope.entryId());

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
            OutboxPublishResult result =
                    OutboxPublishResult.permanent("Failed to serialize outbox payload: " + e.getMessage(), e);
            // value bytes are unavailable — pass null PayloadSource to hooks. Headers carry the durable
            // context (merged) so the audit record's correlation matches what would have been published.
            fireHooks(topic, key, null, hookHeaders(envelope), result, entryId);
            return Future.succeededFuture(result);
        }

        // Use the outbox-origin overload so the record's persisted durable context (not the
        // relay poller's ambient context) is projected to reserved vertique-* Kafka headers,
        // and so capture hooks see KafkaSendOrigin.OUTBOX for this send.
        // Application headers in envelope.headers() are forwarded unchanged alongside the context.
        PayloadSource payloadSource = PayloadSources.buffered(value, null);
        return producerFactory
                .sendForOutbox(
                        topic,
                        key,
                        value,
                        envelope.headers(),
                        envelope.metadata().context())
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
                })
                // After the full recovery chain, every outcome is a succeeded Future<OutboxPublishResult>.
                // onSuccess fires for all three outcomes (Success, RetryableFailure, PermanentFailure)
                // because the preceding recover() converts transport failures to succeeded futures.
                .onSuccess(result -> fireHooks(topic, key, payloadSource, hookHeaders(envelope), result, entryId));
    }

    // --- Internal ---

    /**
     * Fires all registered {@link KafkaOutboxCaptureHook} instances in sorted order, isolating
     * each hook in a {@code try/catch} so exceptions never propagate to callers and never change
     * the publish result.
     *
     * @param topic    the Kafka topic targeted by the outbox entry
     * @param key      the Kafka record key, or {@code null}
     * @param value    a no-copy {@link PayloadSource} over the serialized bytes, or {@code null}
     *                 when serialization failed before any bytes were produced
     * @param headers  the application headers from the outbox envelope
     * @param result   the classified publish outcome
     * @param entryId  the string form of the outbox entry surrogate key
     */
    /**
     * Builds the headers handed to capture hooks: the application headers MERGED with the persisted
     * durable context (the same projection {@code sendForOutbox} applies before publishing), so the
     * audit record's correlation matches the message that was (or would have been) published rather
     * than seeing only application headers. Falls back to the raw application headers if the merge
     * rejects a reserved-prefix collision (an application-header authoring bug already dead-lettered
     * on the send path) so the hook still fires.
     *
     * @param envelope the outbox envelope; non-null
     * @return the merged headers for capture hooks; never null
     */
    private java.util.Map<String, String> hookHeaders(OutboxEnvelope envelope) {
        try {
            return DurableMetadataHeaderCodec.mergeForEgress(
                    envelope.headers(), envelope.metadata().context());
        } catch (IllegalArgumentException e) {
            return envelope.headers();
        }
    }

    private void fireHooks(
            String topic,
            String key,
            PayloadSource value,
            java.util.Map<String, String> headers,
            OutboxPublishResult result,
            String entryId) {
        if (captureHooks.isEmpty()) {
            return;
        }
        for (KafkaOutboxCaptureHook hook : captureHooks) {
            try {
                hook.onOutboxPublish(topic, key, value, headers, result, entryId);
            } catch (Exception ex) {
                log.warn(
                        "[KafkaOutboxDestinationHandler] Capture hook {} threw an exception — swallowing: {}",
                        hook.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex);
            }
        }
    }
}
