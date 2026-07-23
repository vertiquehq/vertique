// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.core.context.ContextValue;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.Optional;

/**
 * Request-scoped container for the typed security model of a single request or channel.
 *
 * <p>Stored in the Vert.x ContextLocal, accessible throughout the request lifecycle.
 *
 * <p>The interface lives in the core module so that {@link dev.vertique.core.eventbus.DispatchEnvelope}
 * can reference it without creating a circular dependency with the web module.
 *
 * <p>The four pillars of the typed security model:
 * <ul>
 *   <li>{@link #identity()} — who is making the request: actor, optional subject (PSD2), optional
 *       delegation context, and optional OAuth client reference</li>
 *   <li>{@link #authentication()} — how identity was established: primary method, ordered
 *       evidence list, assurance level, and safe token metadata</li>
 *   <li>{@link #authorization()} — what the principal is allowed to do: typed
 *       {@link dev.vertique.security.authz.AuthorityClaim} entries keyed by
 *       {@link dev.vertique.security.authz.AuthorityKind} (ROLE, SCOPE, PERMISSION, etc.)</li>
 *   <li>{@link #origin()} — network envelope captured pre-auth: peer address, forwarded chain,
 *       client IP after trusted-proxy policy, scheme, host, and optional TLS facts</li>
 * </ul>
 */
public interface SecurityContext extends ContextValue {

    /**
     * Returns the identity aggregate describing who is making the request.
     *
     * <p>The identity always has a non-null {@link SecurityIdentity#actor()}. Optional fields
     * ({@link SecurityIdentity#subject()}, {@link SecurityIdentity#delegation()},
     * {@link SecurityIdentity#client()}) are populated for delegation scenarios or when an
     * OAuth client reference is available.
     *
     * @return the security identity; never {@code null}
     */
    SecurityIdentity identity();

    /**
     * Returns the authentication state describing how identity was established.
     *
     * <p>For anonymous requests the primary method is {@link DefaultAuthMethod#none()} and the
     * evidence list is empty.
     *
     * @return the authentication state; never {@code null}
     */
    AuthenticationState authentication();

    /**
     * Returns the authorization claims held by the authenticated principal.
     *
     * <p>Claims are keyed by {@link dev.vertique.security.authz.AuthorityKind} and can be
     * queried by kind via {@link AuthorizationClaims#valuesOf(dev.vertique.security.authz.AuthorityKind)}.
     * For anonymous or unauthenticated requests the claims set is empty.
     *
     * @return the authorization claims; never {@code null}
     */
    AuthorizationClaims authorization();

    /**
     * Returns the network-envelope snapshot captured before authentication ran, or
     * {@link Optional#empty()} when origin capture is not configured (e.g., before
     * {@code OriginCaptureMiddleware} is wired in slice 13).
     *
     * @return an optional {@link RequestOrigin}; never {@code null} as an {@code Optional}
     */
    Optional<RequestOrigin> origin();

    /**
     * Returns an immutable point-in-time snapshot of the security facts this context holds.
     *
     * <p>The snapshot pins {@link #identity()}, {@link #authentication()}, and {@link #origin()}
     * at the moment of the call. Async audit observers and event-bus pipelines should capture a
     * snapshot before crossing a thread boundary to avoid a later context rebind swapping the
     * fact records from under off-thread projection.
     *
     * @return a new {@link SecurityContextSnapshot} capturing the current state of this context
     */
    default SecurityContextSnapshot snapshot() {
        return SecurityContextSnapshot.from(this);
    }

    /**
     * Returns the typed, framework-mediated verified-reconstruction signal this context carries, or
     * {@link Optional#empty()} for every normal, live-authored context.
     *
     * <p>A framework verified-reconstruction (see {@code IdentityReconstruction}) produces a
     * context whose {@code reconstruction()} is present. The marker itself carries no principal of
     * its own — a Mode-2 authorizer resolves the context's own {@link SecurityIdentity#actor() actor}
     * for its {@link dev.vertique.security.authz.PrincipalKey} re-resolution, never a principal read
     * off this marker. A Mode-2 authorizer MUST trigger off this typed accessor — never the
     * descriptive {@code identity.reconstructed=true} string attribute on {@link #authentication()}'s
     * {@link AuthenticationState#safeAttributes() safeAttributes}, which any code populating
     * {@code safeAttributes} could set on an otherwise live-authored context. See
     * {@link ReconstructionMarker}'s own javadoc for the trust model this signal is (and is not) a
     * guarantee of.
     *
     * @return the reconstruction marker, or {@link Optional#empty()} for a live-authored context
     */
    default Optional<ReconstructionMarker> reconstruction() {
        return Optional.empty();
    }
}
