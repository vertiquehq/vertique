// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the management HTTP server and health check endpoints.
 *
 * <p>Deserialized from the {@code management} section of the application configuration:
 *
 * <pre>{@code
 * {
 *   "management": {
 *     "port": 9090,
 *     "host": "0.0.0.0",
 *     "enabled": true,
 *     "healthCheckTimeoutSeconds": 5
 *   }
 * }
 * }</pre>
 *
 * <p>All fields have sensible defaults and are optional in the configuration. A blank
 * {@code host} counts as absent and takes the default.
 *
 * @see ManagementModule
 * @see ManagementVerticle
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
public class ManagementConfig {

    /** The port for the management HTTP server (default 9090). */
    @Builder.Default
    private final int port = 9090;

    /** The bind address used when {@code host} is absent, {@code null} or blank: all interfaces. */
    private static final String DEFAULT_HOST = "0.0.0.0";

    /** Network interface the management server binds to. Defaults to {@code "0.0.0.0"} (all interfaces). */
    @Builder.Default
    private final String host = DEFAULT_HOST;

    /**
     * Returns the network interface the management server binds to. A {@code null} or blank
     * configured value counts as absent and yields the default, {@code "0.0.0.0"}.
     *
     * @return the bind address, never {@code null} or blank
     */
    public String host() {
        return host == null || host.isBlank() ? DEFAULT_HOST : host;
    }

    /** Whether management endpoints are enabled (default {@code true}). */
    @Builder.Default
    private final boolean enabled = true;

    /** Per-check timeout in seconds for health check probes (default 5). */
    @Builder.Default
    private final long healthCheckTimeoutSeconds = 5L;
}
