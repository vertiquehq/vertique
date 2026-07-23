// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.transport;

import dev.vertique.core.exception.TechnicalException;

/**
 * Framework-typed root for outcomes of a failed WebSocket upgrade.
 *
 * <p>Vert.x and the underlying netty handshake can surface a single logical outcome — "the
 * server refused the upgrade" — through several different exception shapes depending on
 * timing, JDK, and runtime configuration. Application and test code that wants to react to
 * these outcomes should not pattern-match on raw third-party exception classes; instead, the
 * raw exception should be translated through {@link WebSocketUpgradeExceptions#translate}
 * into one of the two terminal subtypes:
 *
 * <ul>
 *   <li>{@link WebSocketUpgradeRejected} — the server rejected the upgrade with a recoverable
 *       HTTP status code.</li>
 *   <li>{@link WebSocketUpgradeTransportFailure} — the upgrade failed at the transport layer
 *       (for example, the server closed the socket mid-handshake) and no structured status
 *       could be recovered.</li>
 * </ul>
 *
 * <p>This mirrors the per-layer typed-exception pattern used elsewhere in the framework
 * (see {@code DbExceptionMapper}, {@code RestExceptionMapper}, {@code EventBusExceptionMapper},
 * {@code RestClientExceptionMapper}).
 */
public abstract sealed class WebSocketUpgradeException extends TechnicalException
        permits WebSocketUpgradeRejected, WebSocketUpgradeTransportFailure {

    /**
     * Creates a new {@link WebSocketUpgradeException}.
     *
     * @param message human-readable description; may be {@code null}
     * @param cause   the underlying transport exception; may be {@code null}
     */
    protected WebSocketUpgradeException(String message, Throwable cause) {
        super(message, cause);
    }
}
