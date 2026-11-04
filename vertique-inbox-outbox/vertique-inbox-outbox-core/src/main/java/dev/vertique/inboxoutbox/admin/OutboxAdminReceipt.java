// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Proof that an administrative command committed.
 *
 * <p>A receipt is returned only after the state change and the operation record are committed
 * together. A command repeated with the same operation ID and intent returns the original receipt.
 *
 * @param operationId      identifier of the command
 * @param entryId          identifier of the target entry; positive
 * @param action           what was requested
 * @param disposition      what happened to the entry; {@link OutboxAdminDisposition#REQUEUED} for
 *                         {@link OutboxAdminAction#RETRY}, {@link OutboxAdminDisposition#DISCARDED} for
 *                         {@link OutboxAdminAction#DISCARD}
 * @param previousRevision revision of the entry before the command; not negative
 * @param resultRevision   revision after the command; present for a retry and empty for a discard
 * @param recordedAt       time the command was recorded
 */
public record OutboxAdminReceipt(
        UUID operationId,
        long entryId,
        OutboxAdminAction action,
        OutboxAdminDisposition disposition,
        long previousRevision,
        OptionalLong resultRevision,
        Instant recordedAt) {

    /**
     * Creates a receipt.
     *
     * @param operationId      identifier of the command
     * @param entryId          identifier of the target entry; positive
     * @param action           what was requested
     * @param disposition      what happened; must match the action
     * @param previousRevision revision before the command; not negative
     * @param resultRevision   revision after a retry, empty after a discard; {@code null} reads as empty
     * @param recordedAt       time the command was recorded
     * @throws dev.vertique.core.exception.ValidationException if a component is absent or out of range,
     *         the disposition does not match the action, or the result revision is present for a discard
     *         or absent for a retry
     */
    public OutboxAdminReceipt {
        resultRevision = AdminChecks.optionalLong(resultRevision, "resultRevision");
        AdminChecks.required(operationId, "operationId");
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.required(action, "action");
        AdminChecks.required(disposition, "disposition");
        AdminChecks.nonNegative(previousRevision, "previousRevision");
        AdminChecks.required(recordedAt, "recordedAt");
        AdminChecks.consistent(action, disposition, resultRevision);
    }
}
