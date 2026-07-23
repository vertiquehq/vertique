// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable record of authentication assurance metadata sourced from a trusted identity provider.
 *
 * <p>Carries the OpenID Connect / OAuth 2.0 assurance claims that describe how strongly the
 * end-user was authenticated by the IdP:
 * <ul>
 *   <li>{@link #acr()} — Authentication Context Class Reference (RFC 6711 / OIDC §2)</li>
 *   <li>{@link #amr()} — Authentication Methods References (RFC 8176)</li>
 *   <li>{@link #authTime()} — original end-user authentication time</li>
 *   <li>{@link #providerLevel()} — numeric assurance level defined by the provider</li>
 * </ul>
 *
 * @param acr           the ACR value from the ID token or introspection response; empty when
 *                      not present
 * @param amr           the set of authentication methods used (e.g., {@code "pwd"}, {@code "mfa"});
 *                      defensively copied; null treated as empty set
 * @param authTime      the time at which the end-user last authenticated at the IdP; empty
 *                      when not present
 * @param providerLevel optional numeric assurance level defined by the identity provider;
 *                      empty when not present
 */
public record AuthenticationAssurance(
        Optional<String> acr, Set<String> amr, Optional<Instant> authTime, Optional<Integer> providerLevel) {

    /**
     * Compact constructor — validates required Optional fields and defensively copies
     * {@code amr}.
     */
    public AuthenticationAssurance {
        Objects.requireNonNull(acr, "acr");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(providerLevel, "providerLevel");
        amr = Set.copyOf(amr == null ? Set.of() : amr);
    }
}
