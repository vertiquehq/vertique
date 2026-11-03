// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.inboxoutbox.DestinationType;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Narrow listing view of one dead-lettered outbox entry.
 *
 * <p>A summary never carries the payload, the headers or any propagation metadata, and never the
 * entry's scope key.
 *
 * @param entryId         identifier of the entry; positive
 * @param revision        administration revision of the entry; not negative. Pass it back as the
 *                        expected revision of a command
 * @param destinationType category of the destination
 * @param destination     destination address
 * @param eventType       logical event type
 * @param aggregateType   aggregate type, or {@code null} when none was recorded
 * @param aggregateId     aggregate identifier, or {@code null} when none was recorded
 * @param attempt         delivery attempts made; not negative
 * @param maxAttempts     delivery attempts allowed; not negative
 * @param errorType       exception class name of the last failure, or {@code null} when none
 * @param createdAt       time the entry was recorded
 * @param failedAt        time the entry was dead-lettered
 */
public record OutboxDeadLetterSummary(
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
        Instant failedAt) {

    /**
     * Creates a summary.
     *
     * @param entryId         identifier of the entry; positive
     * @param revision        administration revision; not negative
     * @param destinationType category of the destination
     * @param destination     destination address
     * @param eventType       logical event type
     * @param aggregateType   aggregate type, or {@code null}
     * @param aggregateId     aggregate identifier, or {@code null}
     * @param attempt         delivery attempts made; not negative
     * @param maxAttempts     delivery attempts allowed; not negative
     * @param errorType       error type, or {@code null}
     * @param createdAt       creation time
     * @param failedAt        dead-letter time
     * @throws dev.vertique.core.exception.ValidationException if a required component is absent or a
     *         number is out of range
     */
    public OutboxDeadLetterSummary {
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.nonNegative(revision, "revision");
        AdminChecks.required(destinationType, "destinationType");
        AdminChecks.required(destination, "destination");
        AdminChecks.required(eventType, "eventType");
        AdminChecks.nonNegative(attempt, "attempt");
        AdminChecks.nonNegative(maxAttempts, "maxAttempts");
        AdminChecks.required(createdAt, "createdAt");
        AdminChecks.required(failedAt, "failedAt");
    }
}
