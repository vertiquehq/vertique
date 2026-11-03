// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.util.UUID;

/**
 * A retry or discard request for one dead-lettered outbox entry.
 *
 * <p>The canonical intent of a command is the action, the entry ID, the expected revision, the exact
 * reason and the actor. A command repeated with the same {@code operationId} and the same intent is
 * answered with the original receipt; the same {@code operationId} with a different intent is a
 * conflict.
 *
 * @param operationId      caller-chosen identifier that makes the command idempotent; never {@code null}
 * @param entryId          identifier of the target entry; positive
 * @param expectedRevision revision of the entry the caller last saw; not negative
 * @param reason           why the operation is performed, stored and compared exactly as given; 1 to 1024
 *                         characters, not all whitespace
 * @param actor            the identity performing the operation; never {@code null}
 */
public record OutboxAdminCommand(
        UUID operationId, long entryId, long expectedRevision, String reason, OutboxAdminActor actor) {

    /** Maximum length of {@link #reason()} in characters. */
    public static final int MAX_REASON_LENGTH = 1024;

    /**
     * Creates a command.
     *
     * @param operationId      caller-chosen idempotency identifier; never {@code null}
     * @param entryId          identifier of the target entry; positive
     * @param expectedRevision revision the caller last saw; not negative
     * @param reason           1 to 1024 characters, not all whitespace
     * @param actor            the identity performing the operation; never {@code null}
     * @throws dev.vertique.core.exception.ValidationException if any component is outside its bound
     */
    public OutboxAdminCommand {
        AdminChecks.required(operationId, "operationId");
        AdminChecks.positive(entryId, "entryId");
        AdminChecks.nonNegative(expectedRevision, "expectedRevision");
        AdminChecks.text(reason, "reason", MAX_REASON_LENGTH);
        AdminChecks.required(actor, "actor");
    }

    /** Returns a string that carries neither the reason nor the actor. */
    @Override
    public String toString() {
        return "OutboxAdminCommand[operationId=" + operationId + ", entryId=" + entryId + ", expectedRevision="
                + expectedRevision + "]";
    }
}
