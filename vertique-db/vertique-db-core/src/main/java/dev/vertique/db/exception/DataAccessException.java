// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

import dev.vertique.core.exception.TechnicalException;

/**
 * Base exception for data access failures. All database exceptions in the framework extend this
 * class, enabling uniform catch-all handling.
 *
 * <p>Context fields ({@link #sqlState()}, {@link #constraintName()}, {@link #tableName()}) are set
 * by vendor-specific translators when available and are {@code null} by default.
 *
 * <p>Modeled after Spring's {@code DataAccessException} with transient/non-transient
 * categorization via intermediate subclasses.
 *
 * <p>Extends {@link TechnicalException} and maps to HTTP 500 by default.
 */
public class DataAccessException extends TechnicalException {

    private final String sqlState;
    private final String constraintName;
    private final String tableName;

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public DataAccessException(String message) {
        this(message, null, null, null, null);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public DataAccessException(String message, Throwable cause) {
        this(message, cause, null, null, null);
    }

    /**
     * Constructs a new exception with the given message, cause, and SQL state.
     *
     * @param message  the detail message
     * @param cause    the underlying cause
     * @param sqlState the SQL state code (e.g., {@code "23505"})
     */
    public DataAccessException(String message, Throwable cause, String sqlState) {
        this(message, cause, sqlState, null, null);
    }

    /**
     * Constructs a new exception with all context fields.
     *
     * @param message        the detail message
     * @param cause          the underlying cause
     * @param sqlState       the SQL state code (e.g., {@code "23505"}), or {@code null}
     * @param constraintName the constraint name (e.g., {@code "uk_users_email"}), or {@code null}
     * @param tableName      the table name, or {@code null}
     */
    public DataAccessException(
            String message, Throwable cause, String sqlState, String constraintName, String tableName) {
        super(message, cause);
        this.sqlState = sqlState;
        this.constraintName = constraintName;
        this.tableName = tableName;
    }

    /**
     * Returns the SQL state code (e.g., {@code "23505"}), or {@code null} if not available.
     *
     * @return the SQL state code, or {@code null}
     */
    public String sqlState() {
        return sqlState;
    }

    /**
     * Returns the constraint name (e.g., {@code "uk_users_email"}), or {@code null} if not
     * available.
     *
     * @return the constraint name, or {@code null}
     */
    public String constraintName() {
        return constraintName;
    }

    /**
     * Returns the table name, or {@code null} if not available.
     *
     * @return the table name, or {@code null}
     */
    public String tableName() {
        return tableName;
    }
}
