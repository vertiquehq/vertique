// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Authentication is missing or invalid. Maps to HTTP 401.
 *
 * <p>Throw this when the request lacks valid credentials — the client should authenticate
 * and retry. Use {@link ForbiddenException} (403) when the authenticated principal is
 * known but lacks permission.
 */
public class UnauthorizedException extends VertiqueSecurityException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public UnauthorizedException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
