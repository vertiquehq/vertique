// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable reference to the OAuth 2.0 client (application) that initiated a request.
 *
 * <p>In a delegated-authorization flow the {@link SecurityIdentity} carries both the acting
 * {@link PrincipalRef} and an optional {@code ClientRef} that identifies the OAuth application
 * through which the request arrived. The {@code source} field records where the client identity
 * was extracted from (e.g. {@code "jwt-azp"}, {@code "api-key-registry"}).
 *
 * <p>{@code attributes} is request-scoped provenance only and is non-authoritative for client
 * identity: it does not survive identity-snapshot durable capture (capture projects client
 * attributes out unconditionally — see {@link IdentitySnapshotFactory}). A client that must remain
 * distinguishable across a durable boundary must carry that scoping in {@code clientId} itself,
 * which the framework treats as opaque (PRD identity-002 FR-ID-CA-012).
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code clientId} and {@code source} are required (non-null, non-blank)</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The attributes map is defensively copied</li>
 * </ul>
 *
 * @param clientId   stable OAuth 2.0 client identifier (e.g. the {@code azp} or {@code client_id}
 *                   claim value)
 * @param source     where this client identity was extracted from (e.g. {@code "jwt-azp"},
 *                   {@code "api-key-registry"})
 * @param attributes additional provenance attributes associated with the client
 */
public record ClientRef(String clientId, String source, Map<String, Object> attributes) {

    /**
     * Compact constructor — validates required fields and defensively copies the attributes map.
     */
    public ClientRef {
        Objects.requireNonNull(clientId, "clientId");
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
