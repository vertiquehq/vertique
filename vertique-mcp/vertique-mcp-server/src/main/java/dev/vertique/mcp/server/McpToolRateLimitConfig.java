// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import jakarta.annotation.Nullable;

/** Typed rate-limit policy override for one generated MCP tool. */
public record McpToolRateLimitConfig(
        String tool,
        String policy,
        @Nullable RateLimitSubject subject,
        @Nullable AnonymousRateLimitPolicy anonymous,
        long cost) {

    /** Validates the keyed tool identity, its referenced policy, and its admission cost. */
    public McpToolRateLimitConfig {
        if (tool == null || tool.isBlank()) {
            throw new ConfigurationException("mcp.rateLimit.tools[<tool>].tool must be non-blank");
        }
        if (policy == null || policy.isBlank()) {
            throw new ConfigurationException("mcp.rateLimit.tools[" + tool + "].policy must be non-blank");
        }
        if (cost < 1) {
            throw new ConfigurationException("mcp.rateLimit.tools[" + tool + "].cost must be at least 1");
        }
    }

    /** Deserializes one keyed tool policy, retaining null overrides for parent inheritance. */
    @JsonCreator
    static McpToolRateLimitConfig fromJson(
            @JsonProperty("tool") @Nullable String tool,
            @JsonProperty("policy") @Nullable String policy,
            @JsonProperty("subject") @Nullable RateLimitSubject subject,
            @JsonProperty("anonymous") @Nullable AnonymousRateLimitPolicy anonymous,
            @JsonProperty("cost") @Nullable Long cost) {
        return new McpToolRateLimitConfig(tool, policy, subject, anonymous, cost != null ? cost : 1L);
    }
}
