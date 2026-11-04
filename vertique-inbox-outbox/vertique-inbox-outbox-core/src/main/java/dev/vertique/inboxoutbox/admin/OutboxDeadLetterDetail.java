// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.inboxoutbox.DestinationType;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Optional;

/**
 * Detail view of one dead-lettered outbox entry: the summary fields plus scheduling and the last error.
 *
 * <p>The payload, the headers, the propagation metadata and the scope key are never part of the detail;
 * the payload and application headers are available only through {@link OutboxDeadLetterContent}.
 *
 * @param entryId            identifier of the entry; positive
 * @param revision           administration revision of the entry; not negative
 * @param destinationType    category of the destination
 * @param destination        destination address
 * @param eventType          logical event type
 * @param aggregateType      aggregate type, or {@code null} when none was recorded
 * @param aggregateId        aggregate identifier, or {@code null} when none was recorded
 * @param attempt            delivery attempts made; not negative
 * @param maxAttempts        delivery attempts allowed; not negative
 * @param errorType          exception class name of the last failure, or {@code null} when none
 * @param createdAt          time the entry was recorded
 * @param failedAt           time the entry was dead-lettered
 * @param availableAt        earliest time the entry could next be claimed
 * @param scheduledAt        earliest delivery time requested when the entry was published, if any
 * @param lastError          message of the last failure, or {@code null} when none; at most 4096
 *                           characters
 * @param lastErrorTruncated {@code true} when {@code lastError} was cut to fit the 4096-character limit
 */
public record OutboxDeadLetterDetail(
        long entryId,
        long revision,
        DestinationType destinationType,
        String destination,
        String eventType,
        @Nullable String aggregateType,
        @Nullable String aggregateId,
        int attempt,
        int maxAttempts,
        @Nullable String errorType,
        Instant createdAt,
        Instant failedAt,
        Instant availableAt,
        Optional<Instant> scheduledAt,
        @Nullable String lastError,
        boolean lastErrorTruncated) {

    /** Maximum length of {@link #lastError()} in characters. */
    public static final int MAX_LAST_ERROR_LENGTH = 4096;

    /**
     * Creates a detail.
     *
     * @param entryId            identifier of the entry; positive
     * @param revision           administration revision; not negative
     * @param destinationType    category of the destination
     * @param destination        destination address
     * @param eventType          logical event type
     * @param aggregateType      aggregate type, or {@code null}
     * @param aggregateId        aggregate identifier, or {@code null}
     * @param attempt            delivery attempts made; not negative
     * @param maxAttempts        delivery attempts allowed; not negative
     * @param errorType          error type, or {@code null}
     * @param createdAt          creation time
     * @param failedAt           dead-letter time
     * @param availableAt        earliest next claim time
     * @param scheduledAt        requested delivery time; {@code null} reads as empty
     * @param lastError          last failure message, or {@code null}; at most 4096 characters
     * @param lastErrorTruncated whether {@code lastError} was truncated
     * @throws dev.vertique.core.exception.ValidationException if a required component is absent, a
     *         number is out of range or {@code lastError} is longer than 4096 characters
     */
    public OutboxDeadLetterDetail {
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.nonNegative(revision, "revision");
        AdminChecks.required(destinationType, "destinationType");
        AdminChecks.required(destination, "destination");
        AdminChecks.required(eventType, "eventType");
        AdminChecks.nonNegative(attempt, "attempt");
        AdminChecks.nonNegative(maxAttempts, "maxAttempts");
        AdminChecks.required(createdAt, "createdAt");
        AdminChecks.required(failedAt, "failedAt");
        AdminChecks.required(availableAt, "availableAt");
        scheduledAt = AdminChecks.optional(scheduledAt, "scheduledAt");
        if (lastError != null) {
            AdminChecks.bounded(lastError, "lastError", MAX_LAST_ERROR_LENGTH);
        }
    }
}
