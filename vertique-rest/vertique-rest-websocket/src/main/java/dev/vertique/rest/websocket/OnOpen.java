// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the WebSocket connection open handler. Called after a successful
 * WebSocket upgrade.
 *
 * <p>The method may accept {@link WebSocketSession},
 * {@link dev.vertique.security.SecurityContext}, and
 * {@link jakarta.ws.rs.PathParam @PathParam}-annotated parameters. Return type must be
 * {@code void} or {@code Future<Void>}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnOpen {}
