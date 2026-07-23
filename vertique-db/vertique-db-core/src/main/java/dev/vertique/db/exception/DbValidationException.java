// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

import dev.vertique.core.exception.ValidationException;

/**
 * Base exception for database-related validation failures. Maps to HTTP 400 by default.
 */
public class DbValidationException extends ValidationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public DbValidationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public DbValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
