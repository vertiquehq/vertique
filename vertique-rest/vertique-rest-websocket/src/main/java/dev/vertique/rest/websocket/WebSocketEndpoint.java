// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a WebSocket endpoint. The annotated class must contain at least one
 * lifecycle method annotated with {@link OnOpen}, {@link OnMessage}, {@link OnClose},
 * or {@link OnError}.
 *
 * <p>The {@link #value()} specifies the WebSocket path pattern, which may contain
 * path parameter placeholders (e.g. {@code "/ws/chat/{roomId}"}). Path parameters
 * are extracted using {@link jakarta.ws.rs.PathParam @PathParam} on lifecycle method
 * parameters.
 *
 * <p>Security annotations ({@code @Authorized}, {@code @RolesAllowed}, {@code @PermitAll},
 * {@code @DenyAll}) on the endpoint class are enforced at connection level before the
 * WebSocket upgrade.
 *
 * <p>Example:
 * <pre>{@code
 * @WebSocketEndpoint("/ws/chat/{roomId}")
 * @Authorized(scopes = {"chat:connect"})
 * public class ChatEndpoint {
 *     @OnOpen
 *     void onOpen(WebSocketSession session, @PathParam("roomId") String roomId) { }
 *
 *     @OnMessage
 *     Future<Void> onMessage(WebSocketSession session, ChatMessage message) {
 *         return session.send(new ChatAck("ok"));
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WebSocketEndpoint {

    /**
     * The WebSocket path pattern (e.g. {@code "/ws/chat/{roomId}"}).
     *
     * @return the path pattern
     */
    String value();

    /**
     * Optional authentication scheme name used to select the
     * {@link dev.vertique.rest.core.security.RouteAuthHandler} when multiple auth handlers
     * are registered. When empty (default), the framework uses the single registered handler
     * or fails at startup if multiple handlers exist.
     *
     * @return the auth scheme name, or empty string for auto-selection
     */
    String authScheme() default "";
}
