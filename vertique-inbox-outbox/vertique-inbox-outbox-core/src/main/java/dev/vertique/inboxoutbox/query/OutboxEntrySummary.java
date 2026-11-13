// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * One row of an outbox entry listing: what the entry is, where it is going and how far it got.
 *
 * <p>It carries no payload, headers, metadata or scope key, and the error text of a failed entry is only in
 * {@link OutboxEntryDetail}.
 *
 * @param entryId         identifier of the entry; positive
 * @param state           the entry's current state
 * @param destinationType the adapter the relay hands the entry to
 * @param destination     the stable target within that adapter
 * @param eventType       the event or message type
 * @param aggregateType   the logical aggregate type, or {@code null}
 * @param aggregateId     the aggregate instance id, or {@code null}
 * @param attempt         how many delivery attempts have failed; not negative
 * @param maxAttempts     the attempt budget the relay stops at; not negative
 * @param errorType       the class name of the last failure's cause, or {@code null}
 * @param createdAt       when the entry was recorded
 * @param updatedAt       when the entry entered its current state
 */
public record OutboxEntrySummary(
        long entryId,
        OutboxEntryState state,
        DestinationType destinationType,
        String destination,
        String eventType,
        @Nullable String aggregateType,
        @Nullable String aggregateId,
        int attempt,
        int maxAttempts,
        @Nullable String errorType,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Creates a validated summary.
     *
     * @throws dev.vertique.core.exception.ValidationException if a required component is absent or a number is
     *                                                         out of range
     */
    public OutboxEntrySummary {
        QueryChecks.positive(entryId, "entryId");
        QueryChecks.required(state, "state");
        QueryChecks.required(destinationType, "destinationType");
        QueryChecks.required(destination, "destination");
        QueryChecks.required(eventType, "eventType");
        QueryChecks.nonNegative(attempt, "attempt");
        QueryChecks.nonNegative(maxAttempts, "maxAttempts");
        QueryChecks.required(createdAt, "createdAt");
        QueryChecks.required(updatedAt, "updatedAt");
    }
}
