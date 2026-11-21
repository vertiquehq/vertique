// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.core.scope.ScopeKey;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Everything the outbox records about one entry except its content: scheduling and the relay lease. It answers where
 * an entry is and when it can move.
 *
 * <p>The application's last error text can quote payload fragments, so it is not here: it is returned with the
 * payload and headers in {@link OutboxEntryPayload}, the read that needs the higher privilege. Only {@code errorType},
 * the class name of the last failure's cause, is returned here. Carrier metadata is never returned. The scope key is
 * returned as a label.
 *
 * @param entryId            identifier of the entry; positive
 * @param state              the entry's current state
 * @param destinationType    the adapter the relay hands the entry to
 * @param destination        the stable target within that adapter
 * @param eventType          the event or message type
 * @param aggregateType      the logical aggregate type, or {@code null}
 * @param aggregateId        the aggregate instance id, or {@code null}
 * @param scopeKey           the scope label the entry was published under; {@code default} for an entry that
 *                           selected none, never {@code null}
 * @param attempt            how many delivery attempts have failed; not negative
 * @param maxAttempts        the attempt budget the relay stops at; not negative
 * @param errorType          the class name of the last failure's cause, or {@code null}
 * @param createdAt          when the entry was recorded
 * @param updatedAt          when the entry entered its current state
 * @param availableAt        the earliest time the relay may claim the entry
 * @param scheduledAt        the time after which the application asked for delivery, or {@code null}
 * @param publishedAt        when the entry was delivered, or {@code null}
 * @param claimedAt          when the relay took its current lease, or {@code null} when it holds none
 * @param claimedBy          the relay node that holds the lease, or {@code null} when none does
 */
public record OutboxEntryDetail(
        long entryId,
        OutboxEntryState state,
        DestinationType destinationType,
        String destination,
        String eventType,
        @Nullable String aggregateType,
        @Nullable String aggregateId,
        String scopeKey,
        int attempt,
        int maxAttempts,
        @Nullable String errorType,
        Instant createdAt,
        Instant updatedAt,
        Instant availableAt,
        @Nullable Instant scheduledAt,
        @Nullable Instant publishedAt,
        @Nullable Instant claimedAt,
        @Nullable String claimedBy) {

    /**
     * Creates a validated detail.
     *
     * @throws dev.vertique.core.exception.ValidationException if a required component is absent or a number is out
     *                                                         of range
     */
    public OutboxEntryDetail {
        QueryChecks.positive(entryId, "entryId");
        QueryChecks.required(state, "state");
        QueryChecks.required(destinationType, "destinationType");
        QueryChecks.required(destination, "destination");
        QueryChecks.required(eventType, "eventType");
        scopeKey = ScopeKey.orDefault(scopeKey);
        QueryChecks.nonNegative(attempt, "attempt");
        QueryChecks.nonNegative(maxAttempts, "maxAttempts");
        QueryChecks.required(createdAt, "createdAt");
        QueryChecks.required(updatedAt, "updatedAt");
        QueryChecks.required(availableAt, "availableAt");
    }

    /** Returns a string with the entry id and state only, so no entry text can leak through logging. */
    @Override
    public String toString() {
        return "OutboxEntryDetail[entryId=" + entryId + ", state=" + state + "]";
    }
}
