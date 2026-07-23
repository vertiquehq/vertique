// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable {@link SecurityContext} implementation created by
 * {@link IdentityResolutionMiddleware} after resolving identity from accumulated
 * {@link dev.vertique.security.AuthenticationEvidence}.
 *
 * <p>Holds the four typed pillars of the security model:
 * <ul>
 *   <li>{@link #identity()} — who is authenticated (actor, subject, delegation, client)</li>
 *   <li>{@link #authentication()} — how identity was established (method, evidence)</li>
 *   <li>{@link #authorization()} — what is granted (typed authority claims)</li>
 *   <li>{@link #origin()} — where the request came from (network envelope; captured by
 *       {@link OriginCaptureMiddleware} before any auth handler runs)</li>
 * </ul>
 *
 * <p>Package-private — not part of the security module's public API. Applications should use
 * the {@link SecurityContext} interface.
 *
 * @param identity       the authenticated identity aggregate; must not be {@code null}
 * @param authentication the authentication state; must not be {@code null}
 * @param authorization  the authorization claims; must not be {@code null}
 * @param origin         the optional pre-auth network origin; must not be {@code null} as
 *                       an {@code Optional}
 */
record AuthenticatedSecurityContext(
        SecurityIdentity identity,
        AuthenticationState authentication,
        AuthorizationClaims authorization,
        Optional<RequestOrigin> origin)
        implements SecurityContext {

    /**
     * Compact constructor — validates that all four fields are non-null.
     */
    AuthenticatedSecurityContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(origin, "origin");
    }
}
