// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.ops;

import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.OutboxEntryState;

/**
 * Thrown when an operator command finds the entry in a different state than the caller expected.
 *
 * <p>{@link #current()} is the state the entry is in now, so a caller can show it or decide that the command is
 * moot. The message is fixed text: it contains no SQL, driver text or payload data.
 */
public class OutboxEntryStateException extends ConflictException {

    private static final long serialVersionUID = 1L;

    private final long entryId;
    private final OutboxEntryState expected;
    private final OutboxEntryState current;

    /**
     * Creates the exception.
     *
     * @param entryId  identifier of the entry
     * @param expected the state the caller expected
     * @param current  the state the entry is in
     * @throws ValidationException if a state is {@code null}
     */
    public OutboxEntryStateException(long entryId, OutboxEntryState expected, OutboxEntryState current) {
        super("The outbox entry is not in the expected state");
        if (expected == null || current == null) {
            throw new ValidationException("expected and current must not be null");
        }
        this.entryId = entryId;
        this.expected = expected;
        this.current = current;
    }

    /**
     * Returns the identifier of the entry.
     *
     * @return the entry ID
     */
    public long entryId() {
        return entryId;
    }

    /**
     * Returns the state the caller expected.
     *
     * @return the expected state
     */
    public OutboxEntryState expected() {
        return expected;
    }

    /**
     * Returns the state the entry is in now.
     *
     * @return the current state
     */
    public OutboxEntryState current() {
        return current;
    }
}
