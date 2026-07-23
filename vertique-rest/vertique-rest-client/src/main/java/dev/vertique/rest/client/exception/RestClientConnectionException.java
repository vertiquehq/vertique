// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

/**
 * Thrown when a REST client cannot establish a connection to the remote server.
 *
 * <p>Common causes include connection refused, unknown host, no route to host, and other
 * transport-level errors that occur before any HTTP response is received.
 *
 * <p>This exception is produced by the default failure translators registered in
 * {@link dev.vertique.rest.client.DefaultRestClientExceptionMapper} for well-known {@link java.net} exception types.
 *
 * <p>This exception extends {@link RestClientUnavailableException} (not {@link RestClientException}),
 * reflecting that it is a transport-level unavailability rather than a response-level failure.
 *
 * @see RestClientTimeoutException
 * @see RestClientUnavailableException
 * @see RestClientResponseException
 */
public class RestClientConnectionException extends RestClientUnavailableException {

    /**
     * Creates a new connection exception with the given message.
     *
     * @param message the detail message describing the connection failure
     */
    public RestClientConnectionException(String message) {
        super(message);
    }

    /**
     * Creates a new connection exception with the given message and underlying cause.
     *
     * @param message the detail message describing the connection failure
     * @param cause the underlying transport-level exception
     */
    public RestClientConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
