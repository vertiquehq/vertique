// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import dev.vertique.core.scope.ScopeKey;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;

/**
 * Delivery envelope passed to {@link OutboxDestinationHandler} implementations when the relay
 * publishes an outbox entry to its destination.
 *
 * <p>The envelope contains only the fields needed for delivery — it omits internal relay
 * bookkeeping fields (such as {@code claimedBy} and {@code state}) that are not relevant
 * to destination handlers.
 *
 * <p>The {@code payload} field holds the raw value read from JSONB storage. It is typically a
 * {@link io.vertx.core.json.JsonObject} for POJO/map payloads or a scalar-wrapped
 * {@link io.vertx.core.json.JsonObject} (see {@link PayloadCodec}) for scalar types.
 * Destination handlers use {@link PayloadCodec#decode(io.vertx.core.json.JsonObject, Class)}
 * to deserialize the payload to their expected type.
 *
 * @param entryId       surrogate primary key of the originating outbox entry
 * @param aggregateType optional domain aggregate type that produced the event
 * @param aggregateId   optional identifier of the specific aggregate instance
 * @param eventType     logical event type name (e.g., {@code "order.placed"})
 * @param destination   specific destination address within the destination type
 * @param payload       raw payload read from JSONB storage; use {@link PayloadCodec} to decode
 * @param headers       application/transport headers only (no framework keys); durable propagation
 *                      context and delivery control live in {@link #metadata()}. Text only, unique
 *                      keys, no ordering guarantee once stored. The envelope holds an unmodifiable
 *                      copy taken at construction; {@code null} means none and reads back as an
 *                      empty map
 * @param metadata      structured metadata document holding the durable propagation context
 *                      ({@code context}) and delivery control ({@code delivery}); never {@code null}
 * @param scheduledAt   optional intended delivery time specified by the producer
 * @param attempt       zero-based delivery attempt counter (0 = first attempt)
 * @param createdAt     time at which the originating outbox entry was inserted
 * @param scopeKey      the scope the originating entry was published in, a partition label read from
 *                      the stored row; {@code null} is read as {@link ScopeKey#DEFAULT}, so the
 *                      accessor never returns {@code null}. It is a label, not authorization, and
 *                      the relay does not bind it as the ambient scope
 */
public record OutboxEnvelope(
        long entryId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String destination,
        Object payload,
        Map<String, String> headers,
        OutboxMetadata metadata,
        Instant scheduledAt,
        int attempt,
        Instant createdAt,
        @Nullable String scopeKey) {

    /**
     * Creates an envelope in the default scope.
     *
     * <p>This is the constructor shape that predates {@code scopeKey}; it passes {@code null}, which
     * the canonical constructor reads as {@link ScopeKey#DEFAULT}.
     *
     * @param entryId       surrogate primary key of the originating outbox entry
     * @param aggregateType optional domain aggregate type that produced the event
     * @param aggregateId   optional identifier of the specific aggregate instance
     * @param eventType     logical event type name
     * @param destination   specific destination address within the destination type
     * @param payload       raw payload read from JSONB storage
     * @param headers       application/transport headers; {@code null} means none
     * @param metadata      structured metadata document
     * @param scheduledAt   optional intended delivery time specified by the producer
     * @param attempt       zero-based delivery attempt counter
     * @param createdAt     time at which the originating outbox entry was inserted
     */
    public OutboxEnvelope(
            long entryId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String destination,
            Object payload,
            Map<String, String> headers,
            OutboxMetadata metadata,
            Instant scheduledAt,
            int attempt,
            Instant createdAt) {
        this(
                entryId,
                aggregateType,
                aggregateId,
                eventType,
                destination,
                payload,
                headers,
                metadata,
                scheduledAt,
                attempt,
                createdAt,
                null);
    }

    /**
     * Takes an unmodifiable copy of {@code headers}, so a later change to the given map does not
     * reach the envelope and the map {@link #headers()} returns rejects mutation, and maps a
     * {@code null} {@code scopeKey} to {@link ScopeKey#DEFAULT}.
     *
     * @param headers  the application headers; may be {@code null} (treated as empty)
     * @param scopeKey the scope label; may be {@code null} (treated as the default scope)
     */
    public OutboxEnvelope {
        headers = OutboxEntry.unmodifiableCopy(headers);
        scopeKey = scopeKey != null ? scopeKey : ScopeKey.DEFAULT.value();
    }
}
