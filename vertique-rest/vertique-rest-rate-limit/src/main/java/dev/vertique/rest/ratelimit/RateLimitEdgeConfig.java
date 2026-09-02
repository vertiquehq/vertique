// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Root config for the optional edge admission {@code Middleware} — the exact contract-final
 * public record (contracts/rest-adapter.md, "Edge limiter"). Deserialized from the
 * {@code rateLimit.rest.edge} section of the application config.
 *
 * <p>{@code enabled} defaults to {@code false} when the whole {@code rateLimit.rest.edge} section
 * is absent: the edge limiter is an opt-in kill switch, and this module always contributes the
 * unconditional exception-to-HTTP mapping regardless of this flag (contracts/rest-adapter.md,
 * "HTTP mapping" — "always active regardless of this flag"). {@link
 * dev.vertique.core.config.ConfigParser#parse} cannot distinguish "section entirely absent" from
 * "section present but {@code enabled} omitted", so both resolve to the same safe (off) default —
 * mirroring {@code CorsConfig#enabled()}'s own default-off kill-switch convention.
 *
 * @param enabled the edge limiter kill switch; the exception-to-HTTP mapping is always active
 *     regardless of this flag
 * @param rules the ordered edge rules; evaluated in this exact declared order
 * @param path the mount path passed to {@code Middleware.path()}; default {@code "/*"}
 */
public record RateLimitEdgeConfig(boolean enabled, List<RateLimitEdgeRule> rules, String path) {

    /** {@code rateLimit.rest.edge.path}'s default (contracts/rest-adapter.md). */
    public static final String DEFAULT_PATH = "/*";

    /**
     * Compact constructor — defensively copies {@code rules}, requires a non-blank {@code path},
     * and requires at least one rule when {@code enabled} (an enabled edge limiter with no rules
     * would silently admit every request rather than fail startup on the likely-unintended
     * configuration).
     *
     * @throws ConfigurationException if {@code path} is blank, or {@code enabled} is {@code true}
     *     with an empty {@code rules} list
     */
    public RateLimitEdgeConfig {
        Objects.requireNonNull(rules, "rules");
        rules = List.copyOf(rules);
        if (path == null || path.isBlank()) {
            throw new ConfigurationException("rateLimit.rest.edge.path must not be blank");
        }
        if (enabled && rules.isEmpty()) {
            throw new ConfigurationException("rateLimit.rest.edge.rules must not be empty when enabled");
        }
    }

    /**
     * Jackson-friendly factory. Fills defaults for omitted JSON properties: {@code enabled}
     * defaults to {@code false}, {@code rules} to an empty list, {@code path} to {@link
     * #DEFAULT_PATH}.
     *
     * @return the deserialized, validated config
     */
    @JsonCreator
    static RateLimitEdgeConfig fromJson(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("rules") @Nullable List<RateLimitEdgeRule> rules,
            @JsonProperty("path") @Nullable String path) {
        return new RateLimitEdgeConfig(
                enabled != null && enabled, rules != null ? rules : List.of(), path != null ? path : DEFAULT_PATH);
    }

    /**
     * @return the disabled default config (no rules, default path)
     */
    public static RateLimitEdgeConfig disabled() {
        return new RateLimitEdgeConfig(false, List.of(), DEFAULT_PATH);
    }
}
