// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.util.UUID;

/**
 * Delivery-time relay control values that identify the outbox entry for message-ID tracking
 * and aggregate correlation at relay time.
 *
 * <p>These values are projected by the relay from the outbox row columns when building an
 * {@link OutboxEnvelope} ({@link OutboxDeliveryMetadata#outbox()}); they are NOT persisted in the
 * {@code metadata} JSONB and are NOT emitted as record headers — they are internal relay/adapter
 * control surfaced only on the in-memory envelope.
 *
 * @param messageId     surrogate primary key of the originating outbox entry
 * @param carrierId     the first-class {@code carrier_id} column value, projected verbatim from the
 *                      row (PRD identity-002 F3b); consuming destination handlers use this — never a
 *                      value read from the app-writable {@link OutboxEnvelope#metadata()} — to
 *                      reproduce the exact durable row-carrier the entry was signed for at publish
 *                      time; never {@code null}
 * @param eventType     logical event type name (e.g., {@code "order.placed"})
 * @param aggregateType optional domain aggregate type that produced the event, or {@code null}
 * @param aggregateId   optional identifier of the specific aggregate instance, or {@code null}
 */
public record OutboxRelayControl(
        long messageId, UUID carrierId, String eventType, String aggregateType, String aggregateId) {}
