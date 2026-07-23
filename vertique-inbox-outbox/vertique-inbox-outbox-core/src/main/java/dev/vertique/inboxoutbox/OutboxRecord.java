// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Full database row representation of an outbox entry, including all relay lifecycle fields.
 *
 * <p>Instances are returned by {@link OutboxRepository} query methods and passed to the
 * relay pipeline. All fields map 1:1 to columns in the {@code outbox_entries} table, with the
 * exception of {@link #metadata()} which is read from the {@code metadata} JSONB column.
 *
 * @param id              surrogate primary key assigned by the database on insert
 * @param carrierId       framework-generated durable row-carrier identity, allocated by the producer
 *                        before insert and stored in the first-class {@code carrier_id} column (never
 *                        the app-writable {@code metadata} JSONB); backs the F5/F3b per-row carrier
 *                        binding defense (PRD identity-002 §14.6 A9) so a durable identity snapshot
 *                        signed for this row cannot be transplanted onto another; never {@code null}
 * @param aggregateType   optional domain aggregate type that produced the event
 * @param aggregateId     optional identifier of the specific aggregate instance
 * @param eventType       logical event type name (e.g., {@code "order.placed"})
 * @param destination     specific destination address within the destination type
 * @param destinationType category of the destination system
 * @param payload         event payload as read from JSONB storage; destination handlers use
 *                        {@link PayloadCodec#decode(io.vertx.core.json.JsonObject, Class)} to
 *                        deserialize it to the expected type
 * @param headers         application/transport headers only (no framework keys); durable
 *                        propagation context and delivery control live in {@link #metadata()}
 * @param metadata        structured metadata document holding the durable propagation context
 *                        ({@code context}) and delivery control ({@code delivery}); never {@code null}
 * @param scheduledAt     optional earliest wall-clock time at which the entry may be delivered
 * @param availableAt     earliest time at which the entry may be claimed after a failed attempt
 * @param state           current lifecycle state of the entry
 * @param attempt         number of delivery attempts made so far (zero-based)
 * @param maxAttempts     maximum number of delivery attempts allowed before dead-lettering
 * @param claimedAt       time at which the current worker claimed this entry, or {@code null}
 * @param claimedBy       identity of the relay worker currently holding the entry, or {@code null}
 * @param publishedAt     time at which the entry was successfully delivered, or {@code null}
 * @param lastError       human-readable error message from the most recent failed attempt, or {@code null}
 * @param errorType       exception class name from the most recent failed attempt, or {@code null}
 * @param createdAt       time at which the entry was inserted into the outbox table
 * @param updatedAt       time at which the entry was last modified
 */
public record OutboxRecord(
        long id,
        UUID carrierId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String destination,
        DestinationType destinationType,
        Object payload,
        Map<String, String> headers,
        OutboxMetadata metadata,
        Instant scheduledAt,
        Instant availableAt,
        OutboxEntryState state,
        int attempt,
        int maxAttempts,
        Instant claimedAt,
        String claimedBy,
        Instant publishedAt,
        String lastError,
        String errorType,
        Instant createdAt,
        Instant updatedAt) {}
