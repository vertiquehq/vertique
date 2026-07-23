// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Base exception for input validation failures. Maps to HTTP 400 by default.
 *
 * <p>Subclass for domain-specific validation (e.g., {@code DbValidationException}).
 * For REST-layer validation with structured per-field error details, use
 * {@code RestValidationException} from the {@code rest-core} module instead.
 */
public class ValidationException extends VertiqueException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public ValidationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and underlying cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
