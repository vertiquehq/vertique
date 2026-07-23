// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.transport;

/**
 * The WebSocket upgrade failed at the transport layer with no recoverable HTTP status.
 *
 * <p>Typical cause: the server closed the socket mid-handshake before the response status
 * line could be parsed (for example, {@code "Connection closed while handshake in process"}
 * surfacing under CI scheduler load). The original transport exception is available via
 * {@link #getCause()}.
 *
 * <p>This outcome is observable but does not indicate the rejection cause. Callers that need
 * to distinguish rejection causes should retry the upgrade — most transient transport
 * failures resolve on a subsequent attempt and surface as {@link WebSocketUpgradeRejected}.
 */
public final class WebSocketUpgradeTransportFailure extends WebSocketUpgradeException {

    /**
     * Creates a new {@link WebSocketUpgradeTransportFailure}.
     *
     * @param message human-readable description; may be {@code null}
     * @param cause   the underlying transport exception; may be {@code null}
     */
    public WebSocketUpgradeTransportFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
