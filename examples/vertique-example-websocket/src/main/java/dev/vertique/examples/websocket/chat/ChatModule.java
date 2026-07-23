// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.chat;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.websocket.dagger.WebSocketEndpoints;

/**
 * Dagger module that contributes the chat endpoint to the WebSocket endpoint multibinding.
 *
 * <p>The {@link ChatRoomRegistry} singleton is provided automatically via constructor injection —
 * no explicit {@code @Provides} method is needed.
 */
@Module
public abstract class ChatModule {

    /**
     * Contributes the {@link ChatEndpoint} instance to the {@link WebSocketEndpoints} multibinding
     * so the WebSocket framework discovers and registers it.
     *
     * @param endpoint the chat endpoint instance
     * @return the endpoint wrapped as {@code Object} for the multibinding
     */
    @Provides
    @IntoSet
    @WebSocketEndpoints
    static Object chatEndpoint(ChatEndpoint endpoint) {
        return endpoint;
    }
}
