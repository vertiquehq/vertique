// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.exception;

/**
 * Thrown when an inbox or outbox operation fails because of a persistence-layer error.
 *
 * <p>This is the inbox/outbox-domain projection of a database failure that crossed the
 * transactional-messaging API boundary. The exception mapper in the
 * {@code vertique-inbox-outbox-postgresql} module translates
 * {@link dev.vertique.db.exception.DataAccessException} subtypes into this exception so that
 * callers of the inbox/outbox service API see inbox/outbox exceptions, not data-access exceptions.
 *
 * <p>The {@link #retryable()} flag tells callers whether re-attempting the same operation has a
 * reasonable chance of succeeding. Transient infrastructure failures (deadlock, lock timeout,
 * optimistic/pessimistic locking) are retryable; a generic data-access failure is not, by default.
 */
public class InboxOutboxPersistenceException extends InboxOutboxTechnicalException {

    private final boolean retryable;

    /**
     * Creates a new {@code InboxOutboxPersistenceException}.
     *
     * @param message   human-readable description of the persistence failure
     * @param cause     the underlying persistence-layer exception that caused this error
     * @param retryable {@code true} if re-attempting the failed operation may succeed (transient
     *                  infrastructure failure); {@code false} for a non-transient failure
     */
    public InboxOutboxPersistenceException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns whether re-attempting the failed operation may succeed.
     *
     * @return {@code true} for a transient infrastructure failure (deadlock, lock timeout,
     *         optimistic/pessimistic locking); {@code false} for a non-transient failure
     */
    public boolean retryable() {
        return retryable;
    }
}
