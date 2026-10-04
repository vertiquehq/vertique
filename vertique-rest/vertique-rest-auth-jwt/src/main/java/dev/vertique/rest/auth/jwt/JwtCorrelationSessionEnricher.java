// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dev.vertique.core.correlation.CorrelationSessionRef;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds a {@link CorrelationSessionRef} from already-validated JWT claims (FR-COR-100).
 *
 * <p>Does not parse tokens. Does not imply identity, delegation, or session validity (FR-COR-103).
 * Kind is {@code jwt-<claimName>} for the built-in {@code sid}/{@code jti} names and
 * {@code jwt-claim} for any other configured claim; source is always {@code jwt-claim}.
 */
final class JwtCorrelationSessionEnricher {

    private static final String SOURCE = "jwt-claim";

    private final JwtSessionCorrelationConfig config;

    JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Selects the first configured claim present as a non-blank string and builds a session ref.
     *
     * @param claims validated JWT claims (e.g. Vert.x {@code User.principal()} map); must not be
     *               {@code null}
     * @return the session ref when a claim matches; empty when enrichment is disabled or no claim
     *         is present
     */
    Optional<CorrelationSessionRef> enrich(Map<String, Object> claims) {
        Objects.requireNonNull(claims, "claims");
        if (!config.enabled()) {
            return Optional.empty();
        }
        for (String claimName : config.claimPreference()) {
            Optional<String> value = stringClaim(claims, claimName);
            if (value.isPresent()) {
                return Optional.of(new CorrelationSessionRef(
                        value.get(), kindFor(claimName), SOURCE, claimName, config.durableSafe(), Map.of()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> stringClaim(Map<String, Object> claims, String claimName) {
        Object raw = claims.get(claimName);
        if (!(raw instanceof String text) || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private static String kindFor(String claimName) {
        return switch (claimName) {
            case "sid" -> "jwt-sid";
            case "jti" -> "jwt-jti";
            default -> "jwt-claim";
        };
    }
}
