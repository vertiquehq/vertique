// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for binding a JWT claim into {@link dev.vertique.core.correlation.CorrelationSessionRef}
 * after authentication succeeds (Correlation Context PRD §7.6 / FR-COR-100..105).
 *
 * <p>Parsed from {@code jwt.sessionCorrelation}. Defaults prefer {@code sid}, then {@code jti}, with
 * {@code durableSafe=false} so the session id is not persisted or projected into audit unless the
 * operator explicitly opts in.
 *
 * @param claimPreference ordered claim names to try as the session id; first non-blank string wins
 * @param durableSafe     whether the resulting session ref may be persisted / audit-projected
 * @param enabled         when {@code false}, enrichment is a no-op
 */
public record JwtSessionCorrelationConfig(List<String> claimPreference, boolean durableSafe, boolean enabled) {

    private static final List<String> DEFAULT_CLAIM_PREFERENCE = List.of("sid", "jti");

    /**
     * Compact constructor — normalizes null preference to the default order and copies the list.
     */
    public JwtSessionCorrelationConfig {
        if (claimPreference == null || claimPreference.isEmpty()) {
            claimPreference = DEFAULT_CLAIM_PREFERENCE;
        } else {
            List<String> cleaned = new ArrayList<>(claimPreference.size());
            for (String claim : claimPreference) {
                Objects.requireNonNull(claim, "claimPreference element");
                if (claim.isBlank()) {
                    throw new IllegalArgumentException("claimPreference elements must not be blank");
                }
                cleaned.add(claim);
            }
            claimPreference = List.copyOf(cleaned);
        }
    }

    /**
     * Jackson factory with defaults for omitted properties.
     *
     * @param claimPreference ordered claim names; defaults to {@code sid}, {@code jti}
     * @param durableSafe     durable/audit safety flag; defaults to {@code false}
     * @param enabled         whether enrichment runs; defaults to {@code true}
     * @return the config
     */
    @JsonCreator
    public static JwtSessionCorrelationConfig fromJson(
            @JsonProperty("claimPreference") @Nullable List<String> claimPreference,
            @JsonProperty("durableSafe") @Nullable Boolean durableSafe,
            @JsonProperty("enabled") @Nullable Boolean enabled) {
        JwtSessionCorrelationConfig d = defaults();
        return new JwtSessionCorrelationConfig(
                claimPreference != null ? claimPreference : d.claimPreference,
                durableSafe != null ? durableSafe : d.durableSafe,
                enabled != null ? enabled : d.enabled);
    }

    /**
     * Default enrichment: {@code sid} then {@code jti}, not durable-safe, enabled.
     *
     * @return the default config; never {@code null}
     */
    public static JwtSessionCorrelationConfig defaults() {
        return new JwtSessionCorrelationConfig(DEFAULT_CLAIM_PREFERENCE, false, true);
    }
}
