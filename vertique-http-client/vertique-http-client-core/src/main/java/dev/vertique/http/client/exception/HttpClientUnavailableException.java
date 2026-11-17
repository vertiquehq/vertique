// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import dev.vertique.core.exception.UnavailableException;

/**
 * Root of the failures that mean the governed HTTP client cannot serve the call right now. The message
 * is constant and never contains a URI path, query, header value, body text or cause message.
 */
public abstract class HttpClientUnavailableException extends UnavailableException {

    /**
     * Creates an unavailability failure.
     *
     * @param message the constant description of the failure
     */
    protected HttpClientUnavailableException(String message) {
        super(message);
    }
}
