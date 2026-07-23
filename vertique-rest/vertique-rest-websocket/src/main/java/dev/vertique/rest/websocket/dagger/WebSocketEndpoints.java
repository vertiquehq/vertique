// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.dagger;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Dagger qualifier for the WebSocket endpoint multibinding set. Annotate
 * {@code @Provides @IntoSet @WebSocketEndpoints} methods to contribute endpoint
 * instances to the WebSocket module.
 *
 * <p>Example:
 * <pre>{@code
 * @Provides @IntoSet @WebSocketEndpoints
 * Object chatEndpoint(ChatEndpoint e) { return e; }
 * }</pre>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface WebSocketEndpoints {}
