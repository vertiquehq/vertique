// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import dev.vertique.core.exception.ValidationException;

/**
 * Root of the failures that mean the client's policy refuses a request. The message is constant and
 * never contains a URI path, query, header value, body text or cause message.
 */
public abstract class HttpClientValidationException extends ValidationException {

    /**
     * Creates a validation failure.
     *
     * @param message the constant description of the refusal
     */
    protected HttpClientValidationException(String message) {
        super(message);
    }
}
