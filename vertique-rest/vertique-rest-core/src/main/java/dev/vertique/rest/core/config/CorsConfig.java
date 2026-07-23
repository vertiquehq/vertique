// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * CORS configuration for the REST framework. Deserialized from the {@code "cors"} section
 * of the application config JSON.
 *
 * <p>When {@link #enabled()} is {@code false} (the default), no CORS handler is installed
 * and all fields are ignored. When enabled, the framework installs a Vert.x
 * {@link io.vertx.ext.web.handler.CorsHandler} before any route mounts, covering all routes
 * including preflight {@code OPTIONS} requests.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * {
 *   "cors": {
 *     "enabled": true,
 *     "origins": ["https://app.example.com"],
 *     "allowCredentials": true,
 *     "allowedHeaders": ["Authorization", "Content-Type"]
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
public class CorsConfig {

    /**
     * Whether CORS is enabled. When {@code false} (the default), no CORS handler is
     * installed and all other fields are ignored.
     */
    @Builder.Default
    private final boolean enabled = false;

    /**
     * Allowed origin patterns. Each entry may be an exact origin
     * (e.g. {@code "https://example.com"}) or a wildcard ({@code "*"}).
     * Defaults to {@code ["*"]} (all origins allowed).
     */
    @Builder.Default
    private final List<String> origins = List.of("*");

    /**
     * HTTP methods allowed in CORS requests. Corresponds to the
     * {@code Access-Control-Allow-Methods} response header.
     * Defaults to {@code GET, POST, PUT, DELETE, PATCH, OPTIONS}.
     */
    @Builder.Default
    private final Set<String> allowedMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");

    /**
     * Request headers allowed in CORS requests. Corresponds to the
     * {@code Access-Control-Allow-Headers} response header.
     * Defaults to {@code ["*"]} (all headers allowed).
     */
    @Builder.Default
    private final Set<String> allowedHeaders = Set.of("*");

    /**
     * Response headers that the browser is allowed to access. Corresponds to the
     * {@code Access-Control-Expose-Headers} response header.
     * Defaults to empty (no headers exposed beyond the CORS-safe list).
     */
    @Builder.Default
    private final Set<String> exposedHeaders = Set.of();

    /**
     * Whether the browser may send credentials (cookies, HTTP authentication, and
     * client-side TLS certificates) with CORS requests. Corresponds to the
     * {@code Access-Control-Allow-Credentials} response header.
     * Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean allowCredentials = false;

    /**
     * How long (in seconds) the browser may cache a preflight response.
     * Corresponds to the {@code Access-Control-Max-Age} response header.
     * Defaults to {@code 3600} (one hour).
     */
    @Builder.Default
    private final int maxAge = 3600;
}
