// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.verification.VerificationSource;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable proof-of-verification for a single credential check performed during request
 * authentication.
 *
 * <p>An {@code AuthenticationEvidence} record captures only safe, non-sensitive metadata about
 * the verification event. It MUST NOT contain raw tokens, API keys, passwords, or certificates.
 * The {@code safeAttributes} map holds derived, auditable metadata (e.g., JWT ID, key identifiers,
 * token fingerprints) that were extracted at verification time.
 *
 * <p>A request with layered authentication (e.g., mTLS plus JWT) produces one evidence entry per
 * verified credential. All entries are accumulated into
 * {@link AuthenticationState#evidence()}.
 *
 * @param method             the {@link AuthMethod} that was used to verify this credential
 * @param credentialId       stable identifier for the credential (e.g., JWT {@code jti}, API key
 *                           prefix, certificate serial); empty when not available
 * @param verifiedAt         the instant at which verification succeeded
 * @param notAfter           the instant after which this evidence is no longer valid (e.g., JWT
 *                           {@code exp}); empty when the credential has no expiry or expiry is
 *                           unknown
 * @param verificationSource describes the mechanism used to verify the credential
 * @param safeAttributes     auditable, non-sensitive key/value pairs derived from the credential;
 *                           defensively copied; null treated as empty
 */
public record AuthenticationEvidence(
        AuthMethod method,
        Optional<String> credentialId,
        Instant verifiedAt,
        Optional<Instant> notAfter,
        VerificationSource verificationSource,
        Map<String, Object> safeAttributes) {

    /**
     * Compact constructor — validates required fields and defensively copies
     * {@code safeAttributes}.
     */
    public AuthenticationEvidence {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(notAfter, "notAfter");
        Objects.requireNonNull(verificationSource, "verificationSource");
        safeAttributes = Map.copyOf(safeAttributes == null ? Map.of() : safeAttributes);
    }
}
