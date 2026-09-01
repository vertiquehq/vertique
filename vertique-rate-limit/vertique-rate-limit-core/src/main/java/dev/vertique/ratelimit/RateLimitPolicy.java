// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import java.util.regex.Pattern;

/**
 * One declared rate-limit policy — the exact contract-final public record
 * (contracts/rate-limit-runtime.md, "Policy model"). Resolves from typed
 * {@code rateLimit.policies.<name>} configuration and/or Dagger {@code @IntoSet RateLimitPolicy}
 * contributions; {@code dev.vertique.ratelimit.dagger.RateLimitCoreModule} merges the two tiers
 * (config replaces a same-name programmatic policy wholesale — fields never merge across tiers).
 *
 * <p>Every component is required with no default anywhere — including {@code failureMode} on a
 * disabled policy (FR-006, {@code spec.md} §5.5) — enforced by this record's compact constructor
 * (for reference-typed components) and {@link #fromJson} (for the two primitive components,
 * {@code enabled}/{@code defaultCost}, whose omission would otherwise silently bind to {@code
 * false}/{@code 0}).
 *
 * @param name the policy name; syntax {@code [A-Za-z0-9._~-]{1,128}} (same rule as cache names)
 * @param enabled whether this policy is active
 * @param mode where this policy's admission state lives
 * @param failureMode this policy's explicit backend-failure behavior; no default
 * @param revision bumped on any semantic algorithm/key change; syntax {@code [A-Za-z0-9._-]{1,32}}
 * @param defaultCost tokens consumed when a caller does not pass an explicit cost; bounded
 *     {@code 1..1_000_000_000_000}
 * @param algorithm this policy's admission algorithm
 */
public record RateLimitPolicy(
        String name,
        boolean enabled,
        RateLimitMode mode,
        RateLimitFailureMode failureMode,
        String revision,
        long defaultCost,
        RateLimitAlgorithm algorithm) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Pattern REVISION_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final long MIN_COST = 1L;
    private static final long MAX_COST = 1_000_000_000_000L;

    /**
     * Compact constructor — validates every reference-typed component. {@code enabled} and
     * {@code defaultCost}'s "required, no default" rule is enforced one layer up in
     * {@link #fromJson}, since by the time this constructor runs a primitive has already lost the
     * distinction between "omitted" and "explicitly zero/false".
     *
     * @throws ConfigurationException if {@code name}/{@code revision} fail their pattern, any
     *     required reference-typed component is {@code null}, or {@code defaultCost} is out of
     *     bounds
     */
    public RateLimitPolicy {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ConfigurationException("rateLimit.policies name must match [A-Za-z0-9._~-]{1,128}");
        }
        String path = "rateLimit.policies." + name;
        if (mode == null) {
            throw new ConfigurationException(path + ".mode is required (no default)");
        }
        if (failureMode == null) {
            throw new ConfigurationException(path + ".failureMode is required (no default)");
        }
        if (revision == null || !REVISION_PATTERN.matcher(revision).matches()) {
            throw new ConfigurationException(path + ".revision must match [A-Za-z0-9._-]{1,32}");
        }
        if (defaultCost < MIN_COST || defaultCost > MAX_COST) {
            throw new ConfigurationException(path + ".defaultCost must be between 1 and 1000000000000");
        }
        if (algorithm == null) {
            throw new ConfigurationException(path + ".algorithm is required (no default)");
        }
    }

    /**
     * Jackson-friendly factory. {@code enabled} and {@code defaultCost} are bound as boxed types
     * here so an omitted value fails loudly ("required, no default") instead of silently
     * coercing to {@code false}/{@code 0} before the compact constructor ever sees it.
     *
     * @param name the policy name (identity)
     * @param enabled whether the policy is active; required, no default
     * @param mode where the policy's admission state lives; required, no default
     * @param failureMode the explicit backend-failure behavior; required, no default
     * @param revision the policy revision; required, no default
     * @param defaultCost the default per-request cost; required, no default
     * @param algorithm the admission algorithm; required, no default
     * @return the deserialized, validated policy
     * @throws ConfigurationException if {@code enabled} or {@code defaultCost} is {@code null}
     */
    @JsonCreator
    static RateLimitPolicy fromJson(
            @JsonProperty("name") String name,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("mode") RateLimitMode mode,
            @JsonProperty("failureMode") RateLimitFailureMode failureMode,
            @JsonProperty("revision") String revision,
            @JsonProperty("defaultCost") Long defaultCost,
            @JsonProperty("algorithm") RateLimitAlgorithm algorithm) {
        String path = "rateLimit.policies." + name;
        if (enabled == null) {
            throw new ConfigurationException(path + ".enabled is required (no default)");
        }
        if (defaultCost == null) {
            throw new ConfigurationException(path + ".defaultCost is required (no default)");
        }
        return new RateLimitPolicy(name, enabled, mode, failureMode, revision, defaultCost, algorithm);
    }
}
