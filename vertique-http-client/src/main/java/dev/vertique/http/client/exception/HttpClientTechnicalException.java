// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import dev.vertique.core.exception.TechnicalException;

/**
 * Root of the technical failures the governed HTTP client raises itself. The message is constant and
 * never contains a URI path, query, header value, body text or cause message.
 */
public abstract class HttpClientTechnicalException extends TechnicalException {

    /**
     * Creates a technical failure.
     *
     * @param message the constant description of the failure
     */
    protected HttpClientTechnicalException(String message) {
        super(message);
    }
}
