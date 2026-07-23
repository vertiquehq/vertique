// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Optimistic locking failure: version conflict or serialization failure (SQL state {@code 40001}).
 */
public class OptimisticLockingFailureException extends ConcurrencyFailureException {

    /**
     * Constructs a new optimistic locking failure exception with the given message.
     *
     * @param message the detail message
     */
    public OptimisticLockingFailureException(String message) {
        super(message);
    }

    /**
     * Constructs a new optimistic locking failure exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public OptimisticLockingFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new optimistic locking failure exception with the given message, cause, and SQL
     * state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "40001"})
     */
    public OptimisticLockingFailureException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
