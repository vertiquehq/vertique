// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable {@link SecurityContext} implementation for a framework-verified reconstruction,
 * created by {@link SecurityContexts#assembleReconstructed} (PRD identity-002 §14.3 Phase-2
 * Appendix).
 *
 * <p>Identical in shape to {@link DefaultSecurityContext} — the same four typed pillars — plus a
 * fifth field, {@link #marker()}, carrying the typed, unforgeable verified-reconstruction signal
 * this context's {@link #reconstruction()} returns. That extra field is what distinguishes a
 * reconstructed context from a live-authored one at the type level, independent of any
 * string-attribute convention on {@link #authentication()}.
 *
 * <p>Package-private — not part of the security module's public API. Callers use
 * {@link SecurityContexts} and the {@link SecurityContext} interface.
 *
 * @param identity       the identity aggregate; must not be {@code null}
 * @param authentication the authentication state; must not be {@code null}
 * @param authorization  the authorization claims; must not be {@code null}
 * @param origin         the optional network-envelope origin; must not be {@code null} as an
 *                       {@code Optional}
 * @param marker         the typed verified-reconstruction signal; must not be {@code null}
 */
record ReconstructedSecurityContext(
        SecurityIdentity identity,
        AuthenticationState authentication,
        AuthorizationClaims authorization,
        Optional<RequestOrigin> origin,
        ReconstructionMarker marker)
        implements SecurityContext {

    /**
     * Compact constructor — validates that all five fields are non-null.
     */
    ReconstructedSecurityContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(marker, "marker");
    }

    @Override
    public Optional<ReconstructionMarker> reconstruction() {
        return Optional.of(marker);
    }
}
