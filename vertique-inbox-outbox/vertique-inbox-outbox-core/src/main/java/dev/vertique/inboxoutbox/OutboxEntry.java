// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Instant;
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
}
