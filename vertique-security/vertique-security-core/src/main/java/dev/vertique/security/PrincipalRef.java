// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable reference to an authenticated or delegated principal.
 *
 * <p>Captures the principal's {@link PrincipalType type}, its stable identifier, and any
 * provenance attributes (e.g., tenant, issuer, realm) supplied at authentication time.
 *
 * <p>{@code attributes} is request-scoped provenance only and is <strong>non-authoritative for
 * principal identity</strong>: it does not survive identity-snapshot durable capture (capture
 * projects free-form attributes out — see {@link IdentitySnapshotFactory}; the sole exception is a
 * bounded {@code system.reason} entry on a {@link PrincipalType#SYSTEM SYSTEM}-typed actor).
 * Durable principal identity is the {@code (type, id)} tuple, which MUST be stable and unique
 * within the security trust domain without consulting {@code attributes} (PRD identity-002
 * FR-ID-CA-012): applications using tenant-, issuer-, or realm-local identifiers MUST namespace or
 * otherwise qualify {@code id} before constructing the ref (canonical application-generated
 * identifiers — UUIDs or URNs — are preferred over bare usernames). The framework treats
 * {@code id} as opaque and never parses scoping delimiters out of it.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code type} and {@code id} are required (non-null, non-blank)</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The attributes map is defensively copied — mutations to the source after construction do
 *       not affect this record</li>
 * </ul>
 *
 * @param type       the kind of principal
 * @param id         stable identifier for this principal (e.g. user ID, service name)
 * @param attributes provenance attributes supplied at authentication time
 */
public record PrincipalRef(PrincipalType type, String id, Map<String, Object> attributes) {

    /**
     * Compact constructor — validates required fields and defensively copies the attributes map.
     */
    public PrincipalRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
