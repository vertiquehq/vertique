// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.core.exception.NotFoundException;

/**
 * Thrown when an administrative command targets an outbox entry that does not exist.
 *
 * <p>The message names the entry ID only: it contains no SQL, payload, reason or actor data.
 */
public class OutboxDeadLetterNotFoundException extends NotFoundException {

    private static final long serialVersionUID = 1L;

    private final long entryId;

    /**
     * Creates the exception.
     *
     * @param entryId identifier of the entry that was not found
     */
    public OutboxDeadLetterNotFoundException(long entryId) {
        super("Outbox entry " + entryId + " was not found");
        this.entryId = entryId;
    }

    /**
     * Returns the identifier of the entry that was not found.
     *
     * @return the entry ID
     */
    public long entryId() {
        return entryId;
    }
}
