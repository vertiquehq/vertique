// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable aggregate of the authentication state for a single request or channel.
 *
 * <p>An {@code AuthenticationState} always carries a {@link #primaryMethod()} — the
 * {@link AuthMethod} that was decisive for identity resolution. The {@link #evidence()} list
 * holds per-credential verification proofs. For layered authentication (e.g., mTLS plus JWT)
 * multiple entries are present; for simple single-factor authentication there is one entry.
 * Anonymous requests carry an empty evidence list.
 *
 * @param primaryMethod  the decisive authentication method; never null
 * @param evidence       ordered list of per-credential verification proofs; defensively copied;
 *                       null treated as empty list
 * @param assurance      IdP-reported authentication assurance (ACR/AMR/auth_time/provider level);
 *                       empty when not available
 * @param tokens         decoded token metadata (JWT header/claims, introspection response);
 *                       empty when not available
 * @param safeAttributes additional auditable key/value pairs; defensively copied; null treated as
 *                       empty map
 */
public record AuthenticationState(
        AuthMethod primaryMethod,
        List<AuthenticationEvidence> evidence,
        Optional<AuthenticationAssurance> assurance,
        Optional<TokenAttributes> tokens,
        Map<String, Object> safeAttributes) {

    /**
     * Compact constructor — validates required fields and defensively copies mutable inputs.
     */
    public AuthenticationState {
        Objects.requireNonNull(primaryMethod, "primaryMethod");
        Objects.requireNonNull(assurance, "assurance");
        Objects.requireNonNull(tokens, "tokens");
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        safeAttributes = Map.copyOf(safeAttributes == null ? Map.of() : safeAttributes);
    }

    /**
     * Returns the earliest {@code notAfter} instant across all evidence entries.
     *
     * <p>Used by {@code DefaultChannelIdentityManager} to schedule channel auto-expiry based on
     * the soonest-expiring credential in a layered-authentication scenario.
     *
     * @return the earliest non-empty {@link AuthenticationEvidence#notAfter()} across the evidence
     *         list, or {@link Optional#empty()} when no evidence carries an expiry
     */
    public Optional<Instant> earliestNotAfter() {
        return evidence.stream()
                .map(AuthenticationEvidence::notAfter)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Instant::compareTo);
    }
}
