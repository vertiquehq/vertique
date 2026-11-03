// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.time.Instant;

/**
 * Keyset position in the dead-letter listing, ordered by failure time then entry ID, both descending.
 *
 * <p>This is the plain sort tuple. Encoding it into an opaque cursor, signing it, and binding it to a
 * filter set belong to the caller.
 *
 * @param failedAt time the entry was dead-lettered; never {@code null}
 * @param entryId  identifier of the entry; positive
 */
public record OutboxDeadLetterKey(Instant failedAt, long entryId) {

    /**
     * Creates a key.
     *
     * @param failedAt time the entry was dead-lettered; never {@code null}
     * @param entryId  identifier of the entry; positive
     * @throws dev.vertique.core.exception.ValidationException if {@code failedAt} is absent or
     *         {@code entryId} is not positive
     */
    public OutboxDeadLetterKey {
        AdminChecks.required(failedAt, "failedAt");
        AdminChecks.positive(entryId, "entryId");
    }
}
