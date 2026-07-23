// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.vertique.context.ContextSnapshot;
import dev.vertique.core.context.ContextHolder;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link WebSocketSession} wrapping a Vert.x {@link ServerWebSocket}.
 *
 * <p>Instances are created by {@link WebSocketEndpointRegistrar} for each accepted WebSocket
 * upgrade and are scoped to the connection lifetime. The session is not thread-safe — all access
 * must occur on the Vert.x event loop context.
 *
 * <p>Per-request context values (security context, MDC keys, etc.) are no longer stored
 * directly on the session. Instead, a {@link ContextSnapshot} is captured at upgrade time
 * and rebound via {@link dev.vertique.context.ContextValues#bindSnapshot(ContextSnapshot)}
 * inside the {@code RequestContextLifecycle.Handle.afterClose()} task. The live
 * {@link ContextHolder.Scope} is held on {@link #contextScope()} and closed exactly once after
 * the user's {@link OnClose} future settles.
 */
@Slf4j
class DefaultWebSocketSession implements WebSocketSession {

    // --- Immutable connection fields ---

    private final String id;
    private final ServerWebSocket ws;
    private final Map<String, String> pathParams;
    private final MultiMap queryParams;
    private final MultiMap headers;
    private final Map<String, Object> attributes = new HashMap<>();

    // --- Mutable context fields (set by WebSocketEndpointRegistrar) ---

    /** Snapshot captured from the HTTP upgrade request's context at upgrade time. Set once. */
    private @Nullable ContextSnapshot contextSnapshot;

    /**
     * Live scope holding the session's context bindings. Bound once inside the {@code afterClose}
     * task; cleared (set to {@code null}) by the registrar on bootstrap failure.
     */
    private @Nullable ContextHolder.Scope contextScope;

    /**
     * Creates a new session wrapping the given WebSocket connection.
     *
     * @param ws          the underlying Vert.x WebSocket; must not be {@code null}
     * @param pathParams  path parameters extracted from the upgrade URI; must not be {@code null}
     * @param queryParams query parameters from the upgrade request; must not be {@code null}
     * @param headers     headers from the upgrade request; must not be {@code null}
     */
    DefaultWebSocketSession(
            ServerWebSocket ws, Map<String, String> pathParams, MultiMap queryParams, MultiMap headers) {
        this.id = UUID.randomUUID().toString();
        this.ws = ws;
        this.pathParams = Map.copyOf(pathParams);
        this.queryParams = queryParams;
        this.headers = headers;
    }

    // --- WebSocketSession ---

    @Override
    public String id() {
        return id;
    }

    @Override
    public ServerWebSocket raw() {
        return ws;
    }

    @Override
    public String path() {
        return ws.path();
    }

    @Override
    public Map<String, String> pathParams() {
        return pathParams;
    }

    @Override
    public MultiMap queryParams() {
        return queryParams;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    /**
     * Returns the {@link ContextSnapshot} captured from the HTTP upgrade request context.
     * Set once by {@link WebSocketEndpointRegistrar} before {@code lifecycle.completeNow()}.
     *
     * @return the captured snapshot, or {@code null} before the upgrade handler sets it
     */
    @Nullable
    ContextSnapshot contextSnapshot() {
        return contextSnapshot;
    }

    /**
     * Sets the {@link ContextSnapshot} for this session. Called exactly once by
     * {@link WebSocketEndpointRegistrar} during the upgrade handler.
     *
     * @param snapshot the snapshot to store; must not be {@code null}
     */
    void contextSnapshot(ContextSnapshot snapshot) {
        this.contextSnapshot = snapshot;
    }

    /**
     * Returns the live {@link ContextHolder.Scope} holding this session's context bindings.
     * Bound once inside the {@code afterClose} task; {@code null} before binding or after a
     * failed bootstrap clears it.
     *
     * @return the live session scope, or {@code null}
     */
    @Nullable
    ContextHolder.Scope contextScope() {
        return contextScope;
    }

    /**
     * Sets the live {@link ContextHolder.Scope} for this session. Called exactly once by
     * {@link WebSocketEndpointRegistrar} inside the {@code afterClose} task after snapshot binding.
     * May be set to {@code null} on bootstrap failure to signal the scope has been closed.
     *
     * @param scope the live scope, or {@code null} to clear after a failed bootstrap
     */
    void contextScope(@Nullable ContextHolder.Scope scope) {
        this.contextScope = scope;
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public Future<Void> send(Object message) {
        try {
            String json = DatabindCodec.mapper().writeValueAsString(message);
            return ws.writeTextMessage(json);
        } catch (JsonProcessingException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> sendText(String text) {
        return ws.writeTextMessage(text);
    }

    @Override
    public Future<Void> sendBinary(Buffer data) {
        return ws.writeBinaryMessage(data);
    }

    @Override
    public Future<Void> close() {
        return ws.close();
    }

    @Override
    public Future<Void> close(short statusCode, String reason) {
        return ws.close(statusCode, reason);
    }

    @Override
    public boolean isOpen() {
        return !ws.isClosed();
    }
}
