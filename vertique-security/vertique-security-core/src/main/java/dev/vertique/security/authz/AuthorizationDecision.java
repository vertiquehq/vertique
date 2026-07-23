// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of an {@link AuthorizationPolicy} evaluation.
 *
 * <p>An {@code AuthorizationDecision} carries:
 * <ul>
 *   <li>A permit/deny verdict ({@link #permitted()})</li>
 *   <li>A machine-readable {@link #reasonCode()} for audit logs and error responses</li>
 *   <li>Optional {@link #policyId()} and {@link #policyVersion()} for forensics and traceability</li>
 *   <li>Optional {@link #safeAttributes()} — audit-safe attributes that may be logged or returned
 *       in error responses without leaking sensitive data</li>
 * </ul>
 *
 * <p><strong>{@code safeAttributes} is audit-safe by contract, not by enforcement.</strong> The map
 * is intended to be surfaced in audit logs and error responses, so callers MUST NOT place sensitive
 * data in it (tokens, credentials, PII, raw claim values). Equally it is descriptive output only — it
 * MUST NOT be read back as a trust source or re-used to make a subsequent authorization decision.
 *
 * <p>Convenience factories {@link #permit(String)} and {@link #deny(String)} cover the common cases
 * where policy and version metadata are not needed.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code reasonCode} is required (non-null, non-blank)</li>
 *   <li>{@code policyId} and {@code policyVersion} are required {@link Optional} references
 *       (must not be {@code null}, but may be {@link Optional#empty()})</li>
 *   <li>A null {@code safeAttributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The {@code safeAttributes} map is defensively copied</li>
 * </ul>
 *
 * @param permitted      {@code true} if the requested action is permitted; {@code false} if denied
 * @param reasonCode     machine-readable reason code for the decision (e.g., {@code "PERMITTED"},
 *                       {@code "ROLE_MISSING"}); must not be blank
 * @param policyId       optional identifier of the policy that produced this decision
 * @param policyVersion  optional version of the policy that produced this decision
 * @param safeAttributes audit-safe attributes that may be included in logs or error responses; must
 *                       not carry sensitive data and must not be used as a trust source for a
 *                       subsequent decision
 */
public record AuthorizationDecision(
        boolean permitted,
        String reasonCode,
        Optional<String> policyId,
        Optional<String> policyVersion,
        Map<String, Object> safeAttributes) {

    /**
     * Compact constructor — validates required fields and defensively copies the safeAttributes map.
     */
    public AuthorizationDecision {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        safeAttributes = Map.copyOf(safeAttributes == null ? Map.of() : safeAttributes);
    }

    /**
     * Creates a permit decision with the given reason code and no policy metadata.
     *
     * @param reasonCode machine-readable reason code; must not be blank
     * @return a permitted {@code AuthorizationDecision}
     */
    public static AuthorizationDecision permit(String reasonCode) {
        return new AuthorizationDecision(true, reasonCode, Optional.empty(), Optional.empty(), Map.of());
    }

    /**
     * Creates a deny decision with the given reason code and no policy metadata.
     *
     * @param reasonCode machine-readable reason code; must not be blank
     * @return a denied {@code AuthorizationDecision}
     */
    public static AuthorizationDecision deny(String reasonCode) {
        return new AuthorizationDecision(false, reasonCode, Optional.empty(), Optional.empty(), Map.of());
    }
}
