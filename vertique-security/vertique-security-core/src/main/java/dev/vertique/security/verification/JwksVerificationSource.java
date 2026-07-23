// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Objects;
import java.util.Optional;

/**
 * Verification source for credentials validated against a JSON Web Key Set (JWKS).
 *
 * <p>Used when a JWT signature has been verified using a public key fetched from the issuer's
 * JWKS endpoint. All fields are optional because specific deployments may not expose every
 * piece of key metadata at the time of verification.
 *
 * @param issuer   the {@code iss} claim value (or JWKS issuer URL); empty if not available
 * @param jwksUri  the JWKS endpoint URI used for key lookup; empty if not available
 * @param kid      the key identifier ({@code kid}) of the signing key; empty if not available
 * @param alg      the algorithm ({@code alg}) used for signature verification; empty if not available
 */
public record JwksVerificationSource(
        Optional<String> issuer, Optional<String> jwksUri, Optional<String> kid, Optional<String> alg)
        implements VerificationSource {

    /**
     * Compact constructor — validates that all Optional fields are non-null references.
     */
    public JwksVerificationSource {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(jwksUri, "jwksUri");
        Objects.requireNonNull(kid, "kid");
        Objects.requireNonNull(alg, "alg");
    }
}
