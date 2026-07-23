// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable {@link SecurityContext} implementation created by {@link SecurityContexts}
 * for transport-neutral context assembly outside any REST middleware.
 *
 * <p>Holds the four typed pillars of the security model:
 *
 * <ul>
 *   <li>{@link #identity()} — who is authenticated (actor, subject, delegation, client)
 *   <li>{@link #authentication()} — how identity was established (method, evidence)
 *   <li>{@link #authorization()} — what is granted (typed authority claims)
 *   <li>{@link #origin()} — where the request or operation came from, when known
 * </ul>
 *
 * <p>Package-private — not part of the security module's public API. Callers use
 * {@link SecurityContexts} and the {@link SecurityContext} interface.
 *
 * @param identity       the identity aggregate; must not be {@code null}
 * @param authentication the authentication state; must not be {@code null}
 * @param authorization  the authorization claims; must not be {@code null}
 * @param origin         the optional network-envelope origin; must not be {@code null} as an
 *                       {@code Optional}
 */
record DefaultSecurityContext(
        SecurityIdentity identity,
        AuthenticationState authentication,
        AuthorizationClaims authorization,
        Optional<RequestOrigin> origin)
        implements SecurityContext {

    /**
     * Compact constructor — validates that all four fields are non-null.
     */
    DefaultSecurityContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(origin, "origin");
    }
}
