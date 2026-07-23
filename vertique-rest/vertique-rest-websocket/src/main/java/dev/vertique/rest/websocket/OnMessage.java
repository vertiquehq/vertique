// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the WebSocket message handler. Called for each incoming text or binary
 * message.
 *
 * <p>The method may accept {@link WebSocketSession} and one typed message parameter
 * (deserialized from JSON via Jackson). If the message parameter type is {@link String},
 * raw text is passed without deserialization. If it is {@link io.vertx.core.buffer.Buffer},
 * raw binary data is passed.
 *
 * <p>Return type must be {@code void} or {@code Future<Void>}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnMessage {}
