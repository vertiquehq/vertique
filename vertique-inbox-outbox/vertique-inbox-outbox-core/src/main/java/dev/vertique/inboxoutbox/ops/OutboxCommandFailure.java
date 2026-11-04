// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.security.SecurityContextSnapshot;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator command on an outbox entry that failed, as reported to an {@link OutboxOperationListener}: it was
 * refused as invalid, found no entry, found the entry in another state than expected, hit a persistence failure, or
 * its commit failed or could not be confirmed.
 *
 * <p>For a persistence failure the outcome of a commit that was lost on the way may be unknown: the command can have
 * been applied even though the caller received a failure. The entry's state in the table is the source of truth.
 *
 * <p>The event carries no payload, headers, last-error text or message text, and not the failure's message. Its
 * {@link #toString()} shows the call id, the operation, the entry id, the expected state and the error type, and never
 * the {@link #security() security snapshot}.
 *
 * @param callId        identifies the call; the same id the committed report of the call would have carried
 * @param operation     the command
 * @param entryId       identifier of the entry as the caller passed it; not necessarily positive when the call was
 *                      refused as invalid
 * @param expectedState the state the caller said it saw; {@code null} only when the caller passed {@code null}, which
 *                      the command refuses as invalid
 * @param errorType     the simple class name of the failure the caller receives, after translation, and never its
 *                      message; {@code null} only when that class has no simple name
 * @param security      the security context bound to the call when it was made, or {@code null} when none was bound
 * @param occurredAt    when the failure was observed
 */
public record OutboxCommandFailure(
        UUID callId,
        OutboxOperationType operation,
        long entryId,
        @Nullable OutboxEntryState expectedState,
        @Nullable String errorType,
        @Nullable SecurityContextSnapshot security,
        Instant occurredAt) {

    /**
     * Validates the required components.
     *
     * @throws NullPointerException if {@code callId}, {@code operation} or {@code occurredAt} is {@code null}
     */
    public OutboxCommandFailure {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /**
     * Describes the failure without the security snapshot.
     *
     * @return the call id, operation, entry id, expected state and error type
     */
    @Override
    public String toString() {
        return "OutboxCommandFailure[callId=" + callId + ", operation=" + operation + ", entryId=" + entryId
                + ", expectedState=" + expectedState + ", errorType=" + errorType + "]";
    }
}
