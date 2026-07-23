// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.chat;

import dev.vertique.rest.websocket.OnClose;
import dev.vertique.rest.websocket.OnError;
import dev.vertique.rest.websocket.OnMessage;
import dev.vertique.rest.websocket.OnOpen;
import dev.vertique.rest.websocket.WebSocketEndpoint;
import dev.vertique.rest.websocket.WebSocketSession;
import io.vertx.core.Future;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.PathParam;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket endpoint for the real-time chat room feature.
 *
 * <p>Each room is identified by the {@code {roomId}} path parameter. All clients connected to the
 * same room receive messages broadcast by any peer in that room. Rooms are isolated — messages
 * posted to room {@code A} are never delivered to room {@code B}.
 *
 * <p>JWT authentication is enforced at the connection level via {@code @RolesAllowed("chat:connect")}.
 * Clients without a valid bearer token are rejected with HTTP 401 before the WebSocket upgrade is
 * completed.
 */
@WebSocketEndpoint("/ws/chat/{roomId}")
@RolesAllowed("chat:connect")
@Slf4j
public class ChatEndpoint {

    private final ChatRoomRegistry registry;

    /**
     * Creates a new {@link ChatEndpoint} backed by the given room registry.
     *
     * @param registry the shared room registry used to track sessions and broadcast events
     */
    @Inject
    ChatEndpoint(ChatRoomRegistry registry) {
        this.registry = registry;
    }

    /**
     * Called when a new WebSocket connection is established. Registers the session in the room and
     * sends a welcome event to the connecting client.
     *
     * @param session the newly opened WebSocket session
     * @param roomId  the room identifier extracted from the path parameter
     */
    @OnOpen
    void onOpen(WebSocketSession session, @PathParam("roomId") String roomId) {
        registry.join(roomId, session);
        session.send(new ChatEvent("welcome", "server", "joined " + roomId, Instant.now()));
    }

    /**
     * Called when a text or binary message is received from a connected client. Broadcasts the
     * message as a {@link ChatEvent} to all other sessions in the same room.
     *
     * @param session the session that sent the message
     * @param msg     the deserialized chat message
     * @return a future that completes when the broadcast has been attempted for all room peers
     */
    @OnMessage
    Future<Void> onMessage(WebSocketSession session, ChatMessage msg) {
        String roomId = session.pathParams().get("roomId");
        return registry.broadcast(roomId, new ChatEvent("message", msg.author(), msg.text(), Instant.now()));
    }

    /**
     * Called when the WebSocket connection is closed. Removes the session from the room registry.
     *
     * @param session the session that was closed
     */
    @OnClose
    void onClose(WebSocketSession session) {
        registry.leave(session);
    }

    /**
     * Called when an error occurs on the WebSocket connection. Logs the error for diagnostics.
     *
     * @param session the session on which the error occurred
     * @param error   the error that was raised
     */
    @OnError
    void onError(WebSocketSession session, Throwable error) {
        log.warn("chat session {} error: {}", session.id(), error.toString());
    }
}
