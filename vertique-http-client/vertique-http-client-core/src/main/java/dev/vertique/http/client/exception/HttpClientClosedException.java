// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

/** Thrown when the client was closed before or during the call. The message is constant. */
public final class HttpClientClosedException extends HttpClientUnavailableException {

    private static final String MESSAGE = "The HTTP client is closed";

    /** Creates a closed-client failure. */
    public HttpClientClosedException() {
        super(MESSAGE);
    }
}
