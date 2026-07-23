// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.transport;

import io.vertx.core.http.UpgradeRejectedException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates raw transport exceptions thrown by the Vert.x and netty WebSocket client paths
 * into the framework-typed {@link WebSocketUpgradeException} family.
 *
 * <p>Vert.x can surface a single logical outcome — "the server refused the upgrade" — through
 * at least three different exception shapes:
 *
 * <ul>
 *   <li>{@link UpgradeRejectedException} with a structured {@link UpgradeRejectedException#getStatus() status}.</li>
 *   <li>A netty {@code WebSocketHandshakeException} whose message embeds the status code (e.g.
 *       {@code "Invalid handshake response getStatus: 401 Unauthorized"}).</li>
 *   <li>A netty {@code WebSocketHandshakeException} with no recoverable status, e.g.
 *       {@code "Connection closed while handshake in process"} when the server closes the
 *       socket mid-handshake.</li>
 * </ul>
 *
 * <p>{@link #translate(Throwable)} normalizes any of the above into a typed result so callers
 * can pattern-match on {@link WebSocketUpgradeRejected} (with a recoverable status) or
 * {@link WebSocketUpgradeTransportFailure} (no status available) without depending on raw
 * netty types or string parsing.
 *
 * <p>Typical usage in test code:
 *
 * <pre>{@code
 * client.connect(opts).onComplete(ctx.failing(err -> {
 *     WebSocketUpgradeException upgradeErr = WebSocketUpgradeExceptions.translate(err);
 *     if (upgradeErr instanceof WebSocketUpgradeRejected rejected) {
 *         assertEquals(401, rejected.status());
 *     } else {
 *         assertInstanceOf(WebSocketUpgradeTransportFailure.class, upgradeErr);
 *     }
 *     ctx.completeNow();
 * }));
 * }</pre>
 */
public final class WebSocketUpgradeExceptions {

    /**
     * Matches an HTTP status code (100–599) as a standalone token, with word boundaries on
     * both sides so we don't match a substring of a longer numeric value (e.g. {@code 8080}
     * or {@code 4012}).
     */
    private static final Pattern HTTP_STATUS = Pattern.compile("(?<![0-9])([1-5]\\d{2})(?![0-9])");

    private WebSocketUpgradeExceptions() {}

    /**
     * Translates a raw transport exception into the framework-typed
     * {@link WebSocketUpgradeException} family.
     *
     * <p>If {@code err} is already a {@link WebSocketUpgradeException} it is returned as-is.
     * If it is an {@link UpgradeRejectedException}, the structured status is preserved.
     * Otherwise the message is scanned for a 3-digit HTTP-range status code; if found, the
     * exception is mapped to {@link WebSocketUpgradeRejected}. If no status can be recovered
     * the exception is mapped to {@link WebSocketUpgradeTransportFailure}.
     *
     * @param err the underlying transport exception (must not be {@code null})
     * @return a typed {@link WebSocketUpgradeException}; never {@code null}
     */
    public static WebSocketUpgradeException translate(Throwable err) {
        if (err instanceof WebSocketUpgradeException already) {
            return already;
        }
        if (err instanceof UpgradeRejectedException rejected) {
            return new WebSocketUpgradeRejected(rejected.getStatus(), rejected.getMessage(), rejected);
        }
        String message = err.getMessage() == null ? "" : err.getMessage();
        Matcher matcher = HTTP_STATUS.matcher(message);
        if (matcher.find()) {
            int status = Integer.parseInt(matcher.group(1));
            return new WebSocketUpgradeRejected(status, message, err);
        }
        return new WebSocketUpgradeTransportFailure(message, err);
    }
}
