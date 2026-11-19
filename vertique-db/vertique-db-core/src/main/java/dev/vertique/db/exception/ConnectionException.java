// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Connection failure: the database could not be reached or the connection was lost. Covers
 * network-level failures that are not database errors (connection refused, connection reset or
 * closed, connect timeout) and database errors in SQL state class {@code 08} and {@code 57P01} to
 * {@code 57P04}. Exhausting the pool is not a connection failure: the wait for a pooled connection
 * that times out surfaces as {@link QueryTimeoutException}.
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
