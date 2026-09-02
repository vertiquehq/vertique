// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One declared edge admission rule — the exact contract-final public record
 * (contracts/rest-adapter.md, "Edge limiter"). {@code cost} is deliberately {@link OptionalLong}
 * (not the primitive {@code long} {@code spec.md} §8.2 states): the plan's own resolution
 * instruction treats {@code contracts/rest-adapter.md} as authoritative for this task's class
 * inventory, since omitted cost must inherit the referenced policy's {@code defaultCost} — a
 * primitive {@code long} could never distinguish "omitted" from "explicitly zero".
 *
 * @param policy the {@code rateLimit.policies.<name>} this rule evaluates against; required
 * @param key the ordered key dimensions this rule composes; non-empty; {@code GLOBAL} only alone
 * @param headerName the header this rule keys on; required iff {@code key} contains {@code HEADER}
 * @param cost tokens this rule attempts to consume per request; omitted inherits the policy's
 *     {@code defaultCost}; when present must be {@code >= 1}
 * @param missingDimension how a missing/repeated {@code HEADER} value is treated; default
 *     {@code SHARED_BUCKET}
 * @param ipv6PrefixBits the IPv6 aggregation prefix for the {@code IP} dimension; {@code 8..128};
 *     default {@code 64}; meaningless (but harmless) on a rule with no {@code IP} dimension
 */
public record RateLimitEdgeRule(
        String policy,
        List<RateLimitEdgeKeyDimension> key,
        Optional<String> headerName,
        OptionalLong cost,
        MissingDimensionPolicy missingDimension,
        OptionalInt ipv6PrefixBits) {

    /** {@code rateLimit.rest.edge.rules[].ipv6PrefixBits}'s default (contracts/rest-adapter.md). */
    public static final int DEFAULT_IPV6_PREFIX_BITS = 64;

    private static final int MIN_IPV6_PREFIX_BITS = 8;
    private static final int MAX_IPV6_PREFIX_BITS = 128;

    /**
     * Compact constructor — validates every field per contracts/rest-adapter.md's "Rule
     * composition semantics" and "Configuration" tables, and defensively copies {@code key}.
     *
     * @throws ConfigurationException on any bounds/composition violation
     */
    public RateLimitEdgeRule {
        if (policy == null || policy.isBlank()) {
            throw new ConfigurationException("rateLimit.rest.edge.rules[].policy must not be blank");
        }
        Objects.requireNonNull(key, "key");
        key = List.copyOf(key);
        if (key.isEmpty()) {
            throw new ConfigurationException(
                    "rateLimit.rest.edge.rules[].key must not be empty (policy '" + policy + "')");
        }
        if (key.contains(RateLimitEdgeKeyDimension.GLOBAL) && key.size() > 1) {
            throw new ConfigurationException(
                    "rateLimit.rest.edge.rules[].key: GLOBAL is only valid alone (policy '" + policy + "')");
        }
        Objects.requireNonNull(headerName, "headerName");
        if (key.contains(RateLimitEdgeKeyDimension.HEADER) && headerName.isEmpty()) {
            throw new ConfigurationException(
                    "rateLimit.rest.edge.rules[].headerName is required when key contains HEADER (policy '" + policy
                            + "')");
        }
        Objects.requireNonNull(cost, "cost");
        if (cost.isPresent() && cost.getAsLong() < 1) {
            throw new ConfigurationException(
                    "rateLimit.rest.edge.rules[].cost must be >= 1 when present (policy '" + policy + "')");
        }
        Objects.requireNonNull(missingDimension, "missingDimension");
        Objects.requireNonNull(ipv6PrefixBits, "ipv6PrefixBits");
        if (ipv6PrefixBits.isPresent()) {
            int bits = ipv6PrefixBits.getAsInt();
            if (bits < MIN_IPV6_PREFIX_BITS || bits > MAX_IPV6_PREFIX_BITS) {
                throw new ConfigurationException("rateLimit.rest.edge.rules[].ipv6PrefixBits must be between "
                        + MIN_IPV6_PREFIX_BITS + " and " + MAX_IPV6_PREFIX_BITS + " (policy '" + policy + "')");
            }
        }
    }

    /**
     * Jackson-friendly factory. Fills defaults for omitted JSON properties: {@code missingDimension}
     * defaults to {@link MissingDimensionPolicy#SHARED_BUCKET}; {@code headerName}, {@code cost},
     * and {@code ipv6PrefixBits} default to their empty {@link Optional}/{@link OptionalLong}/
     * {@link OptionalInt}.
     *
     * @return the deserialized, validated rule
     */
    @JsonCreator
    static RateLimitEdgeRule fromJson(
            @JsonProperty("policy") @Nullable String policy,
            @JsonProperty("key") @Nullable List<RateLimitEdgeKeyDimension> key,
            @JsonProperty("headerName") @Nullable Optional<String> headerName,
            @JsonProperty("cost") @Nullable OptionalLong cost,
            @JsonProperty("missingDimension") @Nullable MissingDimensionPolicy missingDimension,
            @JsonProperty("ipv6PrefixBits") @Nullable OptionalInt ipv6PrefixBits) {
        return new RateLimitEdgeRule(
                policy,
                key != null ? key : List.of(),
                headerName != null ? headerName : Optional.empty(),
                cost != null ? cost : OptionalLong.empty(),
                missingDimension != null ? missingDimension : MissingDimensionPolicy.SHARED_BUCKET,
                ipv6PrefixBits != null ? ipv6PrefixBits : OptionalInt.empty());
    }

    /**
     * @return {@link #ipv6PrefixBits()} when present, otherwise {@link #DEFAULT_IPV6_PREFIX_BITS}
     */
    public int effectiveIpv6PrefixBits() {
        return ipv6PrefixBits.orElse(DEFAULT_IPV6_PREFIX_BITS);
    }
}
