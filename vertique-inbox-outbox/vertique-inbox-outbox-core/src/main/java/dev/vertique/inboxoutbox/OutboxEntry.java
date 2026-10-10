// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * A request to record a side effect in the outbox table, to be delivered to a destination
 * exactly once via the relay pipeline.
 *
 * <p>Instances are constructed via the Lombok builder and passed to
 * {@link OutboxService#publish(io.vertx.sqlclient.SqlClient, OutboxEntry)} within a database
 * transaction. The outbox relay claims pending entries and delivers them asynchronously.
 *
 * <p>Example usage:
 * <pre>{@code
 * OutboxEntry entry = OutboxEntry.builder()
 *     .eventType("order.placed")
 *     .destinationType(DestinationType.SERVICE)
 *     .destination("orders/handle-order-placed")
 *     .payload(new OrderPlacedEvent(orderId))
 *     .build();
 * outboxService.publish(tx, entry);
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class OutboxEntry {

    /**
     * Optional domain aggregate type that produced this event (e.g., {@code "Order"}).
     * Used for filtering, routing, and observability.
     */
    private final String aggregateType;

    /**
     * Optional identifier of the specific aggregate instance (e.g., the order UUID).
     * Used together with {@link #aggregateType} to correlate events to a domain entity.
     */
    private final String aggregateId;

    /**
     * Logical event type name (e.g., {@code "order.placed"}). Required. Used by destination
     * handlers and consumers to identify and route the event.
     */
    private final String eventType;

    /**
     * The category of the destination system. Required. Determines which
     * {@link OutboxDestinationHandler} implementation is used to deliver the entry.
     */
    private final DestinationType destinationType;

    /**
     * The specific destination address within the destination type — for example, an event bus
     * service address, a delayed job handler name, or a Kafka topic name. Required.
     */
    private final String destination;

    /**
     * The event payload to be delivered to the destination. Required. Any Jackson-serializable
     * value is accepted (POJO, {@code String}, {@code Number}, {@code Boolean},
     * {@link java.util.Collection}, {@link java.util.Map}, etc.). The value is encoded to JSONB
     * by {@link PayloadCodec} at the repository boundary and decoded back to an {@code Object}
     * by the relay adapter before delivery.
     */
    private final Object payload;

    /**
     * Optional key-value metadata to pass alongside the payload. For Kafka destinations these
     * become Kafka record headers; for service destinations they are injected into the
     * dispatch context.
     *
     * <p>Headers are text only and keys are unique. The entry holds an unmodifiable copy of the map
     * given to the builder, taken when the entry is built: a later change to that map does not reach
     * the entry, and the map {@link #headers()} returns rejects mutation. The copy keeps the
     * iteration order of the given map, but storage does not — once the entry is stored its headers
     * carry no ordering guarantee.
     *
     * <p>A {@code null} key, a {@code null} value and a key that starts with the prefix reserved for
     * framework headers are carried by the entry but cannot be delivered:
     * {@link OutboxService#publish(io.vertx.sqlclient.SqlClient, OutboxEntry)} rejects such an entry.
     */
    @Builder.Default
    private final Map<String, String> headers = Map.of();

    /**
     * Maximum number of delivery attempts before the entry is moved to dead-letter state.
     * Defaults to {@code 20}. Must be a positive integer.
     */
    @Builder.Default
    private final int maxAttempts = 20;

    /**
     * Optional wall-clock time at which this entry is intended to be delivered. Relay workers
     * will not claim entries whose {@code scheduledAt} is in the future. When {@code null}
     * the entry is eligible for immediate relay.
     */
    private final Instant scheduledAt;

    /**
     * Optional delayed-job scheduling snapshot captured at publish time. When set, the relay
     * stores this in {@code metadata.delivery.delayedJob} and the
     * {@code DelayedJobOutboxDestinationHandler} reads it at relay time instead of inferring
     * scheduling defaults from headers. Should be {@code null} for non-delayed-job destinations.
     */
    private final DelayedJobControl delayedJob;

    /**
     * Internal field set by the relay backoff logic. Represents the earliest time at which
     * this entry may be re-claimed after a failed publish attempt. Not set by callers.
     */
    private final Instant availableAt;

    /**
     * Creates an entry from the builder's values, taking an unmodifiable copy of the header map.
     *
     * @param aggregateType   optional domain aggregate type
     * @param aggregateId     optional aggregate instance id
     * @param eventType       logical event type name
     * @param destinationType category of the destination system
     * @param destination     destination address within the destination type
     * @param payload         the event payload
     * @param headers         application headers; copied, {@code null} means none
     * @param maxAttempts     maximum number of delivery attempts
     * @param scheduledAt     optional intended delivery time
     * @param delayedJob      optional delayed-job scheduling snapshot
     * @param availableAt     earliest time at which the entry may be claimed
     */
    OutboxEntry(
            String aggregateType,
            String aggregateId,
            String eventType,
            DestinationType destinationType,
            String destination,
            Object payload,
            Map<String, String> headers,
            int maxAttempts,
            Instant scheduledAt,
            DelayedJobControl delayedJob,
            Instant availableAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.destinationType = destinationType;
        this.destination = destination;
        this.payload = payload;
        this.headers = unmodifiableCopy(headers);
        this.maxAttempts = maxAttempts;
        this.scheduledAt = scheduledAt;
        this.delayedJob = delayedJob;
        this.availableAt = availableAt;
    }

    /**
     * Returns the key-value metadata to pass alongside the payload.
     *
     * <p>A {@code null} map given to the builder is reported as an empty map — the copy taken at
     * construction is never {@code null} — so a reader never has to guard against {@code null}. The
     * returned map is the entry's own unmodifiable copy.
     *
     * @return the headers; an empty map when none were set; never {@code null}
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Takes the unmodifiable header copy an entry, a record or an envelope holds.
     *
     * <p>The copy keeps the iteration order of {@code headers} and carries {@code null} keys and
     * values as given, so that publish can still reject them by name.
     *
     * @param headers the map to copy; {@code null} means no headers
     * @return an unmodifiable copy; an empty map when {@code headers} is {@code null} or empty
     */
    static Map<String, String> unmodifiableCopy(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
}
