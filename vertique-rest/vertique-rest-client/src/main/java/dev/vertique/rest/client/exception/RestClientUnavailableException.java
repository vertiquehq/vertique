// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

import dev.vertique.core.exception.UnavailableException;

/**
 * Thrown when the outbound REST dependency is unavailable — a connection-level or timeout failure
 * that occurs before or without a usable HTTP response.
 *
 * <p>Maps to HTTP 503 via {@link UnavailableException}. This is the semantic root for both
 * {@link RestClientConnectionException} (transport-level connection failures) and
 * {@link RestClientTimeoutException} (request timeout failures). Use this type in {@code catch}
 * blocks when any transport-level REST client unavailability should be handled uniformly, without
 * distinguishing connection failures from timeouts.
 *
 * @see RestClientConnectionException
 * @see RestClientTimeoutException
 */
public class RestClientUnavailableException extends UnavailableException {

    /**
     * Creates a new unavailable exception with the given message.
     *
     * @param message the detail message describing the unavailability
     */
    public RestClientUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates a new unavailable exception with the given message and underlying cause.
     *
     * @param message the detail message describing the unavailability
     * @param cause the underlying exception (e.g. a transport-level or timeout exception)
     */
    public RestClientUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
