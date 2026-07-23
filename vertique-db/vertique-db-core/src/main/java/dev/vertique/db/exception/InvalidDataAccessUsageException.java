// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Programming error in data access code: invalid SQL syntax, insufficient permissions, or other
 * non-transient usage errors.
 */
public class InvalidDataAccessUsageException extends DataAccessException {

    /**
     * Constructs a new invalid data access usage exception with the given message.
     *
     * @param message the detail message
     */
    public InvalidDataAccessUsageException(String message) {
        super(message);
    }

    /**
     * Constructs a new invalid data access usage exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public InvalidDataAccessUsageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new invalid data access usage exception with the given message, cause, and SQL
     * state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "42601"})
     */
    public InvalidDataAccessUsageException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
