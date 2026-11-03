// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.inboxoutbox.DestinationType;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One recorded administrative command on an outbox entry.
 *
 * <p>An entry holds the receipt fields, the acting identity and the reason, the failure snapshot taken
 * before the command, and the identifiers of the target. It holds no payload, headers, raw error text
 * or metadata. History outlives the entry: it stays readable after a discard.
 *
 * @param operationId      identifier of the command
 * @param entryId          identifier of the target entry; positive
 * @param action           what was requested
 * @param disposition      what happened to the entry
 * @param previousRevision revision of the entry before the command; not negative
 * @param resultRevision   revision after the command; present for a retry and empty for a discard
 * @param recordedAt       time the command was recorded
 * @param actor            who performed the command
 * @param reason           the reason given, exactly as stored; 1 to 1024 characters
 * @param attempt          delivery attempts made before the command; not negative
 * @param maxAttempts      delivery attempts allowed; not negative
 * @param errorType        exception class name of the last failure before the command, or {@code null}
 * @param destinationType  category of the target's destination
 * @param destination      destination address of the target
 * @param eventType        logical event type of the target
 * @param aggregateType    aggregate type of the target, or {@code null}
 * @param aggregateId      aggregate identifier of the target, or {@code null}
 */
public record OutboxAdminHistoryEntry(
        UUID operationId,
        long entryId,
        OutboxAdminAction action,
        OutboxAdminDisposition disposition,
        long previousRevision,
        OptionalLong resultRevision,
        Instant recordedAt,
        OutboxAdminActor actor,
        String reason,
        int attempt,
        int maxAttempts,
        @Nullable String errorType,
        DestinationType destinationType,
        String destination,
        String eventType,
        @Nullable String aggregateType,
        @Nullable String aggregateId) {

    /**
     * Creates a history entry.
     *
     * @param operationId      identifier of the command
     * @param entryId          identifier of the target entry; positive
     * @param action           what was requested
     * @param disposition      what happened; must match the action
     * @param previousRevision revision before the command; not negative
     * @param resultRevision   revision after a retry, empty after a discard; {@code null} reads as empty
     * @param recordedAt       time the command was recorded
     * @param actor            who performed the command
     * @param reason           the stored reason; 1 to 1024 characters, not all whitespace
     * @param attempt          attempts made before the command; not negative
     * @param maxAttempts      attempts allowed; not negative
     * @param errorType        error type before the command, or {@code null}
     * @param destinationType  destination category of the target
     * @param destination      destination address of the target
     * @param eventType        event type of the target
     * @param aggregateType    aggregate type of the target, or {@code null}
     * @param aggregateId      aggregate identifier of the target, or {@code null}
     * @throws dev.vertique.core.exception.ValidationException if a required component is absent or out of
     *         range, or the action, disposition and result revision are inconsistent
     */
    public OutboxAdminHistoryEntry {
        resultRevision = AdminChecks.optionalLong(resultRevision);
        AdminChecks.required(operationId, "operationId");
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.required(action, "action");
        AdminChecks.required(disposition, "disposition");
        AdminChecks.nonNegative(previousRevision, "previousRevision");
        AdminChecks.required(recordedAt, "recordedAt");
        AdminChecks.required(actor, "actor");
        AdminChecks.text(reason, "reason", OutboxAdminCommand.MAX_REASON_LENGTH);
        AdminChecks.nonNegative(attempt, "attempt");
        AdminChecks.nonNegative(maxAttempts, "maxAttempts");
        AdminChecks.required(destinationType, "destinationType");
        AdminChecks.required(destination, "destination");
        AdminChecks.required(eventType, "eventType");
        AdminChecks.consistent(action, disposition, resultRevision);
    }

    /** Returns a string that carries neither the reason nor the actor. */
    @Override
    public String toString() {
        return "OutboxAdminHistoryEntry[operationId=" + operationId + ", entryId=" + entryId + ", action=" + action
                + ", disposition=" + disposition + ", recordedAt=" + recordedAt + "]";
    }
}
