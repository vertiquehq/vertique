// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Grouping root for security-related failures (authentication / authorization).
 *
 * <p>NOT thrown directly and has NO default REST status mapping — throw
 * {@link UnauthorizedException} (401) or {@link ForbiddenException} (403), or a
 * module-specific subtype. A bare instance reaching REST falls to 500.
 *
 * <p>Named {@code VertiqueSecurityException} rather than {@code SecurityException} to
 * avoid a collision with {@link java.lang.SecurityException}.
 */
public class VertiqueSecurityException extends VertiqueException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public VertiqueSecurityException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public VertiqueSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
