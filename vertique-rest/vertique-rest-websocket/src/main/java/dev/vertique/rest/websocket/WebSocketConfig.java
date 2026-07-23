// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the WebSocket module. Deserialized from the {@code "websocket"} section
 * of the application config JSON.
 *
 * <pre>{@code
 * {
 *   "websocket": {
 *     "basePath": "/ws/*"
 *   }
 * }
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class WebSocketConfig {

    /**
     * Mount path for the WebSocket sub-router. Must start with {@code /} and end with
     * {@code /*}. Defaults to {@code "/*"}.
     */
    @Builder.Default
    private final String basePath = "/*";
}
