// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.transport;

/**
 * The server rejected the WebSocket upgrade with a recoverable HTTP status code.
 *
 * <p>Carries the parsed status (for example, {@code 401} for missing authentication) and the
 * original transport exception as the cause for diagnostics.
 */
public final class WebSocketUpgradeRejected extends WebSocketUpgradeException {

    private final int status;

    /**
     * Creates a new {@link WebSocketUpgradeRejected}.
     *
     * @param status  the HTTP status code returned by the server (1xx–5xx)
     * @param message human-readable description; may be {@code null}
     * @param cause   the underlying transport exception; may be {@code null}
     */
    public WebSocketUpgradeRejected(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * Returns the HTTP status code returned by the server.
     *
     * @return the HTTP status (e.g. 401, 403, 404, 503)
     */
    public int status() {
        return status;
    }
}
