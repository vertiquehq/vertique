// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Base class for concurrent modification failures. Covers optimistic and pessimistic locking
 * failures.
 */
public class ConcurrencyFailureException extends DataAccessException {

    /**
     * Constructs a new concurrency failure exception with the given message.
     *
     * @param message the detail message
     */
    public ConcurrencyFailureException(String message) {
        super(message);
    }

    /**
     * Constructs a new concurrency failure exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ConcurrencyFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new concurrency failure exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "40001"})
     */
    public ConcurrencyFailureException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
