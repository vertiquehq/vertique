// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

/**
 * Thrown when a REST client request exceeds its configured timeout.
 *
 * <p>This exception signals that the remote server did not respond within the allowed time window.
 * The timeout may be configured at the builder level via {@code readTimeout(long, TimeUnit)} or
 * overridden per-method via the {@link dev.vertique.rest.client.Timeout} annotation.
 *
 * <p>This exception extends {@link RestClientUnavailableException} (not {@link RestClientException}),
 * reflecting that it is a transport-level unavailability rather than a response-level failure.
 *
 * @see dev.vertique.rest.client.Timeout
 * @see RestClientConnectionException
 * @see RestClientUnavailableException
 * @see RestClientResponseException
 */
public class RestClientTimeoutException extends RestClientUnavailableException {

    /**
     * Creates a new timeout exception with the given message.
     *
     * @param message the detail message describing the timeout
     */
    public RestClientTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates a new timeout exception with the given message and underlying cause.
     *
     * @param message the detail message describing the timeout
     * @param cause the underlying exception (typically a Vert.x timeout exception)
     */
    public RestClientTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
