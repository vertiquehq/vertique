// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;

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
 * @param amr           the authentication methods used (e.g., {@code "pwd"}, {@code "otp"}), in
 *                      first-observed encounter order; defensively copied into an unmodifiable
 *                      {@link SequencedSet}; null treated as empty. Encounter order is part of this
 *                      record's contract: a durable identity snapshot's integrity tag signs the
 *                      serialized {@code amr} array, so the order observed at construction (for a
 *                      decoded snapshot: the stored JSON array order) must survive re-serialization
 *                      byte-for-byte. Do not substitute an unordered set type.
 * @param authTime      the time at which the end-user last authenticated at the IdP; empty
 *                      when not present
 * @param providerLevel optional numeric assurance level defined by the identity provider;
 *                      empty when not present
 */
public record AuthenticationAssurance(
        Optional<String> acr, SequencedSet<String> amr, Optional<Instant> authTime, Optional<Integer> providerLevel) {

    /**
     * Compact constructor — validates required Optional fields and defensively copies
     * {@code amr}.
     */
    public AuthenticationAssurance {
        Objects.requireNonNull(acr, "acr");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(providerLevel, "providerLevel");
        // Encounter order is part of the record's contract and is load-bearing: the snapshot
        // integrity tag signs the serialized amr array, so decode -> re-serialize must
        // reproduce the stored element order (vertique-dev#181).
        SequencedSet<String> copy = amr == null ? new LinkedHashSet<>() : new LinkedHashSet<>(amr);
        copy.forEach(method -> Objects.requireNonNull(method, "amr element"));
        amr = Collections.unmodifiableSequencedSet(copy);
    }
}
