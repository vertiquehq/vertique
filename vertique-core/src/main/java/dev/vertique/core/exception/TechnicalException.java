// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Base exception for runtime technical failures. Maps to HTTP 500 by default.
 *
 * <p>Covers infrastructure-level problems like database errors, network failures,
 * or internal processing errors that are not caused by invalid input.
 */
public class TechnicalException extends VertiqueException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public TechnicalException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public TechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
