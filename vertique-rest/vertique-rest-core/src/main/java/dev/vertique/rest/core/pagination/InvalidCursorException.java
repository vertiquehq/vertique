// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

import dev.vertique.core.exception.ValidationException;

/**
 * Thrown when a cursor token provided by the client is invalid — malformed, tampered,
 * or expired.
 *
 * <p>Extends {@link ValidationException}, which maps to HTTP 400 Bad Request via the
 * framework's {@code DefaultExceptionMapper}. Applications may register an
 * {@code ExceptionMapper<InvalidCursorException>} to override the status code
 * (e.g. 404 if the cursor semantically means "not found").
 */
public class InvalidCursorException extends ValidationException {

    /**
     * Creates an {@code InvalidCursorException} with the given message.
     *
     * @param message the detail message
     */
    public InvalidCursorException(String message) {
        super(message);
    }

    /**
     * Creates an {@code InvalidCursorException} with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
