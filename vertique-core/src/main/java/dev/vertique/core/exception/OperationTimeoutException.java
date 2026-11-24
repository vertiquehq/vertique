// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Thrown when an operation did not finish within the time allowed for it. Maps to HTTP 504 by default.
 *
 * <p>The timeout counterpart of {@link UnavailableException}'s 503 role: the operation's own deadline
 * elapsed, as opposed to a dependency being unavailable. A module whose deadline failure should answer
 * 504 at a REST boundary throws, or extends, this type, so the boundary needs no knowledge of that
 * module.
 */
public class OperationTimeoutException extends TechnicalException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message; it may reach a client, so it must not carry untrusted data
     */
    public OperationTimeoutException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message; it may reach a client, so it must not carry untrusted data
     * @param cause   the underlying cause
     */
    public OperationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
