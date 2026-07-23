// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Foreign key constraint violation.
 */
public class ForeignKeyViolationException extends DataIntegrityViolationException {

    /**
     * Constructs a new foreign key violation exception with the given message.
     *
     * @param message the detail message
     */
    public ForeignKeyViolationException(String message) {
        super(message);
    }

    /**
     * Constructs a new foreign key violation exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ForeignKeyViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new foreign key violation exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "23503"})
     */
    public ForeignKeyViolationException(String message, Throwable cause, String sqlState) {
        super(message, cause, sqlState);
    }

    /**
     * Constructs a new foreign key violation exception with all context fields.
     *
     * @param message        the detail message
     * @param cause          the underlying cause
     * @param sqlState       the SQL state code (e.g., {@code "23503"}), or {@code null}
     * @param constraintName the constraint name, or {@code null}
     * @param tableName      the table name, or {@code null}
     */
    public ForeignKeyViolationException(
            String message, Throwable cause, String sqlState, String constraintName, String tableName) {
        super(message, cause, sqlState, constraintName, tableName);
    }
}
