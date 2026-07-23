// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.rest.core.sse.BufferOverflowPolicy;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration value object for Server-Sent Events (SSE) settings. Deserialized from the
 * {@code "jaxrs.sse"} section of the application config JSON.
 *
 * <p>All fields have sensible defaults. Applications override individual fields by providing
 * them under {@code "jaxrs.sse"} in their config:
 *
 * <pre>{@code
 * {
 *   "jaxrs": {
 *     "sse": {
 *       "keepAliveIntervalMs": 30000,
 *       "keepAliveEnabled": false,
 *       "defaultBufferSize": 512,
 *       "defaultOverflowPolicy": "DROP_OLDEST"
 *     }
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
public class SseConfig {

    /**
     * Interval in milliseconds between keep-alive comment frames sent to prevent idle proxies
     * from closing the SSE connection. Only active when {@link #keepAliveEnabled()} is
     * {@code true}. Defaults to {@code 15000} (15 seconds).
     */
    @Builder.Default
    private final long keepAliveIntervalMs = 15_000;

    /**
     * Whether the framework automatically sends periodic keep-alive comment frames on idle SSE
     * connections. When {@code true}, a {@code ": keep-alive"} comment is emitted every
     * {@link #keepAliveIntervalMs()} milliseconds. Defaults to {@code true}.
     */
    @Builder.Default
    private final boolean keepAliveEnabled = true;

    /**
     * Default capacity of the per-channel event buffer used when no
     * {@link dev.vertique.rest.core.sse.SseChannelOptions} are provided to
     * {@link dev.vertique.rest.core.sse.SseChannelFactory#create()}. Defaults to {@code 256}.
     */
    @Builder.Default
    private final int defaultBufferSize = 256;

    /**
     * Default {@link BufferOverflowPolicy} applied when the per-channel buffer is full and no
     * explicit policy is configured. Defaults to {@link BufferOverflowPolicy#FAIL}.
     */
    @Builder.Default
    private final BufferOverflowPolicy defaultOverflowPolicy = BufferOverflowPolicy.FAIL;
}
