// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Connection failure: unable to acquire a connection from the pool, pool exhaustion, or
 * network-level connection errors.
 */
public class ConnectionException extends TransientDataAccessException {

    /**
     * Constructs a new connection exception with the given message.
     *
     * @param message the detail message
     */
    public ConnectionException(String message) {
        super(message);
    }

    /**
     * Constructs a new connection exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new connection exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "08001"})
     */
    public ConnectionException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
