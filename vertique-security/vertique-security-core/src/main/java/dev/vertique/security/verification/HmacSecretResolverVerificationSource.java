// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Objects;

/**
 * Verification source for credentials validated using an HMAC shared-secret resolver.
 *
 * <p>Typically used for webhook signature verification (e.g., GitHub, Stripe webhook payloads).
 * The {@code resolverId} identifies the resolver that supplied the shared secret, and
 * {@code algorithm} names the HMAC algorithm (e.g., {@code "HmacSHA256"}).
 *
 * @param resolverId stable identifier of the HMAC secret resolver; must not be null or blank
 * @param algorithm  the HMAC algorithm used (e.g., {@code "HmacSHA256"}); must not be null or blank
 */
public record HmacSecretResolverVerificationSource(String resolverId, String algorithm) implements VerificationSource {

    /**
     * Compact constructor — validates that both {@code resolverId} and {@code algorithm}
     * are non-null and non-blank.
     */
    public HmacSecretResolverVerificationSource {
        Objects.requireNonNull(resolverId, "resolverId");
        if (resolverId.isBlank()) {
            throw new IllegalArgumentException("resolverId must not be blank");
        }
        Objects.requireNonNull(algorithm, "algorithm");
        if (algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be blank");
        }
    }
}
