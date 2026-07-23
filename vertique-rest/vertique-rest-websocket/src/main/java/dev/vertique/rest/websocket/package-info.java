// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation-driven WebSocket endpoint module for the Vertique framework.
 *
 * <p>Provides a dedicated {@link dev.vertique.rest.core.router.RouterMount}-based WebSocket
 * runtime separate from the JAX-RS response pipeline. WebSocket endpoints are declared via
 * {@link dev.vertique.rest.websocket.WebSocketEndpoint @WebSocketEndpoint} with lifecycle
 * hooks ({@link dev.vertique.rest.websocket.OnOpen @OnOpen},
 * {@link dev.vertique.rest.websocket.OnMessage @OnMessage},
 * {@link dev.vertique.rest.websocket.OnClose @OnClose},
 * {@link dev.vertique.rest.websocket.OnError @OnError}) and automatic Jackson message
 * serialization.
 *
 * <p>Security annotations ({@code @Authorized}, {@code @RolesAllowed}, {@code @PermitAll},
 * {@code @DenyAll}) are enforced at connection level using the same
 * {@link dev.vertique.rest.core.security.SecurityPolicy} model as JAX-RS routes.
 */
package dev.vertique.rest.websocket;
