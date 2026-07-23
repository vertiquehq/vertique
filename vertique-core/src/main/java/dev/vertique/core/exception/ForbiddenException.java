// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * The authenticated principal is not permitted to perform the operation. Maps to HTTP 403.
 *
 * <p>Throw this when the caller's identity is known (authentication succeeded) but they
 * lack the required permission or role. Use {@link UnauthorizedException} (401) when
 * authentication itself is missing or invalid.
 */
public class ForbiddenException extends VertiqueSecurityException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public ForbiddenException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
