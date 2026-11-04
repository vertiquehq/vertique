// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Everything the outbox records about one entry except its content: scheduling, the relay lease and the last
 * failure. It answers why an entry has not moved.
 *
 * <p>The last error is read from the database at most {@value #MAX_LAST_ERROR_LENGTH} characters long;
 * {@link #lastErrorTruncated()} says whether there was more. The payload and headers are in
 * {@link OutboxEntryPayload}, and the scope key and carrier metadata are never returned.
 *
 * @param entryId            identifier of the entry; positive
 * @param state              the entry's current state
 * @param destinationType    the adapter the relay hands the entry to
 * @param destination        the stable target within that adapter
 * @param eventType          the event or message type
 * @param aggregateType      the logical aggregate type, or {@code null}
 * @param aggregateId        the aggregate instance id, or {@code null}
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
 * @param lastError          the last failure message, cut at {@value #MAX_LAST_ERROR_LENGTH} characters, or
 *                           {@code null}
 * @param lastErrorTruncated whether the stored last error was longer than what is returned
 */
public record OutboxEntryDetail(
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
        Instant updatedAt,
        Instant availableAt,
        @Nullable Instant scheduledAt,
        @Nullable Instant publishedAt,
        @Nullable Instant claimedAt,
        @Nullable String claimedBy,
        @Nullable String lastError,
        boolean lastErrorTruncated) {

    /** Longest last error the detail returns, in characters. */
    public static final int MAX_LAST_ERROR_LENGTH = 4096;

    /**
     * Creates a validated detail.
     *
     * @throws dev.vertique.core.exception.ValidationException if a required component is absent, a number is out
     *                                                         of range or the last error is too long
     */
    public OutboxEntryDetail {
        QueryChecks.positive(entryId, "entryId");
        QueryChecks.required(state, "state");
        QueryChecks.required(destinationType, "destinationType");
        QueryChecks.required(destination, "destination");
        QueryChecks.required(eventType, "eventType");
        QueryChecks.nonNegative(attempt, "attempt");
        QueryChecks.nonNegative(maxAttempts, "maxAttempts");
        QueryChecks.required(createdAt, "createdAt");
        QueryChecks.required(updatedAt, "updatedAt");
        QueryChecks.required(availableAt, "availableAt");
        if (lastError != null && lastError.codePointCount(0, lastError.length()) > MAX_LAST_ERROR_LENGTH) {
            throw new ValidationException("lastError must be at most " + MAX_LAST_ERROR_LENGTH + " characters");
        }
    }
}
