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
 * An operator command on an outbox entry whose transaction committed, as reported to an
 * {@link OutboxOperationListener}.
 *
 * <p>The event carries no payload, headers, last-error text or message text. Its {@link #toString()} shows the call
 * id, the operation, the entry id and the states, and never the {@link #security() security snapshot}.
 *
 * @param callId     identifies the call; minted once when the command is called, so a failure of the same call carries
 *                   the same id
 * @param operation  the command
 * @param entryId    identifier of the entry
 * @param previous   the state the entry was in when the command ran
 * @param current    the state the entry is in now
 * @param changed    whether the command changed the entry; {@code false} for a discard of an entry that was already
 *                   discarded, which is still reported
 * @param security   the security context bound to the call when it was made, or {@code null} when none was bound; the
 *                   snapshot is taken when the call is made, so rebinding the context later does not change it
 * @param occurredAt when the command was called: stamped together with the snapshot, and not when the transaction
 *                   committed
 */
public record OutboxEntryChange(
        UUID callId,
        OutboxOperationType operation,
        long entryId,
        OutboxEntryState previous,
        OutboxEntryState current,
        boolean changed,
        @Nullable SecurityContextSnapshot security,
        Instant occurredAt) {

    /**
     * Validates the required components.
     *
     * @throws NullPointerException if a component other than {@code security} is {@code null}
     */
    public OutboxEntryChange {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /**
     * Describes the change without the security snapshot.
     *
     * @return the call id, operation, entry id and states
     */
    @Override
    public String toString() {
        return "OutboxEntryChange[callId=" + callId + ", operation=" + operation + ", entryId=" + entryId
                + ", previous=" + previous + ", current=" + current + ", changed=" + changed + "]";
    }
}
