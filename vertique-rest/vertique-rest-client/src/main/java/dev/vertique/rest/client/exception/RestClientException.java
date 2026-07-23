// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

import dev.vertique.core.exception.TechnicalException;

/**
 * Base exception for non-configuration, non-unavailability REST client technical failures — request
 * preparation, dispatch, or response handling. Maps to HTTP 500 via {@link TechnicalException}.
 * (Configuration failures use {@link RestClientConfigurationException}; connection/timeout
 * unavailability uses {@link RestClientUnavailableException} — neither extends this type.)
 *
 * <p>Thrown when a REST client proxy encounters an unrecoverable error during request preparation,
 * dispatch, or response handling. Subclasses provide more specific failure context:
 * <ul>
 *   <li>{@link RestClientResponseException} — HTTP 4xx/5xx responses from the remote server</li>
 * </ul>
 *
 * <p>Note: configuration failures extend {@link dev.vertique.core.exception.ConfigurationException}
 * via {@link RestClientConfigurationException}, and transport-level unavailability (connection
 * refused, timeouts) extends {@link dev.vertique.core.exception.UnavailableException} via
 * {@link RestClientUnavailableException}. Neither of those branches extends this class.
 */
public class RestClientException extends TechnicalException {

    /**
     * Creates a new exception with the given message.
     *
     * @param message the detail message
     */
    public RestClientException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public RestClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
