// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/** Immutable configuration for a single MCP HTTP mount. */
@Value
@Builder(toBuilder = true)
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class McpServerConfig {
    @Builder.Default
    boolean enabled = false;

    @Builder.Default
    String mountPath = "/mcp/*";

    @Nullable
    String serverName;

    @Nullable
    String serverVersion;

    @Nullable
    String instructions;

    @Nullable
    String authenticationScheme;

    @Nullable
    String jsonProfile;

    @Builder.Default
    Set<String> allowedOrigins = Set.of();

    @Builder.Default
    int jsonMaxDepth = 64;

    @Builder.Default
    int jsonMaxPropertiesPerObject = 1_000;

    @Builder.Default
    int jsonMaxItemsPerArray = 10_000;

    @Builder.Default
    int jsonMaxStringChars = 262_144;

    @Builder.Default
    int outputMaxBytes = 2_097_152;

    @Builder.Default
    long requestTimeoutMs = 30_000;

    @Builder.Default
    int toolsPageSize = 100;

    @Builder.Default
    long toolsTtlMs = 300_000;

    /** Returns the complete, programmatic MCP default configuration. */
    public static McpServerConfig defaults() {
        return builder().build();
    }
}
