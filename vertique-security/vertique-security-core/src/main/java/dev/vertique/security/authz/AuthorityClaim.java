// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable typed authority claim held by a principal.
 *
 * <p>An {@code AuthorityClaim} associates a {@link AuthorityKind semantic category} with a string
 * value, the issuer that granted it, the intended audience, the extraction source, and optional
 * provenance attributes. It replaces the flat {@code Set<String>} approach in
 * {@link dev.vertique.security.SecurityContext} with a richer, auditable representation.
 *
 * <p>{@code attributes} is request-scoped provenance only: it does not survive identity-snapshot
 * durable capture (capture projects free-form attributes out — see
 * {@link dev.vertique.security.IdentitySnapshotFactory}). The typed claim components ({@code
 * kind}, {@code value}, {@code issuer}, {@code audience}, {@code source}) do survive capture;
 * only the free-form attribute map is projected out.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code kind} and {@code value} are required (non-null; {@code value} must also be
 *       non-blank because an empty authority value is meaningless)</li>
 *   <li>{@code issuer}, {@code audience}, and {@code source} are required (non-null) but
 *       MAY be the empty string when not applicable for the claim's origin</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The {@code attributes} map is defensively copied</li>
 * </ul>
 *
 * @param kind       semantic category of this authority claim
 * @param value      the granted authority value (e.g., {@code "admin"}, {@code "read"}) — must not
 *                   be blank
 * @param issuer     identity of the authority that granted this claim; may be empty when the claim
 *                   source is local/hardcoded
 * @param audience   the intended audience for this claim; may be empty when not applicable
 * @param source     where this claim was extracted from (e.g., {@code "jwt-roles"},
 *                   {@code "api-key-registry"}); may be empty when not tracked
 * @param attributes additional provenance attributes associated with this claim
 */
public record AuthorityClaim(
        AuthorityKind kind,
        String value,
        String issuer,
        String audience,
        String source,
        Map<String, Object> attributes) {

    /**
     * Compact constructor — validates required fields and defensively copies the attributes map.
     */
    public AuthorityClaim {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(source, "source");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
