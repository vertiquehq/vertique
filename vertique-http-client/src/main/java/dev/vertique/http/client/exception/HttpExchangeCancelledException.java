// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

/** Thrown when the call was cancelled before it completed. The message is constant. */
public final class HttpExchangeCancelledException extends HttpClientTechnicalException {

    private static final String MESSAGE = "The HTTP exchange was cancelled";

    /** Creates a cancellation failure. */
    public HttpExchangeCancelledException() {
        super(MESSAGE);
    }
}
