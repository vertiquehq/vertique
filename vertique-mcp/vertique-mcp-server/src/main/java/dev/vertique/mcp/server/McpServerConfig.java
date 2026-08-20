// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Immutable configuration for a single MCP HTTP mount, deserialized from the {@code "mcp"} section
 * of the application config JSON.
 *
 * <p>The {@code @Builder.Default} initializers below are the one authoritative programmatic default
 * source; {@link #defaults()} returns {@code builder().build()} and configuration loading overlays
 * supplied values onto that builder. {@code McpServerConfigValidator} enforces the documented bounds
 * before any route is mounted, so invalid programmatic and loaded configuration fail startup
 * identically.
 */
@Getter
@Builder(toBuilder = true)
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class McpServerConfig {

    /** Whether the MCP mount is installed at all ({@code mcp.enabled}). Defaults to {@code false}. */
    @Builder.Default
    private final boolean enabled = false;

    /** The one literal Router mount path, which must end in {@code /*}. Defaults to {@code "/mcp/*"}. */
    @Builder.Default
    private final String mountPath = "/mcp/*";

    /** Server name reported to clients. Required and non-blank when enabled. */
    @Nullable
    private final String serverName;

    /** Server version reported to clients. Required and non-blank when enabled. */
    @Nullable
    private final String serverVersion;

    /** Optional natural-language guidance for clients. Bounded at 16,384 characters. */
    @Nullable
    private final String instructions;

    /** Optional optional-authentication-capable {@code RouteAuthHandler} scheme name. */
    @Nullable
    private final String authenticationScheme;

    /** Optional JSON mapper profile id applied at the MCP boundary. */
    @Nullable
    private final String jsonProfile;

    /** Exact normalized origins accepted when a request carries an {@code Origin}. Defaults to empty. */
    @Builder.Default
    private final Set<String> allowedOrigins = Set.of();

    /** Maximum JSON nesting depth. Defaults to {@code 64}; range 8–256. */
    @Builder.Default
    private final int jsonMaxDepth = 64;

    /** Maximum properties per JSON object. Defaults to {@code 1000}; range 1–10,000. */
    @Builder.Default
    private final int jsonMaxPropertiesPerObject = 1_000;

    /** Maximum items per JSON array. Defaults to {@code 10000}; range 1–100,000. */
    @Builder.Default
    private final int jsonMaxItemsPerArray = 10_000;

    /** Maximum characters per JSON string. Defaults to {@code 262144}; range 1–1,048,576. */
    @Builder.Default
    private final int jsonMaxStringChars = 262_144;

    /** Streaming output cap in bytes. Defaults to {@code 2097152}; range 1,024–16,777,216. */
    @Builder.Default
    private final int outputMaxBytes = 2_097_152;

    /** Bound on one admitted request in milliseconds. Defaults to {@code 30000}; range 1,000–1,800,000. */
    @Builder.Default
    private final long requestTimeoutMs = 30_000;

    /** Maximum tools returned per {@code tools/list} page. Defaults to {@code 100}; range 1–500. */
    @Builder.Default
    private final int toolsPageSize = 100;

    /**
     * Client cache-freshness hint in milliseconds, emitted as the mandatory {@code ttlMs} of both
     * {@code server/discover} and {@code tools/list}. Defaults to {@code 300000}; range 0–3,600,000.
     */
    @Builder.Default
    private final long toolsTtlMs = 300_000;

    /** Returns the complete, programmatic MCP default configuration. */
    public static McpServerConfig defaults() {
        return builder().build();
    }
}
