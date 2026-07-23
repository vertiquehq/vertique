// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Thrown when a required dependency or service is unavailable. Maps to HTTP 503 by default.
 *
 * <p>Represents a transient condition — the dependency may become available later.
 */
public class UnavailableException extends TechnicalException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public UnavailableException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
