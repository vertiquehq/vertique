// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Pessimistic locking failure: lock acquisition timeout or lock wait timeout exceeded.
 */
public class PessimisticLockingFailureException extends ConcurrencyFailureException {

    /**
     * Constructs a new pessimistic locking failure exception with the given message.
     *
     * @param message the detail message
     */
    public PessimisticLockingFailureException(String message) {
        super(message);
    }

    /**
     * Constructs a new pessimistic locking failure exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public PessimisticLockingFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new pessimistic locking failure exception with the given message, cause, and SQL
     * state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "55P03"})
     */
    public PessimisticLockingFailureException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
