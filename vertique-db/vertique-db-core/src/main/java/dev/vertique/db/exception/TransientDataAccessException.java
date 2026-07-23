// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Base class for transient (retry-safe) data access failures. Subclasses represent conditions that
 * may succeed on retry, such as connection timeouts, deadlocks, or pool exhaustion.
 *
 * <p>Retry/circuit-breaker logic can catch this type:
 *
 * <pre>{@code
 * catch (TransientDataAccessException e) { retry(); }
 * }</pre>
 */
public class TransientDataAccessException extends DataAccessException {

    /**
     * Constructs a new transient exception with the given message.
     *
     * @param message the detail message
     */
    public TransientDataAccessException(String message) {
        super(message);
    }

    /**
     * Constructs a new transient exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public TransientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new transient exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "08001"})
     */
    public TransientDataAccessException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }
}
