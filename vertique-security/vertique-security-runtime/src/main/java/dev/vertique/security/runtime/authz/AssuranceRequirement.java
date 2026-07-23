// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

/**
 * The minimum-assurance gate an assurance-gated action places on the caller's authentication
 * (PRD identity-002 FR-ID-CA-005), deserialized as the element type of
 * {@link AssuranceRequirementConfig#patterns()}.
 *
 * <p>{@link AssuranceRequirementNarrower} evaluates both dimensions together: the caller's
 * {@link dev.vertique.security.AuthenticationAssurance#providerLevel() providerLevel} must meet or
 * exceed {@link #minProviderLevel()}, <strong>and</strong> the caller's
 * {@link dev.vertique.security.AuthenticationAssurance#authTime() authTime} must be present and no
 * older than {@link #maxAge()} — a caller that meets the level but whose authentication has decayed
 * past the freshness window is still denied.
 *
 * @param minProviderLevel the minimum IdP-reported {@code providerLevel} the caller's
 *                          {@link dev.vertique.security.AuthenticationAssurance} must meet or
 *                          exceed; must be {@code >= 0}
 * @param maxAge            the freshness window measured from
 *                          {@link dev.vertique.security.AuthenticationAssurance#authTime()}; a
 *                          caller whose {@code authTime} is older than this (or absent) is treated
 *                          as decayed; must be positive
 */
public record AssuranceRequirement(int minProviderLevel, Duration maxAge) {

    /**
     * Compact constructor — validates {@code minProviderLevel} is non-negative and {@code maxAge}
     * is present and strictly positive.
     *
     * @throws ConfigurationException if {@code minProviderLevel} is negative, or {@code maxAge} is
     *                                 {@code null}, zero, or negative
     */
    public AssuranceRequirement {
        if (minProviderLevel < 0) {
            throw new ConfigurationException(
                    "identity.assurance.actions[].minProviderLevel must be >= 0, got: " + minProviderLevel);
        }
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new ConfigurationException("identity.assurance.actions[].maxAgeMs must be > 0");
        }
    }

    /**
     * Jackson-friendly factory. {@code maxAgeMs} carries the framework's explicit-unit config
     * naming convention (duration fields end in {@code Ms}); it is converted to the typed
     * {@link #maxAge()} here so the rest of the framework never handles a bare millisecond long for
     * this requirement.
     *
     * @param minProviderLevel the minimum required provider level; required (no permissive default —
     *                         an omitted value fails the compact constructor's {@code >= 0} check only
     *                         if explicitly negative, so {@code null} defaults to {@code 0})
     * @param maxAgeMs         the freshness window in milliseconds; required, must be {@code > 0}
     * @return the deserialized, validated requirement
     * @throws ConfigurationException if {@code maxAgeMs} is {@code null} or not positive
     */
    @JsonCreator
    static AssuranceRequirement fromJson(
            @JsonProperty("minProviderLevel") @Nullable Integer minProviderLevel,
            @JsonProperty("maxAgeMs") @Nullable Long maxAgeMs) {
        if (maxAgeMs == null) {
            throw new ConfigurationException("identity.assurance.actions[].maxAgeMs is required");
        }
        return new AssuranceRequirement(minProviderLevel != null ? minProviderLevel : 0, Duration.ofMillis(maxAgeMs));
    }

    /**
     * Returns a stable, audit-safe human-readable rendering of this requirement (no secrets), used
     * by {@link AssuranceRequirementNarrower#requirementFor} for
     * {@link dev.vertique.security.authz.RequirementDescriptor#detail()}.
     *
     * @return {@code "minProviderLevel=<n>, maxAgeMs=<n>"}
     */
    String describe() {
        return "minProviderLevel=" + minProviderLevel + ", maxAgeMs=" + maxAge.toMillis();
    }

    /**
     * Combines two requirements into the strictest of the two (FR-ID-AR-003), used by
     * {@link AssuranceRequirementConfig#requirementFor(dev.vertique.security.authz.ActionRef)} when
     * more than one configured action pattern matches the same action.
     *
     * <p>The two dimensions are combined independently, so the result is at least as strict as
     * either input on both: the higher {@link #minProviderLevel()} wins, and the shorter (tighter)
     * {@link #maxAge()} freshness window wins. A wildcard rule can never weaken a more-specific
     * sibling rule, and vice versa.
     *
     * @param a the first requirement; must not be {@code null}
     * @param b the second requirement; must not be {@code null}
     * @return a new requirement carrying the higher {@code minProviderLevel} and the shorter
     *     {@code maxAge} of {@code a} and {@code b}
     * @throws NullPointerException if either argument is {@code null}
     */
    static AssuranceRequirement strictest(AssuranceRequirement a, AssuranceRequirement b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int minProviderLevel = Math.max(a.minProviderLevel(), b.minProviderLevel());
        Duration maxAge = a.maxAge().compareTo(b.maxAge()) <= 0 ? a.maxAge() : b.maxAge();
        return new AssuranceRequirement(minProviderLevel, maxAge);
    }
}
