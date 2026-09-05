// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.KeyedBy;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/** Typed configuration for config-bound MCP tool rate-limit admission. */
public record McpRateLimitConfig(
        @Nullable String defaultPolicy,
        RateLimitSubject subject,
        AnonymousRateLimitPolicy anonymous,
        @KeyedBy("tool") List<McpToolRateLimitConfig> tools) {

    /** Makes the keyed tool collection immutable and requires resolved parent defaults. */
    public McpRateLimitConfig {
        subject = Objects.requireNonNull(subject, "subject");
        anonymous = Objects.requireNonNull(anonymous, "anonymous");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }

    /** Deserializes configuration while applying the MCP rate-limit defaults. */
    @JsonCreator
    static McpRateLimitConfig fromJson(
            @JsonProperty("defaultPolicy") @Nullable String defaultPolicy,
            @JsonProperty("subject") @Nullable RateLimitSubject subject,
            @JsonProperty("anonymous") @Nullable AnonymousRateLimitPolicy anonymous,
            @JsonProperty("tools") @Nullable List<McpToolRateLimitConfig> tools) {
        McpRateLimitConfig defaults = defaults();
        return new McpRateLimitConfig(
                defaultPolicy,
                subject != null ? subject : defaults.subject(),
                anonymous != null ? anonymous : defaults.anonymous(),
                tools != null ? tools : List.of());
    }

    /** Returns configuration with no default policy and no per-tool policies. */
    public static McpRateLimitConfig defaults() {
        return new McpRateLimitConfig(
                null, RateLimitSubject.EFFECTIVE_PRINCIPAL, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of());
    }
}
