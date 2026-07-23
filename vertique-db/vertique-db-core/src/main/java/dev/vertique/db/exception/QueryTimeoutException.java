// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Query or statement execution timed out.
 */
public class QueryTimeoutException extends TransientDataAccessException {

    /**
     * Constructs a new query timeout exception with the given message.
     *
     * @param message the detail message
     */
    public QueryTimeoutException(String message) {
        super(message);
    }

    /**
     * Constructs a new query timeout exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public QueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new query timeout exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "57014"})
     */
    public QueryTimeoutException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
