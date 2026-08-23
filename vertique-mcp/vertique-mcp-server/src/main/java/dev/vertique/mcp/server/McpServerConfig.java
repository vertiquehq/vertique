// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.util.Map;
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
 *
 * <p>The class is {@code final} by contract: the configuration surface is closed, so no consumer may
 * widen or reinterpret a documented bound by subclassing it.
 */
@Getter
@Builder(toBuilder = true)
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public final class McpServerConfig {

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

    /** Streaming output cap in bytes. Defaults to {@code 2097152}; range 1,024–16,777,216. */
    @Builder.Default
    private final int outputMaxBytes = 2_097_152;

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

    /**
     * Manually declared only so {@code @Builder} merges its generated fields and fluent setters into
     * this pre-existing class — Lombok's documented "reuse an existing builder class" behavior —
     * instead of generating a brand new one, letting this class additionally carry exactly one
     * hand-written method: {@link #rejectRetiredKey}, a package-private {@code @JsonAnySetter} that
     * fails startup on one of the five configuration keys the T007 rebaseline removed (issue #424)
     * while leaving every other unrecognized property forward-compatible, matching {@link
     * McpServerConfig}'s class-level {@code ignoreUnknown = true} for everything that is not a retired
     * key. Jackson checks a builder's {@code @JsonAnySetter} before falling back to "ignore unknown",
     * so this one method intercepts both cases without adding any public field, getter, or builder
     * setter to {@link McpServerConfig}'s frozen public shape (contract §4.5) — {@link
     * #rejectRetiredKey} itself is package-private, invoked by Jackson via reflection only.
     */
    public static class McpServerConfigBuilder {

        /** Guidance shared by all four removed {@code mcp.json.max*} JSON-shape limit keys. */
        private static final String RETIRED_JSON_LIMIT_GUIDANCE =
                "JSON-shape limits are now Jackson's own frozen StreamReadConstraints inside the "
                        + "private envelope codec (see the module reference); there is no direct "
                        + "replacement configuration key";

        /**
         * The five keys the T007 architecture rebaseline removed, mapped to the operator-facing
         * replacement guidance issue #424 requires — each message names the retired key's successor
         * (or explains why none exists) rather than merely saying the key is gone.
         */
        private static final Map<String, String> RETIRED_KEYS = Map.of(
                "requestTimeoutMs",
                        "MCP arms no whole-request deadline of its own; move the equivalent protection to "
                                + "http.idleTimeoutSeconds / http.readIdleTimeoutSeconds / "
                                + "http.writeIdleTimeoutSeconds",
                "jsonMaxDepth", RETIRED_JSON_LIMIT_GUIDANCE,
                "jsonMaxPropertiesPerObject", RETIRED_JSON_LIMIT_GUIDANCE,
                "jsonMaxItemsPerArray", RETIRED_JSON_LIMIT_GUIDANCE,
                "jsonMaxStringChars", RETIRED_JSON_LIMIT_GUIDANCE);

        /**
         * Intercepts every property Jackson would otherwise treat as unrecognized. A retired key
         * (issue #424) fails startup naming its successor; any other unrecognized property is
         * silently ignored, staying forward-compatible exactly as {@link McpServerConfig}'s
         * class-level {@code ignoreUnknown = true} already promises for everything else.
         *
         * @param name the unrecognized JSON property name
         * @param value the unrecognized property's value; never inspected — only presence matters
         * @throws ConfigurationException if {@code name} is one of the five retired keys
         */
        @JsonAnySetter
        void rejectRetiredKey(String name, Object value) {
            String guidance = RETIRED_KEYS.get(name);
            if (guidance != null) {
                throw new ConfigurationException("mcp." + name + " was removed; " + guidance);
            }
        }
    }
}
