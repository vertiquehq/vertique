// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;

/**
 * Exception thrown when a REST client accepts a response, 2xx or a status listed by
 * {@link dev.vertique.rest.client.ExpectedStatus}, but its body does not fit the declared return type.
 *
 * <p>The exception carries the status and the raw response like its parent, so a caller can tell which
 * HTTP status carried the body that did not decode. Its message names the client method and the status
 * and never includes body text; the decoder's failure is the cause. Unlike a plain
 * {@link RestClientResponseException}, it does not mean the expectation rejected the response, so
 * {@link #isClientError()} and {@link #isServerError()} describe the accepted status.
 *
 * @see RestClientResponseException
 */
public class RestClientResponseDecodingException extends RestClientResponseException {

    /**
     * Creates the exception for a response body that could not be decoded as the declared type.
     *
     * @param methodName the client method whose return type the body did not fit
     * @param requestContext the request context describing the request
     * @param responseContext the response context containing the HTTP status, headers, and body
     * @param cause the decoder's failure
     */
    public RestClientResponseDecodingException(
            String methodName,
            RestClientRequestContext requestContext,
            RestClientResponseContext responseContext,
            Throwable cause) {
        super(
                "Failed to deserialize response for " + methodName + " (HTTP " + responseContext.statusCode() + ")",
                requestContext,
                responseContext,
                cause);
    }
}
