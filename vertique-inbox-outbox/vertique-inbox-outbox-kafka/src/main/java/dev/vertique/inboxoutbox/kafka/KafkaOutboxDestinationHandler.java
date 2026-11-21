// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * <h2>Forwarded context</h2>
 * <p>Which namespaces of the stored durable context leave the process as
 * {@code vertique-<namespace>} headers is decided by
 * {@link KafkaOutboxConfig#outboundContextNamespaces()}. Without the key, the handler forwards
 * every namespace the row holds except the signed {@value #IDENTITY_SNAPSHOT_NAMESPACE} namespace;
 * an explicit list forwards exactly the listed namespaces, so an empty list forwards none and a list
 * naming {@value #IDENTITY_SNAPSHOT_NAMESPACE} forwards the identity. A listed name that no encoder
 * of the {@link DurableContextMetadataRegistry} owns fails construction with an
 * {@link InboxOutboxConfigurationException}, so a misspelled list stops the node at startup instead
 * of silently dropping a namespace.
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

    /**
     * The identity-snapshot durable-context namespace. Mirrors the name the security runtime's
     * encoder writes; the default exclusion covers it because the signed identity must not reach a
     * topic that can be read outside the process unless the configuration names it.
     */
    static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    /**
     * The namespaces a handler without an explicit {@code outboundContextNamespaces} list never
     * forwards. Every other namespace the row holds is forwarded by default, so a future sensitive
     * namespace is kept inside the process only by being added here or by listing what may be sent.
     */
    static final Set<String> NEVER_FORWARDED_BY_DEFAULT = Set.of(IDENTITY_SNAPSHOT_NAMESPACE);

    // --- Dependencies ---

    private final KafkaProducerFactory producerFactory;
    private final ObjectMapper objectMapper;

    /** The explicit allowlist, or {@code null} for the default. */
    private final Set<String> outboundContextNamespaces;

    /**
     * Creates a new Kafka outbox destination handler.
     *
     * @param producerFactory the shared Kafka producer factory used to send records
     * @param objectMapper    the Jackson object mapper used to serialize the outbox payload
     * @param config          the Kafka outbox configuration, which decides the forwarded context
     * @param registry        the durable-context registry whose encoders own the namespace names a
     *                        configured list may use
     * @throws InboxOutboxConfigurationException if a listed namespace is not registered; the message
     *                                           names the configuration path, the position and the
     *                                           registered names
     */
    @Inject
    KafkaOutboxDestinationHandler(
            KafkaProducerFactory producerFactory,
            ObjectMapper objectMapper,
            KafkaOutboxConfig config,
            DurableContextMetadataRegistry registry) {
        this.producerFactory = producerFactory;
        this.objectMapper = objectMapper;
        this.outboundContextNamespaces = validatedAllowlist(config, registry);
    }

    /**
     * Checks every listed name against the namespaces the registered encoders own.
     *
     * @param config   the Kafka outbox configuration
     * @param registry the durable-context registry
     * @return the explicit allowlist, or {@code null} when the configuration keeps the default
     */
    private static Set<String> validatedAllowlist(KafkaOutboxConfig config, DurableContextMetadataRegistry registry) {
        List<String> listed = config.outboundContextNamespaces();
        if (listed == null) {
            return null;
        }
        Set<String> registered = new TreeSet<>();
        for (DurableContextMetadataEncoder<?> encoder : registry.encoders()) {
            registered.add(encoder.namespace());
        }
        for (int i = 0; i < listed.size(); i++) {
            if (!registered.contains(listed.get(i))) {
                throw new InboxOutboxConfigurationException(KafkaOutboxConfig.OUTBOUND_CONTEXT_NAMESPACES_PATH + "[" + i
                        + "] names a durable-context namespace no registered encoder owns; registered: " + registered);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(listed));
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
                .sendForOutbox(
                        topic,
                        key,
                        value,
                        headers,
                        forwardedContext(envelope.metadata().context()),
                        entryId)
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
     * Reduces the row's stored context to the namespaces this handler forwards.
     *
     * @param stored the row's stored durable context
     * @return the context to project as headers: without {@link #NEVER_FORWARDED_BY_DEFAULT} when the
     *         configuration keeps the default, or exactly the listed namespaces otherwise
     */
    private DurableMetadata forwardedContext(DurableMetadata stored) {
        DurableMetadata forwarded = stored;
        for (String namespace : stored.namespaces()) {
            if (namespace.isBlank()) {
                continue; // without("") would throw; a blank key is not a namespace and carries nothing
            }
            boolean keep = outboundContextNamespaces == null
                    ? !NEVER_FORWARDED_BY_DEFAULT.contains(namespace)
                    : outboundContextNamespaces.contains(namespace);
            if (!keep) {
                forwarded = forwarded.without(namespace);
            }
        }
        return forwarded;
    }

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
