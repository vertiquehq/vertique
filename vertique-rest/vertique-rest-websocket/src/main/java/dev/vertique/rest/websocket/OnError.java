// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the WebSocket error handler. Called when an exception occurs during
 * message processing or lifecycle handling.
 *
 * <p>The method may accept {@link WebSocketSession} and {@link Throwable}. Return type
 * must be {@code void} or {@code Future<Void>}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnError {}
