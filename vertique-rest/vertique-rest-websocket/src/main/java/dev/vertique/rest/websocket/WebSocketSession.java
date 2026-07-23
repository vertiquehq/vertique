// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import java.util.Map;

/**
 * Represents an active WebSocket connection. Provides access to connection metadata,
 * path parameters, and methods to send messages or close the connection.
 *
 * <p>Passed as a parameter to lifecycle methods annotated with {@link OnOpen},
 * {@link OnMessage}, {@link OnClose}, and {@link OnError}. Instances are scoped to
 * the connection lifetime and are not thread-safe — all access should occur on the
 * Vert.x event loop context.
 *
 * <p>Per-request context values (security context, MDC keys, etc.) are propagated through a
 * {@link dev.vertique.context.ContextSnapshot} captured at upgrade time and rebound via
 * {@link dev.vertique.context.ContextValues#bindSnapshot(ContextSnapshot)} inside the
 * {@code RequestContextLifecycle.Handle.afterClose()} task. Lifecycle callbacks can read the
 * security context via {@code SecurityRuntime.current()} or
 * {@code ContextValues.current(SecurityContext.class)}.
 *
 * <p>This interface does not expose ambient context scope manipulation ({@code contextScope}
 * accessors). Scope management is the sole responsibility of {@link WebSocketEndpointRegistrar};
 * user code must not acquire or close the live scope directly.
 */
public interface WebSocketSession {

    /**
     * Returns the unique identifier for this session (UUID).
     *
     * @return the session ID; never {@code null}
     */
    String id();

    /**
     * Returns the underlying Vert.x {@link ServerWebSocket}.
     *
     * @return the raw Vert.x WebSocket; never {@code null}
     */
    ServerWebSocket raw();

    /**
     * Returns the request path from the upgrade request.
     *
     * @return the request path; never {@code null}
     */
    String path();

    /**
     * Returns extracted path parameters from the upgrade URI.
     *
     * @return an immutable map of path parameter names to their values; never {@code null}
     */
    Map<String, String> pathParams();

    /**
     * Returns query parameters from the upgrade request.
     *
     * @return the query parameter map; never {@code null}
     */
    MultiMap queryParams();

    /**
     * Returns headers from the upgrade request.
     *
     * @return the request header map; never {@code null}
     */
    MultiMap headers();

    /**
     * Returns a mutable attributes map scoped to this session. Lifecycle methods may use this
     * to share state across handler invocations for the same connection.
     *
     * @return the mutable session attribute map; never {@code null}
     */
    Map<String, Object> attributes();

    /**
     * Sends an object as a JSON text message via Jackson.
     *
     * @param message the object to serialize and send
     * @return a {@link Future} that completes when the message has been written to the wire
     */
    Future<Void> send(Object message);

    /**
     * Sends a raw text message to the remote peer.
     *
     * @param text the text to send
     * @return a {@link Future} that completes when the message has been written to the wire
     */
    Future<Void> sendText(String text);

    /**
     * Sends a raw binary message to the remote peer.
     *
     * @param data the binary data to send
     * @return a {@link Future} that completes when the message has been written to the wire
     */
    Future<Void> sendBinary(Buffer data);

    /**
     * Closes the WebSocket connection with normal code (1000).
     *
     * @return a {@link Future} that completes when the close handshake is finished
     */
    Future<Void> close();

    /**
     * Closes the WebSocket connection with the given status code and reason phrase.
     *
     * @param statusCode the WebSocket close status code
     * @param reason     the human-readable close reason
     * @return a {@link Future} that completes when the close handshake is finished
     */
    Future<Void> close(short statusCode, String reason);

    /**
     * Returns {@code true} if the connection is open.
     *
     * @return {@code true} when the session is open; {@code false} when it has been closed
     */
    boolean isOpen();
}
