// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Deadlock detected. The database aborted the transaction to resolve the deadlock. Retrying the
 * entire transaction may succeed.
 */
public class DeadlockException extends TransientDataAccessException {

    /**
     * Constructs a new deadlock exception with the given message.
     *
     * @param message the detail message
     */
    public DeadlockException(String message) {
        super(message);
    }

    /**
     * Constructs a new deadlock exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public DeadlockException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new deadlock exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "40P01"})
     */
    public DeadlockException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
