// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Unique or primary key constraint violation.
 */
public class UniqueConstraintViolationException extends DataIntegrityViolationException {

    /**
     * Constructs a new unique constraint violation exception with the given message.
     *
     * @param message the detail message
     */
    public UniqueConstraintViolationException(String message) {
        super(message);
    }

    /**
     * Constructs a new unique constraint violation exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public UniqueConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new unique constraint violation exception with the given message, cause, and SQL
     * state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "23505"})
     */
    public UniqueConstraintViolationException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }

    /**
     * Constructs a new unique constraint violation exception with all context fields.
     *
     * @param message        the detail message
     * @param cause          the underlying cause
     * @param sqlState       the SQL state code (e.g., {@code "23505"}), or {@code null}
     * @param constraintName the constraint name (e.g., {@code "uk_users_email"}), or {@code null}
     * @param tableName      the table name, or {@code null}
     */
    public UniqueConstraintViolationException(
            String message, Throwable cause, String sqlState, String constraintName, String tableName) {
        super(message, cause, sqlState, constraintName, tableName);
    }
}
